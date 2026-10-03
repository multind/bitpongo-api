package com.multind.bitpongo.alerts;

import com.multind.bitpongo.common.api.BusinessException;
import com.multind.bitpongo.notification.AlertNotificationBridge;
import com.multind.bitpongo.notification.BarkCredentialCipher;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service @Profile("!test")
public class WebhookService {
    public record Draft(String name,String description,String keywords,String allowedIps) {}
    public record Rule(long id,String name,String description,String keywords,String allowedIps,String status,String publicId) {}
    public record Activation(String publicId,String signingKey) {}
    private final JdbcTemplate jdbc;
    private final AlertNotificationBridge notifications;
    private final String encryptionKey;
    private final JsonMapper json=JsonMapper.builder().build();
    private final SecureRandom random=new SecureRandom();
    public WebhookService(JdbcTemplate jdbc,AlertNotificationBridge notifications,@Value("${WEBHOOK_CREDENTIAL_ENCRYPTION_KEY:}") String encryptionKey) {
        this.jdbc=jdbc;this.notifications=notifications;this.encryptionKey=encryptionKey;
    }
    public boolean available() { try { cipher();return true; } catch (BusinessException e) { return false; } }
    private BarkCredentialCipher cipher() {
        try { return new BarkCredentialCipher(encryptionKey); }
        catch (IllegalStateException e) { throw new BusinessException(503,"Webhook 加密密钥尚未配置"); }
    }
    @Transactional public long create(long userId,Draft draft) {
        validate(draft);
        String publicId=UUID.randomUUID().toString();Instant now=Instant.now();
        jdbc.update("insert into webhook_rule(user_id,name,description,keywords,allowed_ips,public_id,created_at,updated_at) values(?,?,?,?,?,?,?,?)",
                userId,draft.name().trim(),draft.description(),draft.keywords().trim(),draft.allowedIps().trim(),publicId,PriceAlertService.utc(now),PriceAlertService.utc(now));
        return jdbc.queryForObject("select id from webhook_rule where public_id=?",Long.class,publicId);
    }
    public List<Rule> list(long userId) {
        return jdbc.query("select * from webhook_rule where user_id=? order by id desc limit 100",(rs,i)->new Rule(rs.getLong("id"),rs.getString("name"),rs.getString("description"),rs.getString("keywords"),rs.getString("allowed_ips"),rs.getString("status"),rs.getString("public_id")),userId);
    }
    @Transactional public void update(long userId,long id,Draft draft) {
        validate(draft);owned(userId,id);
        jdbc.update("update webhook_rule set name=?,description=?,keywords=?,allowed_ips=?,updated_at=? where id=?",
                draft.name().trim(),draft.description(),draft.keywords().trim(),draft.allowedIps().trim(),PriceAlertService.utc(Instant.now()),id);
    }
    @Transactional public Activation activate(long userId,long id) {
        var cipher=cipher();var rule=owned(userId,id);
        byte[] bytes=new byte[32];random.nextBytes(bytes);
        String secret=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("update webhook_rule set status='ACTIVE',secret_cipher=?,updated_at=? where id=?",cipher.encrypt(secret),PriceAlertService.utc(Instant.now()),id);
        return new Activation((String)rule.get("public_id"),secret);
    }
    @Transactional public void revoke(long userId,long id) {
        owned(userId,id);
        jdbc.update("update webhook_rule set status='REVOKED',secret_cipher=null,updated_at=? where id=?",PriceAlertService.utc(Instant.now()),id);
    }
    private Map<String,Object> owned(long userId,long id) {
        return jdbc.queryForList("select * from webhook_rule where user_id=? and id=? for update",userId,id).stream().findFirst().orElseThrow(()->new BusinessException(404,"Webhook 不存在"));
    }
    @Transactional public void receive(String publicId,String timestamp,String nonce,String signature,byte[] body,String remoteIp) {
        if (body==null || body.length>4096) throw PriceAlertService.invalid("Webhook 内容不能超过 4096 bytes");
        if (publicId==null || !publicId.matches("[a-f0-9-]{36}")) throw new BusinessException(404,"Webhook 不可用");
        var owners=jdbc.queryForList("select user_id from webhook_rule where public_id=?",Long.class,publicId);
        if (owners.isEmpty()) throw new BusinessException(404,"Webhook 不可用");
        long userId=owners.getFirst();
        if (jdbc.queryForList("select id from user where id=? and status='active' for update",userId).isEmpty()) throw new BusinessException(404,"Webhook 不可用");
        var rows=jdbc.queryForList("select * from webhook_rule where public_id=? and status='ACTIVE' for update",publicId);
        if (rows.isEmpty()) throw new BusinessException(404,"Webhook 不可用");
        var rule=rows.getFirst();Instant now=Instant.now();long id=((Number)rule.get("id")).longValue();
        String ips=(String)rule.get("allowed_ips");
        if (!ips.isBlank() && java.util.Arrays.stream(ips.split("\\s+")).noneMatch(remoteIp::equals)) throw new BusinessException(403,"Webhook IP 不允许");
        WebhookSignature.verify(cipher().decrypt((String)rule.get("secret_cipher")),timestamp,nonce,signature,body,now);
        String eventKey="webhook:"+publicId+":"+nonce;
        if (jdbc.queryForObject("select count(*) from alert_event where event_key=?",Integer.class,eventKey)>0) throw new BusinessException(409,"Webhook 重复请求");
        if (jdbc.queryForObject("select count(*) from webhook_receipt where webhook_id=? and nonce=?",Integer.class,id,nonce)>0) throw new BusinessException(409,"Webhook 重复请求");
        if (jdbc.queryForObject("select count(*) from webhook_receipt where webhook_id=? and received_at>?",Integer.class,id,PriceAlertService.utc(now.minusSeconds(60)))>=60) throw new BusinessException(429,"Webhook 请求过于频繁");
        String title,message;
        try {
            var content=json.readTree(body);
            if (!content.isObject() || !content.path("message").isTextual() || (content.has("title") && !content.path("title").isTextual())) throw PriceAlertService.invalid("Webhook 内容无效");
            title=content.has("title")?content.get("title").asText():(String)rule.get("name");message=content.get("message").asText();
        } catch (BusinessException e) { throw e; }
        catch(Exception e) { throw PriceAlertService.invalid("Webhook JSON 无效"); }
        if (title.isBlank() || title.length()>100 || message.isBlank() || message.length()>900) throw PriceAlertService.invalid("Webhook 标题或消息长度无效");
        jdbc.update("insert into webhook_receipt(webhook_id,nonce,received_at) values(?,?,?)",id,nonce,PriceAlertService.utc(now));
        jdbc.update("delete from webhook_receipt where webhook_id=? and received_at<?",id,PriceAlertService.utc(now.minusSeconds(600)));
        String keywords=(String)rule.get("keywords");
        if (!keywords.isBlank() && java.util.Arrays.stream(keywords.split("\\s+")).noneMatch((title+" "+message)::contains)) return;
        jdbc.update("insert into alert_event(user_id,event_type,event_key,title,body,occurred_at) values(?,?,?,?,?,?)",userId,"WEBHOOK_TRIGGERED",eventKey,title,message,PriceAlertService.utc(now));
        notifications.webhook(userId,eventKey,title+" · "+message,now);
    }
    static void validate(Draft d) {
        if (d==null || d.name()==null || d.name().isBlank() || d.name().length()>100 || d.description()==null || d.description().length()>500 || d.keywords()==null || d.keywords().length()>500 || d.allowedIps()==null || d.allowedIps().length()>1000) throw PriceAlertService.invalid("Webhook 参数无效");
        for (String ip:d.allowedIps().trim().split("\\s+")) {
            if (ip.isEmpty()) continue;
            if (!ip.matches("[0-9.]{7,15}|[a-fA-F0-9:]{2,45}")) throw PriceAlertService.invalid("Webhook IP 无效（仅支持单个 IP）");
            try { if (!java.net.InetAddress.getByName(ip).getHostAddress().equals(ip)) throw PriceAlertService.invalid("Webhook IP 必须使用标准格式"); }
            catch(java.net.UnknownHostException e) { throw PriceAlertService.invalid("Webhook IP 无效"); }
        }
    }
}
