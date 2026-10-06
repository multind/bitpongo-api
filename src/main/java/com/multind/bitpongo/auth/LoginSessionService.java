package com.multind.bitpongo.auth;

import com.multind.bitpongo.common.api.BusinessException;
import com.multind.bitpongo.common.time.UtcDateTimes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@Service
@Profile("!test")
public class LoginSessionService {
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Session(String id, Instant createdAt, Instant lastSeenAt, Instant expiresAt, boolean current) {}
    public record Listing(List<Session> items) {}
    private final JdbcTemplate jdbc;
    public LoginSessionService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Adopts existing signed tokens on first use; revoked rows are never resurrected. */
    @Transactional
    public void authenticate(long userId, String token, Instant expiresAt) {
        authenticate(userId, token, expiresAt, 0);
    }

    @Transactional
    public void authenticate(long userId, String token, Instant expiresAt, long securityVersion) {
        active(userId);
        if (jdbc.queryForObject("select security_version from user where id=?",Long.class,userId) != securityVersion)
            throw new IllegalArgumentException("登录凭据已失效");
        String hash = hash(token);
        jdbc.update("""
                insert into login_session(id,user_id,token_hash,created_at,last_seen_at,expires_at)
                values(?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),?)
                on duplicate key update token_hash=token_hash
                """, UUID.randomUUID().toString(), userId, hash, UtcDateTimes.toDatabase(expiresAt));
        var revoked = jdbc.queryForObject("select revoked_at is not null from login_session where user_id=? and token_hash=?", Boolean.class, userId, hash);
        if (Boolean.TRUE.equals(revoked)) throw new IllegalArgumentException("登录会话已撤销");
        jdbc.update("update login_session set last_seen_at=UTC_TIMESTAMP(6) where token_hash=? and last_seen_at<UTC_TIMESTAMP(6)-INTERVAL 1 MINUTE", hash);
    }

    @Transactional(readOnly=true)
    public Listing list(long userId, String token) {
        String currentHash=hash(token);
        return new Listing(jdbc.query("""
                select id,token_hash,created_at,last_seen_at,expires_at from login_session
                where user_id=? and revoked_at is null and expires_at>UTC_TIMESTAMP(6)
                order by (token_hash=?) desc,last_seen_at desc,id limit 100
                """, (rs,row)->new Session(rs.getString("id"), UtcDateTimes.toInstant(rs.getTimestamp("created_at").toLocalDateTime()),
                UtcDateTimes.toInstant(rs.getTimestamp("last_seen_at").toLocalDateTime()), UtcDateTimes.toInstant(rs.getTimestamp("expires_at").toLocalDateTime()),
                currentHash.equals(rs.getString("token_hash"))), userId, currentHash));
    }

    @Transactional
    public void revoke(long userId, String id) {
        if (id == null || !id.matches("[a-fA-F0-9-]{36}")) throw new BusinessException(400,"会话标识无效");
        active(userId);
        if (jdbc.update("update login_session set revoked_at=coalesce(revoked_at,UTC_TIMESTAMP(6)) where user_id=? and id=?", userId, id)==0)
            throw new BusinessException(404,"登录会话不存在");
    }

    @Transactional
    public void deleteForUser(long userId) {
        jdbc.queryForList("select id from user where id=? for update",userId);
        jdbc.update("delete from login_session where user_id=?",userId);
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString="${LOGIN_SESSION_CLEANUP_DELAY:600000}",initialDelayString="${LOGIN_SESSION_CLEANUP_INITIAL_DELAY:600000}")
    @Transactional
    public void cleanupExpired() {
        // Use the same application clock authority as JWT expiry, with a one-day retention buffer.
        jdbc.update("delete from login_session where expires_at<? limit 1000",
                UtcDateTimes.toDatabase(Instant.now().minusSeconds(86400)));
    }

    private void active(long userId) {
        if (jdbc.queryForList("select id from user where id=? and status='active' for update",userId).isEmpty())
            throw new IllegalArgumentException("账号不可用");
    }
    private static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
