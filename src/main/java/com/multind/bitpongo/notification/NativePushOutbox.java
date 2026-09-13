package com.multind.bitpongo.notification;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Native fanout shares the publisher transaction but has independent recipient dedupe and per-device state. */
@Profile("!test")
@Service
public class NativePushOutbox {
    private final JdbcTemplate jdbc;
    private final NotificationMessageRenderer renderer;
    private final NotificationPayloadSanitizer sanitizer;
    private final NotificationAudienceResolver audiences;
    private final NotificationDedupeWindowStore windows;
    private final JsonMapper json = JsonMapper.builder().build();
    private final int ttlSeconds;
    public NativePushOutbox(JdbcTemplate jdbc, NotificationMessageRenderer renderer,
            NotificationPayloadSanitizer sanitizer, NotificationAudienceResolver audiences,
            NotificationDedupeWindowStore windows, @Value("${FCM_ALERT_TTL_SECONDS:86400}") int ttlSeconds) {
        this.jdbc=jdbc; this.renderer=renderer; this.sanitizer=sanitizer; this.audiences=audiences; this.windows=windows;
        this.ttlSeconds=Math.max(60,Math.min(86400,ttlSeconds));
    }
    @Transactional public void enqueue(NotificationEvent original, LocalDateTime now) {
        for (long userId : audiences.resolveNative(original).stream().sorted().toList()) {
            // Serialize with account soft deletion so an in-flight fanout cannot recreate its pending jobs.
            var active=jdbc.queryForList("select status from user where id=? for update",String.class,userId);
            if(active.isEmpty()||!"active".equals(active.getFirst()))continue;
            var devices = jdbc.queryForList("""
                    select d.*,s.trade_succeeded,s.trade_failed,s.plan_alerts,s.system_alerts,s.sound_enabled
                    from push_device d join user_push_setting s on s.user_id=d.user_id
                    join user u on u.id=d.user_id
                    where d.user_id=? and d.enabled=true and s.enabled=true and u.status='active'
                    """,userId);
            if (devices.isEmpty() || !allowed(devices.getFirst(),original.type())) continue;
            String base = original.dedupeKey();
            if (original.dedupeWindow() != null) {
                String scope = NativePushService.hash("NATIVE:"+userId+":"+original.dedupeWindow().scopeKey());
                if (!windows.tryAcquire(scope,now,now.plus(original.dedupeWindow().duration()))) continue;
                base += ":WINDOW:"+now;
            }
            Map<String,Object> payload = sanitizer.sanitize(original);
            @SuppressWarnings("unchecked") Map<String,Object> attrs=(Map<String,Object>)payload.get("attributes");
            NotificationEvent event = new NotificationEvent(original.type(), userId, original.planId(), original.intentId(),
                    original.scheduledAt(),original.occurredAt(),base,attrs);
            String eventId=NativePushService.hash("NATIVE:"+userId+":"+base);
            for(var device:devices) {
                BarkMessage rendered=renderer.render(event,(String)device.get("locale"),(String)device.get("timezone"),null);
                insert(userId,device,eventId,event.type().name(),message(eventId,userId,event.type().name(),event.planId(),
                        rendered.title(),rendered.body()),now);
            }
        }
    }
    @Transactional public void enqueueTest(long userId,String installationId,LocalDateTime now) {
        var devices=jdbc.queryForList("select d.* from push_device d join user_push_setting s on s.user_id=d.user_id where d.user_id=? and installation_id=? and d.enabled=true",userId,installationId);
        if (devices.isEmpty()) throw NativePushService.invalid("设备不可用");
        String id=NativePushService.hash(UUID.randomUUID().toString());
        String locale=(String)devices.getFirst().get("locale");
        String title="en-US".equals(locale)?"Notification test":"zh-TW".equals(locale)?"通知測試":"通知测试";
        String body="en-US".equals(locale)?"Bitpongo notifications are ready.":"zh-TW".equals(locale)?"Bitpongo 通知已就緒。":"Bitpongo 通知已就绪。";
        insert(userId,devices.getFirst(),id,"PUSH_TEST",message(id,userId,"PUSH_TEST",null,title,body),now);
    }
    private void insert(long userId,Map<String,Object> device,String eventId,String eventType,Map<String,Object> message,LocalDateTime now) {
        String installation=(String)device.get("installation_id");
        String dedupe=NativePushService.hash(eventId+":"+installation);
        jdbc.update("""
                insert into push_delivery(event_id,dedupe_key,user_id,installation_id,binding_version,event_type,
                    message,next_attempt_at,expires_at,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,?,?)
                on duplicate key update dedupe_key=dedupe_key
                """,eventId,dedupe,userId,installation,device.get("binding_version"),eventType,
                json.writeValueAsString(message),now,now.plusSeconds(ttlSeconds),now,now);
    }
    static Map<String,Object> message(String eventId,long userId,String type,Long planId,String title,String body) {
        var data=new LinkedHashMap<String,String>();
        data.put("eventId",eventId);data.put("userId",Long.toString(userId));data.put("eventType",type);
        if(planId!=null && planId>0) data.put("planId",planId.toString());
        return new LinkedHashMap<>(Map.of("data",data,"notification",Map.of("title",truncate(title,100),"body",truncate(body,500))));
    }
    private static String truncate(String text,int limit) {
        int[] points=text.codePoints().filter(c->c=='\n'||!Character.isISOControl(c)).limit(limit).toArray();
        return new String(points,0,points.length);
    }
    static boolean allowed(Map<String,Object> setting,NotificationEventType type) {
        String field=switch(NativePushPolicy.category(type)) {
            case "tradeSucceeded" -> "trade_succeeded";
            case "tradeFailed" -> "trade_failed";
            case "planAlerts" -> "plan_alerts";
            default -> "system_alerts";
        };
        return bool(setting.get(field));
    }
    static boolean bool(Object value) { return Boolean.TRUE.equals(value) || value instanceof Number n && n.intValue()!=0; }
    Map<String,Object> decode(String message) { return json.readValue(message,new TypeReference<Map<String,Object>>(){}); }
    static void configureDelivery(Map<String,Object> message,String eventType,boolean sound,LocalDateTime expiresAt,LocalDateTime now) {
        NotificationEventType type="PUSH_TEST".equals(eventType)?NotificationEventType.BARK_TEST:NotificationEventType.valueOf(eventType);
        String urgency=NativePushPolicy.urgency(type);
        boolean audible=sound&&!"passive".equals(urgency);
        var notification=new LinkedHashMap<String,Object>();
        notification.put("channel_id",NativePushPolicy.channel(type,audible));
        notification.put("tag",((Map<?,?>)message.get("data")).get("eventId"));
        if(audible) notification.put("sound","default");
        message.put("android",Map.of("priority","time-sensitive".equals(urgency)?"high":"normal",
                "ttl",Math.max(0,java.time.Duration.between(now,expiresAt).getSeconds())+"s","notification",notification));
        var aps=new LinkedHashMap<String,Object>();
        aps.put("interruption-level",urgency);
        if(audible) aps.put("sound","default");
        message.put("apns",Map.of("headers",Map.of("apns-push-type","alert","apns-priority","10",
                "apns-expiration",Long.toString(expiresAt.toEpochSecond(ZoneOffset.UTC))),"payload",Map.of("aps",aps)));
    }
}
