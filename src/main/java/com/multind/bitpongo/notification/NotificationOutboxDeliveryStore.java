package com.multind.bitpongo.notification;

import com.multind.bitpongo.auth.UserTimeZoneService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
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
    private final UserTimeZoneService timeZones;
    private final String adminTimezone;

    NotificationOutboxDeliveryStore(
            NotificationOutboxRepository outbox,
            UserBarkSettingRepository settings,
            JdbcTemplate jdbc,
            UserTimeZoneService timeZones,
            @Value("${zhitoubao.scheduling-zone:Asia/Shanghai}") String adminTimezone) {
        this.outbox = outbox;
        this.settings = settings;
        this.jdbc = jdbc;
        this.timeZones = timeZones;
        this.adminTimezone = adminTimezone;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<LeasedNotification> load(NotificationOutboxLeaseService.Lease lease) {
        if (!owned(lease)) {
            return Optional.empty();
        }
        return outbox.findById(lease.id())
                .filter(message -> message.getStatus() == NotificationOutboxStatus.SENDING)
                .map(this::snapshot);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<Integer> currentAttempts(NotificationOutboxLeaseService.Lease lease) {
        return jdbc.query("""
                        select attempts from notification_outbox
                         where id = ? and lease_token = ? and status = 'SENDING'
                        """,
                result -> result.next()
                        ? Optional.of(result.getInt("attempts"))
                        : Optional.empty(),
                lease.id(), lease.token());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markSent(
            NotificationOutboxLeaseService.Lease lease,
            LocalDateTime now) {
        return jdbc.update("""
                update notification_outbox
                   set status = 'SENT', sent_at = ?, lease_until = null,
                       lease_token = null, last_error = null, updated_at = ?
                 where id = ? and lease_token = ? and status = 'SENDING'
                """, now, now, lease.id(), lease.token()) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markSkipped(
            NotificationOutboxLeaseService.Lease lease,
            LocalDateTime now) {
        return jdbc.update("""
                update notification_outbox
                   set status = 'SKIPPED', lease_until = null,
                       lease_token = null, updated_at = ?
                 where id = ? and lease_token = ? and status = 'SENDING'
                """, now, lease.id(), lease.token()) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markRetry(
            NotificationOutboxLeaseService.Lease lease,
            int attempts,
            LocalDateTime nextAttemptAt,
            String lastError,
            LocalDateTime now) {
        return jdbc.update("""
                update notification_outbox
                   set status = 'PENDING', attempts = ?, next_attempt_at = ?,
                       lease_until = null, lease_token = null,
                       last_error = ?, updated_at = ?
                 where id = ? and lease_token = ? and status = 'SENDING'
                """, attempts, nextAttemptAt, lastError, now,
                lease.id(), lease.token()) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markDead(
            NotificationOutboxLeaseService.Lease lease,
            int attempts,
            String lastError,
            LocalDateTime now) {
        return jdbc.update("""
                update notification_outbox
                   set status = 'DEAD', attempts = ?, lease_until = null,
                       lease_token = null, last_error = ?, updated_at = ?
                 where id = ? and lease_token = ? and status = 'SENDING'
                """, attempts, lastError, now,
                lease.id(), lease.token()) == 1;
    }

    private boolean owned(NotificationOutboxLeaseService.Lease lease) {
        Integer count = jdbc.queryForObject("""
                select count(*) from notification_outbox
                 where id = ? and lease_token = ? and status = 'SENDING'
                """, Integer.class, lease.id(), lease.token());
        return count != null && count == 1;
    }

    private LeasedNotification snapshot(NotificationOutboxEntity message) {
        NotificationEvent event = event(message);
        if (message.getRecipientType() == NotificationRecipientType.ADMIN) {
            return new LeasedNotification(
                    message.getId(), message.getAttempts(), message.getRecipientType(), event,
                    null, null, DEFAULT_LOCALE, adminTimezone);
        }
        UserBarkSettingEntity setting = settings.findByUserId(message.getUserId())
                .filter(UserBarkSettingEntity::isEnabled)
                .orElse(null);
        return new LeasedNotification(
                message.getId(), message.getAttempts(), message.getRecipientType(), event,
                setting == null ? null : setting.getServerUrl(),
                setting == null ? null : setting.getDeviceKeyCiphertext(),
                setting == null ? DEFAULT_LOCALE : setting.getLocale(),
                timeZones.resolveDisplayZone(message.getUserId()).getId());
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
                instant(payload.get("scheduledAt")),
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
