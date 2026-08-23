package com.multind.bitpongo.notification;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Profile("!test")
@Service
class NotificationOutboxDeliveryStore {

    private static final String DEFAULT_LOCALE = "zh-CN";
    private static final String DEFAULT_TIMEZONE = "Asia/Shanghai";

    private final NotificationOutboxRepository outbox;
    private final UserBarkSettingRepository settings;
    private final JdbcTemplate jdbc;

    NotificationOutboxDeliveryStore(
            NotificationOutboxRepository outbox,
            UserBarkSettingRepository settings,
            JdbcTemplate jdbc) {
        this.outbox = outbox;
        this.settings = settings;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<LeasedNotification> load(long id) {
        return outbox.findById(id)
                .filter(message -> message.getStatus() == NotificationOutboxStatus.SENDING)
                .map(this::snapshot);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<Integer> currentAttempts(long id) {
        return outbox.findById(id).map(NotificationOutboxEntity::getAttempts);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSent(long id, LocalDateTime now) {
        jdbc.update("""
                update notification_outbox
                   set status = 'SENT', sent_at = ?, lease_until = null,
                       last_error = null, updated_at = ?
                 where id = ? and status = 'SENDING'
                """, now, now, id);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSkipped(long id, LocalDateTime now) {
        jdbc.update("""
                update notification_outbox
                   set status = 'SKIPPED', lease_until = null, updated_at = ?
                 where id = ? and status = 'SENDING'
                """, now, id);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRetry(
            long id,
            int attempts,
            LocalDateTime nextAttemptAt,
            String lastError,
            LocalDateTime now) {
        jdbc.update("""
                update notification_outbox
                   set status = 'PENDING', attempts = ?, next_attempt_at = ?,
                       lease_until = null, last_error = ?, updated_at = ?
                 where id = ? and status = 'SENDING'
                """, attempts, nextAttemptAt, lastError, now, id);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDead(
            long id,
            int attempts,
            String lastError,
            LocalDateTime now) {
        jdbc.update("""
                update notification_outbox
                   set status = 'DEAD', attempts = ?, lease_until = null,
                       last_error = ?, updated_at = ?
                 where id = ? and status = 'SENDING'
                """, attempts, lastError, now, id);
    }

    private LeasedNotification snapshot(NotificationOutboxEntity message) {
        NotificationEvent event = event(message);
        if (message.getRecipientType() == NotificationRecipientType.ADMIN) {
            return new LeasedNotification(
                    message.getId(), message.getAttempts(), message.getRecipientType(), event,
                    null, null, DEFAULT_LOCALE, DEFAULT_TIMEZONE);
        }
        UserBarkSettingEntity setting = settings.findByUserId(message.getUserId())
                .filter(UserBarkSettingEntity::isEnabled)
                .orElse(null);
        return new LeasedNotification(
                message.getId(), message.getAttempts(), message.getRecipientType(), event,
                setting == null ? null : setting.getServerUrl(),
                setting == null ? null : setting.getDeviceKeyCiphertext(),
                setting == null ? DEFAULT_LOCALE : setting.getLocale(),
                setting == null ? DEFAULT_TIMEZONE : setting.getTimezone());
    }

    private static NotificationEvent event(NotificationOutboxEntity message) {
        Map<String, Object> payload = message.getBodyPayload() == null
                ? Map.of()
                : message.getBodyPayload();
        return new NotificationEvent(
                message.getEventType(),
                longValue(payload.get("userId"), message.getUserId()),
                longValue(payload.get("planId"), null),
                longValue(payload.get("intentId"), null),
                instant(payload.get("occurredAt")),
                message.getDedupeKey(),
                attributes(payload.get("attributes")));
    }

    private static Long longValue(Object value, Long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private static Instant instant(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(String.valueOf(value));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Map<String, Object> attributes(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, attribute) -> {
            if (key != null) {
                copy.put(String.valueOf(key), attribute);
            }
        });
        return java.util.Collections.unmodifiableMap(copy);
    }

    record LeasedNotification(
            long id,
            int attempts,
            NotificationRecipientType recipientType,
            NotificationEvent event,
            String serverUrl,
            String deviceKeyCiphertext,
            String locale,
            String timezone) {

        boolean hasStoredUserTarget() {
            return serverUrl != null && deviceKeyCiphertext != null;
        }
    }
}
