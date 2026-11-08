package com.multind.bitpongo.notification;

import com.multind.bitpongo.common.api.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

@Service @Profile("!test")
public class TelegramReminderService {
    private final JdbcTemplate jdbc;
    private final TelegramClient client;
    private final SecureRandom random = new SecureRandom();

    TelegramReminderService(JdbcTemplate jdbc, TelegramClient client) {
        this.jdbc = jdbc;
        this.client = client;
    }

    public record Status(boolean available, boolean bound) {}
    public record Delivery(long userId, String text) {}

    public Status status(long user) { return new Status(client.available(), chat(user, false) != null); }

    @Transactional
    public String start(long user) {
        if (!client.available()) throw new BusinessException(503, "Telegram 服务未配置");
        if (jdbc.queryForList("select id from user where id=? and status='active' for update", Long.class, user).isEmpty())
            throw new BusinessException(401, "请先登录");
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("""
            insert into telegram_binding_challenge(user_id,code_hash,expires_at)
            values(?,?,UTC_TIMESTAMP(6)+interval 10 minute)
            on duplicate key update code_hash=?,expires_at=UTC_TIMESTAMP(6)+interval 10 minute
            """, user, hash(code), hash(code));
        return client.link(code);
    }

    @Transactional
    public void bind(String code, long chat) {
        String hash = hash(code);
        var owners = jdbc.queryForList("select user_id from telegram_binding_challenge where code_hash=? and expires_at>UTC_TIMESTAMP(6)", Long.class, hash);
        if (owners.isEmpty()) return;
        long owner = owners.getFirst();
        if (jdbc.queryForList("select id from user where id=? and status='active' for update", Long.class, owner).isEmpty()) return;
        if (jdbc.update("delete from telegram_binding_challenge where user_id=? and code_hash=? and expires_at>UTC_TIMESTAMP(6)", owner, hash) != 1) return;
        if (!jdbc.queryForList("select user_id from user_notification_channel where telegram_chat_id=? and user_id<>?", Long.class, chat, owner).isEmpty()) return;
        jdbc.update("insert ignore into user_notification_channel(user_id,updated_at) values(?,UTC_TIMESTAMP(6))", owner);
        jdbc.update("update user_notification_channel set telegram_chat_id=? where user_id=?", chat, owner);
    }

    @Transactional
    public void unlink(long user) {
        jdbc.queryForList("select id from user where id=? for update", Long.class, user);
        jdbc.update("delete from telegram_binding_challenge where user_id=?", user);
        jdbc.update("update user_notification_channel set telegram_chat_id=null,telegram_enabled=false where user_id=?", user);
    }

    public void test(long user) {
        Long chat = chat(user, false);
        if (chat == null) throw new BusinessException(400, "请先绑定 Telegram");
        if (!client.send(chat, "Bitpongo · Telegram test notification")) throw new BusinessException(503, "Telegram 消息发送失败");
    }

    @TransactionalEventListener
    public void deliver(Delivery delivery) {
        if (!client.available()) return;
        try {
            Long chat = chat(delivery.userId(), true);
            if (chat != null && !client.send(chat, delivery.text())) logFailure();
        } catch (RuntimeException e) { logFailure(); }
    }

    private Long chat(long user, boolean enabled) {
        return jdbc.queryForList("""
            select c.telegram_chat_id from user_notification_channel c
            join user u on u.id=c.user_id and u.status='active'
            where c.user_id=? and c.telegram_chat_id is not null
            """ + (enabled ? " and c.telegram_enabled=true" : ""), Long.class, user).stream().findFirst().orElse(null);
    }

    boolean sendPerception(long userId, String text) {
        Long chat = chat(userId, true);
        return chat == null || (client.available() && client.send(chat, text));
    }

    private static void logFailure() {
        org.slf4j.LoggerFactory.getLogger(TelegramReminderService.class).warn("Telegram reminder delivery failed");
    }

    private static String hash(String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
