package com.multind.bitpongo.notification;

import com.multind.bitpongo.common.api.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.multind.bitpongo.notification.NativePushController.*;

@Profile("!test")
@Service
public class NativePushService {
    private final JdbcTemplate jdbc;
    private final NativePushClient client;
    private final NativePushOutbox outbox;
    public NativePushService(JdbcTemplate jdbc, NativePushClient client, NativePushOutbox outbox) {
        this.jdbc = jdbc; this.client = client; this.outbox = outbox;
    }
    @Transactional public SettingView settings(long userId) {
        active(userId);
        ensureSettings(userId);
        return view(userId);
    }
    @Transactional public SettingView save(long userId, Settings s) {
        active(userId);
        if (s.enabled() == null || s.tradeSucceeded() == null || s.tradeFailed() == null || s.planAlerts() == null
                || s.systemAlerts() == null || s.soundEnabled() == null) throw invalid("请提供完整通知设置");
        validateLocaleZone(s.locale(), s.timezone());
        ensureSettings(userId);
        jdbc.update("""
                update user_push_setting set enabled=?, trade_succeeded=?, trade_failed=?, plan_alerts=?,
                system_alerts=?, sound_enabled=?, locale=?, timezone=? where user_id=?
                """, s.enabled(), s.tradeSucceeded(), s.tradeFailed(), s.planAlerts(), s.systemAlerts(),
                s.soundEnabled(), canonicalLocale(s.locale()), s.timezone(), userId);
        // Pending records are checked against the new preference again immediately before dispatch.
        return view(userId);
    }
    @Transactional public List<DeviceView> devices(long userId) {
        active(userId);
        return jdbc.query("select * from push_device where user_id=? order by created_at", (rs, n) ->
                new DeviceView(rs.getString("installation_id"), rs.getString("platform"), rs.getString("app_version"),
                        rs.getBoolean("enabled"), rs.getTimestamp("last_seen_at").toLocalDateTime().toInstant(ZoneOffset.UTC),
                        rs.getTimestamp("created_at").toLocalDateTime().toInstant(ZoneOffset.UTC)), userId);
    }
    @Transactional public boolean register(long userId, String installationId, Registration r) {
        uuid(installationId);
        active(userId);
        validateLocaleZone(r.locale(), r.timezone());
        if (r.token() == null || r.token().isBlank() || r.token().length() > 4096 || r.token().chars().anyMatch(Character::isWhitespace)
                || !Set.of("ios", "android").contains(r.platform() == null ? "" : r.platform())
                || !Set.of("authorized", "provisional", "denied", "notDetermined").contains(r.permission() == null ? "" : r.permission())
                || r.appVersion() == null || r.appVersion().length() > 64) throw invalid("设备推送参数无效");
        ensureSettings(userId);
        jdbc.queryForObject("select id from push_registration_lock where id=1 for update", Integer.class);
        String hash = hash(r.token());
        var previous = jdbc.queryForList("select * from push_device where installation_id=? for update", installationId);
        boolean revoked = !previous.isEmpty() && ((Number)previous.getFirst().get("user_id")).longValue()==userId
                && truth(previous.getFirst().get("revoked")) && !Boolean.TRUE.equals(r.activate());
        if (!revoked) {
            // Upgrade older unsanitized tombstones before a unique-token conflict can hit the upsert path.
            jdbc.update("update push_device set token='',token_hash=sha2(concat('revoked:',installation_id,':',uuid()),256) where token_hash=? and installation_id<>? and revoked=true",
                    hash,installationId);
            // Only a live registration may transfer a token. A revoked sync must not detach another device.
            jdbc.update("delete from push_device where token_hash=? and installation_id<>? and revoked=false", hash, installationId);
        }
        boolean enabled = Set.of("authorized", "provisional").contains(r.permission());
        String version = UUID.randomUUID().toString();
        if (!previous.isEmpty()) {
            var old = previous.getFirst();
            boolean sameOwner = ((Number) old.get("user_id")).longValue() == userId;
            if (sameOwner && hash.equals(old.get("token_hash")) && r.permission().equals(old.get("permission"))
                    && !truth(old.get("enabled")) && !truth(old.get("revoked"))) enabled = false;
            if (sameOwner && hash.equals(old.get("token_hash")) && revoked == truth(old.get("revoked"))
                    && r.permission().equals(old.get("permission"))) version = (String) old.get("binding_version");
            else jdbc.update("delete from push_delivery where installation_id=?", installationId);
        }
        LocalDateTime now = now();
        // A revoked installation keeps its identity, but never retains the submitted live token.
        String storedToken = revoked ? "" : r.token();
        String storedHash = revoked ? tombstoneHash(installationId) : hash;
        jdbc.update("""
                insert into push_device(installation_id,user_id,token,token_hash,binding_version,platform,permission,
                    enabled,app_version,locale,timezone,last_seen_at,created_at,revoked) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                on duplicate key update user_id=values(user_id),token=values(token),token_hash=values(token_hash),
                    binding_version=values(binding_version),platform=values(platform),permission=values(permission),
                    enabled=values(enabled),app_version=values(app_version),locale=values(locale),timezone=values(timezone),last_seen_at=values(last_seen_at),revoked=values(revoked)
                """, installationId,userId,storedToken,storedHash,version,r.platform(),r.permission(),enabled && !revoked,
                r.appVersion(),canonicalLocale(r.locale()),r.timezone(),now,now,revoked);
        return !revoked;
    }
    @Transactional public void remove(long userId, String id) {
        uuid(id); active(userId);
        jdbc.update("update push_device set enabled=false,revoked=true,binding_version=?,token='',token_hash=? where installation_id=? and user_id=?",
                UUID.randomUUID().toString(),tombstoneHash(id),id,userId);
        jdbc.update("delete from push_delivery where installation_id=? and user_id=?",id,userId);
    }
    @Transactional public void deleteForUser(long userId) {
        // Shared account lock is held through the enclosing soft-delete transaction.
        jdbc.queryForList("select id from user where id=? for update", userId);
        jdbc.update("delete from push_delivery where user_id=?", userId);
        jdbc.update("delete from push_device where user_id=?", userId);
        jdbc.update("delete from user_push_setting where user_id=?", userId);
    }
    @Transactional public void logout(long userId, String id, String token) {
        uuid(id); active(userId);
        if (token == null || token.isBlank() || token.length() > 4096 || token.chars().anyMatch(Character::isWhitespace))
            throw invalid("设备推送参数无效");
        // Atomic conditional detach: delayed offline cleanup must never remove a newer token or explicit revoke.
        jdbc.update("delete from push_device where installation_id=? and user_id=? and token_hash=? and revoked=false",
                id,userId,hash(token));
    }
    @Transactional public void test(long userId, String id) {
        uuid(id); active(userId); ensureSettings(userId);
        if (!client.available()) throw new BusinessException(503, "服务器尚未配置原生推送");
        if (!view(userId).enabled()) throw invalid("请先开启通知");
        if (jdbc.queryForObject("select count(*) from push_device where installation_id=? and user_id=? and enabled=true", Integer.class,id,userId) != 1)
            throw invalid("当前设备未注册或未授予通知权限");
        LocalDateTime now = now();
        if (jdbc.update("update user_push_setting set last_test_at=? where user_id=? and (last_test_at is null or last_test_at<=?)",
                now,userId,now.minusMinutes(1)) != 1) throw new BusinessException(429,"请等待一分钟再发送测试通知");
        outbox.enqueueTest(userId,id,now);
    }
    private void ensureSettings(long userId) {
        jdbc.update("insert into user_push_setting(user_id) values(?) on duplicate key update user_id=user_id", userId);
    }
    private SettingView view(long userId) {
        return jdbc.queryForObject("""
                select s.*, (select count(*) from push_device d where d.user_id=s.user_id) device_count
                from user_push_setting s where user_id=?
                """, (rs,n) -> new SettingView(client.available(),rs.getBoolean("enabled"),rs.getBoolean("trade_succeeded"),
                rs.getBoolean("trade_failed"),rs.getBoolean("plan_alerts"),rs.getBoolean("system_alerts"),rs.getBoolean("sound_enabled"),
                rs.getString("locale"),rs.getString("timezone"),rs.getInt("device_count")),userId);
    }
    private void active(long userId) {
        var status = jdbc.queryForList("select status from user where id=? for update", String.class,userId);
        if (status.isEmpty() || !"active".equals(status.getFirst())) throw new BusinessException(401,"账号不可用");
    }
    static void validateLocaleZone(String locale, String timezone) {
        canonicalLocale(locale);
        if (timezone == null || !ZoneId.getAvailableZoneIds().contains(timezone)) throw invalid("timezone 必须是 IANA 时区");
    }
    static String canonicalLocale(String locale) {
        if (locale == null) throw invalid("locale 无效");
        return switch (locale.toLowerCase(java.util.Locale.ROOT)) {
            case "zh-cn" -> "zh-CN";
            case "zh-tw" -> "zh-TW";
            case "en-us" -> "en-US";
            default -> {
                var copy = NotificationLocaleCopy.find(locale);
                if (copy == null) throw invalid("locale 无效");
                yield copy.tag();
            }
        };
    }
    static void uuid(String id) {
        try { if (!UUID.fromString(id).toString().equals(id)) throw invalid("installationId 无效"); }
        catch (RuntimeException e) { throw invalid("installationId 无效"); }
    }
    static BusinessException invalid(String message) { return new BusinessException(400,message); }
    static boolean truth(Object value) { return Boolean.TRUE.equals(value) || value instanceof Number n && n.intValue()!=0; }
    private static String tombstoneHash(String id) { return hash("revoked:"+id+":"+UUID.randomUUID()); }
    static LocalDateTime now() { return LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC); }
    static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
