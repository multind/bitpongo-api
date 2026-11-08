package com.multind.bitpongo.notification;

import com.multind.bitpongo.auth.EmailCodeSender;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.multind.bitpongo.notification.NotificationPerceptionPlan.Strength;

@Service
@Profile("!test")
class NotificationPerceptionDelivery {
    private final JdbcTemplate jdbc;
    private final NotificationPerceptionQueue queue;
    private final NotificationChannelService settings;
    private final WebhookReminderService webhookSettings;
    private final NotificationOutboxEnqueuer app;
    private final WebhookReminderOutbox webhook;
    private final TelegramReminderService telegram;
    private final EmailCodeSender email;
    private final TransactionTemplate tx;
    private final Clock clock;

    NotificationPerceptionDelivery(JdbcTemplate jdbc, NotificationPerceptionQueue queue, NotificationChannelService settings,
                                   WebhookReminderService webhookSettings, NotificationOutboxEnqueuer app, WebhookReminderOutbox webhook,
                                   TelegramReminderService telegram, EmailCodeSender email, PlatformTransactionManager manager,
                                   org.springframework.beans.factory.ObjectProvider<Clock> clocks) {
        this.jdbc = jdbc;
        this.queue = queue;
        this.settings = settings;
        this.webhookSettings = webhookSettings;
        this.app = app;
        this.webhook = webhook;
        this.telegram = telegram;
        this.email = email;
        this.tx = new TransactionTemplate(manager);
        this.clock = clocks.getIfAvailable(Clock::systemUTC);
    }

    void schedule(long userId, String key, List<String> selected) {
        var ids = jdbc.queryForList("SELECT id FROM alert_event WHERE user_id=? AND event_key=?", Long.class, userId, key);
        if (ids.isEmpty()) return;
        var configured = settings.get(userId);
        boolean confirmation = configured.confirmationEnabled();
        var tiers = NotificationPerceptionPlan.stages(channels(userId, configured),
                selected == null ? null : Set.copyOf(selected), confirmation);
        queue.schedule(userId, ids.getFirst(), tiers, confirmation, now());
    }

    private List<NotificationPerceptionPlan.Channel> channels(long userId, NotificationChannelService.Channels configured) {
        var strengths = settings.strengths(userId);
        var hooks = webhookSettings.available() ? webhookSettings.list(userId) : null;
        boolean webhookEnabled = hooks != null && hooks.enabled() && hooks.items().stream().anyMatch(WebhookReminderService.Item::enabled);
        return List.of(
                new NotificationPerceptionPlan.Channel("email", Strength.valueOf(strengths.get("email")),
                        eligible(configured.email()) && recipient(userId) != null),
                new NotificationPerceptionPlan.Channel("bitpongo", Strength.valueOf(strengths.get("bitpongo")), eligible(configured.bitpongo())),
                new NotificationPerceptionPlan.Channel("telegram", Strength.valueOf(strengths.get("telegram")), eligible(configured.telegram())),
                new NotificationPerceptionPlan.Channel("webhook", Strength.valueOf(strengths.get("webhook")), webhookEnabled));
    }

    private static boolean eligible(NotificationChannelService.Channel channel) {
        return channel.available() && channel.enabled();
    }

    boolean dispatchWave(NotificationPerceptionQueue.Wave wave) {
        var ready = channels(wave.userId(), settings.get(wave.userId())).stream()
                .filter(NotificationPerceptionPlan.Channel::eligible).map(NotificationPerceptionPlan.Channel::code).toList();
        boolean dispatched = false;
        for (String channel : wave.channels()) {
            if (!ready.contains(channel)) continue;
            var event = new NotificationEvent(NotificationEventType.valueOf(wave.eventType()), wave.userId(), null, null,
                    clock.instant(), wave.eventKey(), Map.of("symbol", wave.symbol() == null ? "" : wave.symbol(), "status", wave.body()));
            switch (channel) {
                case "bitpongo" -> app.enqueueAppOnly(event);
                case "webhook" -> {
                    if (event.type() == NotificationEventType.PRICE_ALERT_TRIGGERED) webhook.enqueuePrice(event);
                    else webhook.enqueue(event);
                }
                case "email", "telegram" -> jdbc.update("""
                        INSERT INTO notification_perception_delivery(event_id,channel_code,due_at)
                        VALUES (?,?,?) ON DUPLICATE KEY UPDATE due_at=notification_perception_delivery.due_at
                        """, wave.eventId(), channel, now());
                default -> { continue; }
            }
            dispatched = true;
        }
        return dispatched;
    }

