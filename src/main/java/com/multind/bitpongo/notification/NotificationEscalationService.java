package com.multind.bitpongo.notification;

import com.multind.bitpongo.auth.EmailCodeSender;
import com.multind.bitpongo.common.api.BusinessException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends a follow-up email when an in-app alert is not explicitly confirmed within five minutes.
 *
 * <p>The initial app notification is sent by the existing Bark/native-push paths. This service only
 * owns the confirmation deadline and the follow-up channel, so it never duplicates the initial
 * delivery. Other channels remain unavailable until a provider is implemented.</p>
 */
@Service
@Profile("!test")
public class NotificationEscalationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationEscalationService.class);
    private static final Duration CONFIRMATION_DELAY = Duration.ofMinutes(5);
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final int MAX_ATTEMPTS = 3;
    private static final int BATCH_SIZE = 20;
    private static final String EMAIL = "email";

    private final JdbcTemplate jdbc;
    private final EmailCodeSender email;
    private final BarkProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactions;

    @Autowired
    public NotificationEscalationService(
            JdbcTemplate jdbc,
            EmailCodeSender email,
            BarkProperties properties,
            ObjectProvider<Clock> clock,
            PlatformTransactionManager transactionManager) {
        this(jdbc, email, properties, clock.getIfAvailable(Clock::systemUTC), transactionManager);
    }

    NotificationEscalationService(
            JdbcTemplate jdbc,
            EmailCodeSender email,
            BarkProperties properties,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.email = Objects.requireNonNull(email, "email");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** Schedules a confirmation deadline when the account has confirmation enabled. */
    @Transactional
    public void schedule(long userId, String eventKey, Instant occurredAt) {
        if (userId <= 0 || eventKey == null || eventKey.isBlank()) {
            return;
        }
        Boolean enabled = jdbc.query(
                "select confirmation_enabled from user_notification_channel where user_id=?",
                result -> result.next() && result.getBoolean(1),
                userId);
        if (!Boolean.TRUE.equals(enabled)) {
            return;
        }
        Instant base = occurredAt == null ? clock.instant() : occurredAt;
        LocalDateTime dueAt = LocalDateTime.ofInstant(base.plus(CONFIRMATION_DELAY), ZoneOffset.UTC);
        LocalDateTime now = now();
        jdbc.update("""
                insert into notification_escalation(event_key,user_id,due_at,status,attempts,created_at,updated_at)
                values(?,?,?, 'PENDING', 0, ?, ?)
                on duplicate key update event_key=event_key
                """, eventKey, userId, dueAt, now, now);
    }

    /** Marks an alert confirmed and cancels any pending follow-up delivery. */
    @Transactional
    public void confirm(long userId, long eventId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select event_key from alert_event where id=? and user_id=?", eventId, userId);
        if (rows.isEmpty()) {
            throw new BusinessException(404, "提醒不存在");
        }
        String eventKey = String.valueOf(rows.getFirst().get("event_key"));
        LocalDateTime now = now();
        jdbc.update("""
                update alert_event
                   set read_at=coalesce(read_at,?), confirmed_at=coalesce(confirmed_at,?)
                 where id=? and user_id=?
                """, now, now, eventId, userId);
        jdbc.update("""
                update notification_escalation
                   set status='CONFIRMED', lease_token=null, lease_until=null, updated_at=?
                 where event_key=? and user_id=? and status<>'CONFIRMED'
                """, now, eventKey, userId);
    }

    public void escalateDue() {
        List<Due> claimed = transactions.execute(status -> claim(now()));
        if (claimed == null) {
            return;
        }
        for (Due due : claimed) {
            try {
                deliver(due);
            } catch (RuntimeException exception) {
                markFailure(due, exception);
            }
        }
        transactions.executeWithoutResult(status -> cleanup(now()));
    }

    private List<Due> claim(LocalDateTime now) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select event_key,user_id,attempts from notification_escalation
                 where (status='PENDING' and due_at<=?)
                    or (status='SENDING' and lease_until<=?)
                 order by due_at,event_key
                 limit ? for update skip locked
                """, now, now, BATCH_SIZE);
        if (rows.isEmpty()) {
            return List.of();
        }
        String token = UUID.randomUUID().toString();
        LocalDateTime leaseUntil = now.plus(LEASE);
        List<Due> claimed = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            String eventKey = String.valueOf(row.get("event_key"));
            int updated = jdbc.update("""
                    update notification_escalation
                       set status='SENDING', lease_token=?, lease_until=?, updated_at=?
                     where event_key=? and user_id=?
                       and ((status='PENDING' and due_at<=?)
                         or (status='SENDING' and lease_until<=?))
                    """, token, leaseUntil, now, eventKey,
                    ((Number) row.get("user_id")).longValue(), now, now);
            if (updated == 1) {
                claimed.add(new Due(
                        eventKey,
                        ((Number) row.get("user_id")).longValue(),
                        ((Number) row.get("attempts")).intValue(),
                        token));
            }
        }
        return claimed;
    }

    private void deliver(Due due) {
        Delivery delivery = transactions.execute(status -> load(due));
        if (delivery == null) {
            mark(due, "SKIPPED", null, null, null, null);
            return;
        }
        if (delivery.confirmedAt() != null
                || delivery.inboxDeleted()
                || !delivery.confirmationEnabled()) {
            mark(due, "SKIPPED", null, null, null, null);
            return;
        }
        if (!delivery.emailEnabled()
                || delivery.email() == null
                || delivery.email().isBlank()
                || !email.available()) {
            mark(due, "SKIPPED", null, null, null, null);
            return;
        }
        try {
            email.sendNotification(delivery.email(), delivery.title(), body(delivery));
            mark(due, "ESCALATED", EMAIL, due.attempts(), null, null);
        } catch (RuntimeException exception) {
            int attempts = due.attempts() + 1;
            String error = sanitized(exception);
            if (attempts >= MAX_ATTEMPTS) {
                mark(due, "FAILED", EMAIL, attempts, error, null);
            } else {
                mark(due, "PENDING", EMAIL, attempts, error, now().plus(CONFIRMATION_DELAY));
            }
        }
    }

    private Delivery load(Due due) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select e.title,e.body,e.confirmed_at,e.inbox_deleted,
                       case when nullif(trim(u.email),'') is not null then coalesce(c.reminder_email,u.email) end as email,
                       coalesce(c.confirmation_enabled,false) confirmation_enabled,
                       coalesce(c.email_enabled,false) email_enabled
                  from notification_escalation n
                  join alert_event e on e.event_key=n.event_key
                  join user u on u.id=n.user_id and u.status='active'
                  left join user_notification_channel c on c.user_id=n.user_id
                 where n.event_key=? and n.user_id=?
                   and n.status='SENDING' and n.lease_token=?
                """, due.eventKey(), due.userId(), due.token());
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.getFirst();
        return new Delivery(
                String.valueOf(row.get("title")),
                row.get("body") == null ? "" : String.valueOf(row.get("body")),
                instant(row.get("confirmed_at")),
                truth(row.get("inbox_deleted")),
                row.get("email") == null ? null : String.valueOf(row.get("email")),
                truth(row.get("confirmation_enabled")),
                truth(row.get("email_enabled")));
    }

    private void mark(
            Due due,
            String status,
            String channel,
            Integer attempts,
            String error,
            LocalDateTime dueAt) {
        LocalDateTime now = now();
        transactions.executeWithoutResult(ignored -> jdbc.update("""
                update notification_escalation
                   set status=?, channel=?, attempts=coalesce(?,attempts), last_error=?,
                       due_at=coalesce(?,due_at),
                       escalated_at=case when ?='ESCALATED' then ? else escalated_at end,
                       lease_token=null, lease_until=null, updated_at=?
                 where event_key=? and user_id=? and status='SENDING' and lease_token=?
                """, status, channel, attempts, error, dueAt, status, now, now,
                due.eventKey(), due.userId(), due.token()));
    }

    private void markFailure(Due due, RuntimeException exception) {
        int attempts = due.attempts() + 1;
        String error = sanitized(exception);
        if (attempts >= MAX_ATTEMPTS) {
            mark(due, "FAILED", null, attempts, error, null);
        } else {
            mark(due, "PENDING", null, attempts, error, now().plus(CONFIRMATION_DELAY));
        }
    }

    private void cleanup(LocalDateTime now) {
        jdbc.update("""
                delete from notification_escalation
                 where status in ('CONFIRMED','SKIPPED','ESCALATED','FAILED')
                   and updated_at<?
                 limit 1000
                """, now.minusDays(30));
    }

    private String body(Delivery delivery) {
        String url = properties.appPublicUrl();
        if (url == null || url.isBlank()) {
            return delivery.body();
        }
        return delivery.body() + "\n\n" + stripTrailingSlash(url) + "/alerts/events";
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static boolean truth(Object value) {
        return Boolean.TRUE.equals(value) || value instanceof Number number && number.intValue() != 0;
    }

    private static String sanitized(RuntimeException exception) {
        String message = exception.getMessage();
        String sanitized = NotificationMessageRenderer.sanitizeError(
                message == null || message.isBlank() ? "EMAIL_DELIVERY_FAILED" : message);
        return sanitized.isBlank() ? "EMAIL_DELIVERY_FAILED" : sanitized;
    }

    private static String stripTrailingSlash(String value) {
        String stripped = value;
        while (stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }

    private static Instant instant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.toInstant(ZoneOffset.UTC);
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime().toInstant(ZoneOffset.UTC);
        }
        return null;
    }

    private record Due(String eventKey, long userId, int attempts, String token) {}

    private record Delivery(
            String title,
            String body,
            Instant confirmedAt,
            boolean inboxDeleted,
            String email,
            boolean confirmationEnabled,
            boolean emailEnabled) {}
}
