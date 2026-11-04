package com.multind.bitpongo.notification;

import com.multind.bitpongo.auth.EmailCodeSender;
import com.multind.bitpongo.common.api.BusinessException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@Service
@Profile("!test")
public class NotificationChannelService {

    public record Channel(boolean enabled, boolean available, String level, int sequence) {}

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Channels(
            Channel bitpongo,
            Channel email,
            Channel message,
            Channel call,
            Channel dingding,
            Channel feishu,
            Channel telegram,
            boolean confirmationEnabled) {}

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Update(
            Boolean emailEnabled,
            Boolean messageEnabled,
            Boolean callEnabled,
            Boolean dingdingEnabled,
            Boolean feishuEnabled,
            Boolean telegramEnabled,
            Boolean confirmationEnabled) {
        public Update {
            if (emailEnabled == null || messageEnabled == null || callEnabled == null
                    || dingdingEnabled == null || feishuEnabled == null
                    || telegramEnabled == null || confirmationEnabled == null) {
                throw new BusinessException(400, "请提供完整通知设置");
            }
        }
    }

    private static final String WEAK = "Weak";
    private static final String MODERATE_WEAK = "ModerateWeak";
    private static final String MODERATE = "Moderate";
    private static final String MODERATE_STRONG = "ModerateStrong";
    private static final String STRONG = "Strong";

    private final JdbcTemplate jdbc;
    private final NativePushClient push;
    private final EmailCodeSender email;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    private TelegramClient telegramClient;
    private static final List<String> DEFAULT_ORDER = List.of("call", "message", "dingding", "feishu", "telegram", "bitpongo", "email");

    @Transactional
    public Channels reorder(long userId, List<String> order) {
        activeUser(userId);
        if (order == null || order.size() != DEFAULT_ORDER.size() || !new java.util.HashSet<>(order).equals(new java.util.HashSet<>(DEFAULT_ORDER)))
            throw new BusinessException(400, "通知顺序无效");
        jdbc.queryForObject("select id from user where id=? for update", Long.class, userId);
        jdbc.update("delete from user_notification_channel_order where user_id=?", userId);
        for (int i = 0; i < order.size(); i++) jdbc.update("insert into user_notification_channel_order(user_id,channel_code,position) values(?,?,?)", userId, order.get(i), i);
        return get(userId);
    }

    private int position(long userId, String code) {
        return jdbc.queryForList("select position from user_notification_channel_order where user_id=? and channel_code=?", Integer.class, userId, code)
                .stream().findFirst().orElse(DEFAULT_ORDER.indexOf(code));
    }

    public NotificationChannelService(
            JdbcTemplate jdbc,
            NativePushClient push,
            ObjectProvider<EmailCodeSender> email,
            ObjectProvider<Clock> clock) {
        this.jdbc = jdbc;
        this.push = push;
        this.email = email.getIfAvailable();
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    @Transactional(readOnly = true)
    public Channels get(long userId) {
        activeUser(userId);
        Map<String, Object> row = channelRow(userId);
        boolean bitpongoEnabled = Boolean.TRUE.equals(jdbc.queryForList(
                "select enabled from user_push_setting where user_id=?", Boolean.class, userId).stream().findFirst().orElse(true));
        return new Channels(
                new Channel(bitpongoEnabled, push.available(), MODERATE_WEAK, position(userId,"bitpongo")),
                new Channel(bool(row, "email_enabled"), email != null && email.available(), WEAK, position(userId,"email")),
                new Channel(bool(row, "message_enabled"), false, MODERATE_STRONG, position(userId,"message")),
                new Channel(bool(row, "call_enabled"), false, STRONG, position(userId,"call")),
                new Channel(bool(row, "dingding_enabled"), false, MODERATE, position(userId,"dingding")),
                new Channel(bool(row, "feishu_enabled"), false, MODERATE, position(userId,"feishu")),
                new Channel(bool(row, "telegram_enabled"), telegramClient.available() && row.get("telegram_chat_id") != null, MODERATE, position(userId,"telegram")),
                bool(row, "confirmation_enabled"));
    }

    @Transactional
    public Channels update(long userId, Update request) {
        activeUser(userId);
        if (request == null) throw new BusinessException(400, "请提供完整通知设置");
        boolean email = enabled(request.emailEnabled());
        boolean message = enabled(request.messageEnabled());
        boolean call = enabled(request.callEnabled());
        boolean dingding = enabled(request.dingdingEnabled());
        boolean feishu = enabled(request.feishuEnabled());
        boolean telegram = enabled(request.telegramEnabled());
        boolean confirmation = enabled(request.confirmationEnabled());
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        jdbc.update("""
                insert into user_notification_channel(user_id,email_enabled,message_enabled,call_enabled,dingding_enabled,
                    feishu_enabled,telegram_enabled,confirmation_enabled,updated_at)
                values(?,?,?,?,?,?,?,?,?)
                on duplicate key update email_enabled=?,message_enabled=?,call_enabled=?,dingding_enabled=?,
                    feishu_enabled=?,telegram_enabled=?,confirmation_enabled=?,updated_at=?
                """, userId, email, message, call, dingding, feishu, telegram, confirmation, now,
                email, message, call, dingding, feishu, telegram, confirmation, now);
        return get(userId);
    }

    private void activeUser(long userId) {
        List<String> rows = jdbc.queryForList("select id from user where id=? and status='active'", String.class, userId);
        if (rows.isEmpty()) throw new BusinessException(401, "无法验证凭据");
    }

    private Map<String, Object> channelRow(long userId) {
        return jdbc.queryForList("select * from user_notification_channel where user_id=?", userId)
                .stream().findFirst().orElse(Map.of());
    }

    private static boolean bool(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Boolean b ? b : value instanceof Number n && n.intValue() != 0;
    }

    private static boolean enabled(Boolean value) {
        return Boolean.TRUE.equals(value);
    }
}