    @Scheduled(fixedDelayString = "${NOTIFICATION_PERCEPTION_DELAY:2s}",
            initialDelayString = "${NOTIFICATION_PERCEPTION_INITIAL_DELAY:10s}", scheduler = "notificationPerceptionScheduler")
    void advanceWaves() {
        for (int i = 0; i < 20 && queue.advance(now(), this::dispatchWave); i++) {
            // Bounded batches leave room for other scheduled work.
        }
    }

    @Scheduled(fixedDelayString = "${NOTIFICATION_PERCEPTION_PROVIDER_DELAY:2s}",
            initialDelayString = "${NOTIFICATION_PERCEPTION_INITIAL_DELAY:10s}", scheduler = "notificationPerceptionScheduler")
    void deliverDue() {
        for (int i = 0; i < 10; i++) {
            var job = tx.execute(ignored -> claim());
            if (job == null) return;
            boolean success = false;
            try {
                if (job.attempt() <= 5) success = send(job);
            } catch (RuntimeException failure) {
                LoggerFactory.getLogger(getClass()).warn("Perception delivery failed for {} ({})",
                        job.channel(), failure.getClass().getSimpleName());
            }
            boolean retry = !success && job.attempt() < 5;
            jdbc.update("""
                    UPDATE notification_perception_delivery SET status=?,due_at=?,lease_token=NULL
                    WHERE event_id=? AND channel_code=? AND status='SENDING' AND lease_token=?
                    """, success ? "SENT" : retry ? "RETRY" : "FAILED",
                    retry ? now().plusSeconds(30L << (job.attempt() - 1)) : now(), job.id(), job.channel(), job.token());
        }
    }

    private Job claim() {
        var jobs = jdbc.query("""
                SELECT event_id,channel_code,attempts FROM notification_perception_delivery
                WHERE status IN ('PENDING','RETRY','SENDING') AND due_at<=?
                ORDER BY due_at,event_id,channel_code LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new Job(rs.getLong(1), rs.getString(2), rs.getInt(3) + 1, UUID.randomUUID().toString()), now());
        if (jobs.isEmpty()) return null;
        var job = jobs.getFirst();
        jdbc.update("""
                UPDATE notification_perception_delivery SET status='SENDING',attempts=?,due_at=?,lease_token=?
                WHERE event_id=? AND channel_code=?
                """, job.attempt(), now().plusMinutes(2), job.token(), job.id(), job.channel());
        return job;
    }

    private boolean send(Job job) {
        var events = jdbc.query("""
                SELECT e.user_id,e.title,e.body FROM alert_event e JOIN user u ON u.id=e.user_id
                WHERE e.id=? AND e.inbox_deleted=false AND u.status='active'
                """, (rs, row) -> new Message(rs.getLong(1), rs.getString(2), rs.getString(3)), job.id());
        if (events.isEmpty()) return true;
        var message = events.getFirst();
        if ("telegram".equals(job.channel())) {
            return telegram.sendPerception(message.owner(), "Bitpongo · " + message.title() + "\n" + message.body());
        }
        String to = recipient(message.owner());
        if (to == null) return true;
        if (!email.available()) return false;
        email.sendNotification(to, message.title(), message.body());
        return true;
    }

    private String recipient(long userId) {
        return jdbc.queryForList("""
                SELECT COALESCE(NULLIF(TRIM(c.reminder_email),''),NULLIF(TRIM(u.email),''))
                FROM user_notification_channel c JOIN user u ON u.id=c.user_id
                WHERE c.user_id=? AND c.email_enabled=true AND u.status='active' AND NULLIF(TRIM(u.email),'') IS NOT NULL
                """, String.class, userId).stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
    private record Job(long id, String channel, int attempt, String token) {}
    private record Message(long owner, String title, String body) {}

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @Profile("!test")
    static class Scheduling {
        @org.springframework.context.annotation.Bean(defaultCandidate = false)
        org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler notificationPerceptionScheduler() {
            var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
            scheduler.setPoolSize(2);
            scheduler.setThreadNamePrefix("notification-perception-");
            return scheduler;
        }
    }
}
