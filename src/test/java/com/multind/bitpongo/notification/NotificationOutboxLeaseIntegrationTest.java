package com.multind.bitpongo.notification;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(properties = {
        "zhitoubao.jwt.secret-key=outbox-lease-test-secret",
        "zhitoubao.notifications.bark.admin-push-url=",
        "zhitoubao.notifications.bark.allowed-hosts=localhost",
        "zhitoubao.notifications.bark.allow-private-hosts=true",
        "zhitoubao.notifications.bark.credential-encryption-key="
                + "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        "zhitoubao.notifications.bark.dispatch-enabled=false",
        "zhitoubao.notifications.bark.dispatch-delay=24h",
        "zhitoubao.notifications.bark.dispatch-initial-delay=24h",
        "zhitoubao.market.stream-enabled=false",
        "spring.quartz.auto-startup=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationOutboxLeaseIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 23, 12, 0);

    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer(
            System.getProperty("test.mysql.image", "mysql:9.7.0"))
            .withDatabaseName("zhitoubao");

    @Autowired
    private NotificationOutboxLeaseService leases;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private long userId;

    @BeforeEach
    void resetDatabase() {
        jdbc.update("delete from notification_outbox");
        jdbc.update("insert into user (name, email, password) values (?, ?, ?)",
                "Lease Test", "lease-" + System.nanoTime() + "@example.com", "unused");
        userId = jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    @Test
    void concurrentWorkersLeaseDisjointRowsWhileBothTransactionsHoldTheirLocks()
            throws Exception {
        for (int index = 0; index < 100; index++) {
            insertMessage("lease-concurrent-" + index, NotificationOutboxStatus.PENDING,
                    NOW.minusMinutes(1), null);
        }
        CountDownLatch bothLeasedBeforeCommit = new CountDownLatch(2);
        int leasedCount;

        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<List<NotificationOutboxLeaseService.Lease>> first = workers.submit(
                    () -> leaseInsideHeldTransaction(bothLeasedBeforeCommit));
            Future<List<NotificationOutboxLeaseService.Lease>> second = workers.submit(
                    () -> leaseInsideHeldTransaction(bothLeasedBeforeCommit));

            List<Long> firstIds = ids(first.get());
            List<Long> secondIds = ids(second.get());
            Set<Long> overlap = new HashSet<>(firstIds);
            overlap.retainAll(secondIds);

            assertThat(firstIds).isNotEmpty().hasSizeLessThanOrEqualTo(50);
            assertThat(secondIds).isNotEmpty().hasSizeLessThanOrEqualTo(50);
            assertThat(overlap).isEmpty();
            leasedCount = firstIds.size() + secondIds.size();
        }

        assertThat(jdbc.queryForObject(
                "select count(*) from notification_outbox where status = 'SENDING'",
                Integer.class)).isEqualTo(leasedCount);
        assertThat(jdbc.queryForList(
                "select distinct lease_until from notification_outbox where status = 'SENDING'", LocalDateTime.class))
                .containsExactly(NOW.plusSeconds(30));
    }

    @Test
    void singleWorkerLeasesAtMostFiftyRows() {
        for (int index = 0; index < 51; index++) {
            insertMessage("lease-batch-" + index, NotificationOutboxStatus.PENDING,
                    NOW.minusMinutes(1), null);
        }

        assertThat(leases.leaseDue(NOW)).hasSize(50);
        assertThat(jdbc.queryForObject(
                "select count(*) from notification_outbox where status = 'PENDING'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void expiredSendingLeaseIsRecoveredButUnexpiredLeaseAndFuturePendingAreIgnored() {
        long expired = insertMessage("lease-expired", NotificationOutboxStatus.SENDING,
                NOW.minusMinutes(2), NOW.minusSeconds(1));
        insertMessage("lease-current", NotificationOutboxStatus.SENDING,
                NOW.minusMinutes(2), NOW.plusSeconds(1));
        insertMessage("lease-future", NotificationOutboxStatus.PENDING,
                NOW.plusSeconds(1), null);

        assertThat(leases.leaseDue(NOW))
                .extracting(NotificationOutboxLeaseService.Lease::id)
                .containsExactly(expired);
        assertThat(jdbc.queryForObject(
                "select lease_until from notification_outbox where id = ?",
                LocalDateTime.class,
                expired)).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void leasingAndSendingStateUpdateRollBackTogether() {
        long messageId = insertMessage("lease-rollback", NotificationOutboxStatus.PENDING,
                NOW.minusMinutes(1), null);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            assertThat(leases.leaseDue(NOW))
                    .extracting(NotificationOutboxLeaseService.Lease::id)
                    .containsExactly(messageId);
            assertThat(jdbc.queryForObject(
                    "select status from notification_outbox where id = ?",
                    String.class,
                    messageId)).isEqualTo("SENDING");
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject(
                "select status from notification_outbox where id = ?",
                String.class,
                messageId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                "select lease_until is null from notification_outbox where id = ?",
                Boolean.class,
                messageId)).isTrue();
        assertThat(jdbc.queryForObject(
                "select lease_token is null from notification_outbox where id = ?",
                Boolean.class,
                messageId)).isTrue();
    }

    private List<NotificationOutboxLeaseService.Lease> leaseInsideHeldTransaction(
            CountDownLatch bothLeasedBeforeCommit) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction.execute(status -> {
            List<NotificationOutboxLeaseService.Lease> leased = leases.leaseDue(NOW);
            bothLeasedBeforeCommit.countDown();
            try {
                bothLeasedBeforeCommit.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
            return leased;
        });
    }

    private static List<Long> ids(List<NotificationOutboxLeaseService.Lease> leased) {
        return leased.stream().map(NotificationOutboxLeaseService.Lease::id).toList();
    }

    private long insertMessage(
            String dedupeKey,
            NotificationOutboxStatus status,
            LocalDateTime nextAttemptAt,
            LocalDateTime leaseUntil) {
        jdbc.update("""
                insert into notification_outbox (
                    event_type, recipient_type, user_id, title_key, body_payload,
                    dedupe_key, priority, status, attempts, next_attempt_at,
                    lease_until, created_at, updated_at)
                values ('TRADE_FAILED', 'USER', ?, 'notification.trade_failed.title',
                    json_object('attributes', json_object('symbol', 'BTCUSDT')),
                    ?, '10_TIME_SENSITIVE', ?, 0, ?, ?, ?, ?)
                """, userId, dedupeKey, status.name(), nextAttemptAt, leaseUntil,
                NOW.minusMinutes(5), NOW.minusMinutes(5));
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }
}
