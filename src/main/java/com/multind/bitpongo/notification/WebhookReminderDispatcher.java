package com.multind.bitpongo.notification;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Profile("!test")
class WebhookReminderDispatcher {
    private static final int MAX_ATTEMPTS = 8;
    private final JdbcTemplate jdbc;
    private final WebhookReminderService settings;
    private final TransactionTemplate tx;

    WebhookReminderDispatcher(JdbcTemplate jdbc, WebhookReminderService settings, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.tx = new TransactionTemplate(manager);
    }

    @Scheduled(fixedDelayString = "${OUTBOUND_WEBHOOK_DISPATCH_DELAY:5s}",
            initialDelayString = "${OUTBOUND_WEBHOOK_DISPATCH_INITIAL_DELAY:30s}", scheduler = "outboundWebhookScheduler")
    void dispatch() {
        if (!settings.available()) return;
        for (int i = 0; i < 10; i++) {
            Lease lease = tx.execute(ignored -> claim());
            if (lease == null) return;
            try {
                tx.executeWithoutResult(ignored -> deliver(lease));
            } catch (RuntimeException failure) {
                // A failed transaction cannot hold the queue hostage. The lease expires if this update also fails.
                retry(lease);
            }
        }
    }

    private Lease claim() {
        var jobs = jdbc.query("""
                SELECT id,destination_id,attempts FROM outbound_webhook_delivery
                WHERE (status IN ('PENDING','RETRY') AND next_attempt_at<=UTC_TIMESTAMP(6))
                   OR (status='SENDING' AND lease_until<=UTC_TIMESTAMP(6))
                ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new Lease(rs.getLong(1), rs.getLong(2), rs.getInt(3) + 1, UUID.randomUUID().toString()));
        if (jobs.isEmpty()) return null;
        Lease lease = jobs.getFirst();
        jdbc.update("""
                UPDATE outbound_webhook_delivery SET status='SENDING',lease_token=?,
                lease_until=UTC_TIMESTAMP(6)+INTERVAL 2 MINUTE,attempts=?,updated_at=UTC_TIMESTAMP(6) WHERE id=?
                """, lease.token(), lease.attempt(), lease.id());
        return lease;
    }

    private void deliver(Lease lease) {
        // Settings edits take the same destination lock; once disabled, no later send can start.
        var destinations = jdbc.queryForList(
                "SELECT user_id FROM outbound_webhook_destination WHERE id=? AND enabled=true FOR UPDATE", Long.class, lease.destinationId());
        var jobs = jdbc.queryForList("""
                SELECT message FROM outbound_webhook_delivery
                WHERE id=? AND status='SENDING' AND lease_token=? AND lease_until>UTC_TIMESTAMP(6) FOR UPDATE
                """, String.class, lease.id(), lease.token());
        if (jobs.isEmpty()) return;
        if (destinations.isEmpty() || jdbc.queryForList("SELECT id FROM user WHERE id=? AND status='active'",
                Long.class, destinations.getFirst()).isEmpty()) {
            finish(lease, "SKIPPED", 0);
        } else if (lease.attempt() > MAX_ATTEMPTS) {
            finish(lease, "DEAD", 0);
        } else {
            switch (settings.sendEnabled(destinations.getFirst(), lease.destinationId(), jobs.getFirst())) {
                case SENT -> finish(lease, "SENT", 0);
                case REJECTED -> finish(lease, "DEAD", 0);
                case RETRY -> retry(lease);
            }
        }
    }

    private void retry(Lease lease) {
        if (lease.attempt() >= MAX_ATTEMPTS) finish(lease, "DEAD", 0);
        else finish(lease, "RETRY", Math.min(3600, 60 << (lease.attempt() - 1)));
    }

    private void finish(Lease lease, String state, int seconds) {
        jdbc.update("""
                UPDATE outbound_webhook_delivery SET status=?,next_attempt_at=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)),
                updated_at=UTC_TIMESTAMP(6),lease_token=NULL,lease_until=NULL
                WHERE id=? AND status='SENDING' AND lease_token=?
                """, state, seconds, lease.id(), lease.token());
    }

    @Scheduled(fixedDelayString = "24h", initialDelayString = "5m", scheduler = "outboundWebhookScheduler")
    void cleanup() {
        if (!settings.available()) return;
        jdbc.update("""
                DELETE FROM outbound_webhook_delivery WHERE updated_at < UTC_TIMESTAMP(6)-INTERVAL 30 DAY
                AND status IN ('SENT','DEAD','SKIPPED')
                """);
    }

    private record Lease(long id, long destinationId, int attempt, String token) {}

    @Configuration(proxyBeanMethods = false)
    @Profile("!test")
    static class Scheduling {
        // Do not replace the application's default scheduler: provider I/O can be slow.
        @Bean(defaultCandidate = false)
        ThreadPoolTaskScheduler outboundWebhookScheduler() {
            var scheduler = new ThreadPoolTaskScheduler();
            scheduler.setThreadNamePrefix("outbound-webhook-");
            return scheduler;
        }
    }
}
