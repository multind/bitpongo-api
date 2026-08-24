package com.multind.bitpongo.notification;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(properties = {
        "zhitoubao.jwt.secret-key=outbox-publisher-test-secret",
        "zhitoubao.notifications.bark.admin-push-url=https://localhost/admin-secret-key",
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
@Import(OutboxNotificationPublisherTest.AdjustableClockConfiguration.class)
class OutboxNotificationPublisherTest {

    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer(
            System.getProperty("test.mysql.image", "mysql:9.7.0"))
            .withDatabaseName("zhitoubao");

    @Autowired
    private NotificationPublisher publisher;

    @Autowired
    private NotificationOutboxRepository outbox;

    @Autowired
    private NotificationAudienceResolver audiences;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private NotificationDedupeWindowStore windows;

    @Autowired
    private AdjustableClock clock;

    @BeforeEach
    void clearOutbox() {
        outbox.deleteAll();
        jdbc.update("delete from notification_dedupe_window");
        clock.set(Instant.parse("2026-08-23T04:09:59Z"));
    }

    @Test
    void publishingTheSameDedupeKeyTwicePersistsOneOutboxRecord() {
        long userId = createUser("publisher-sequential@example.com");
        enableBarkFor(userId);
        NotificationEvent event = event(userId, "trade:plan-7:fire-20260823:BTC");

        publisher.publish(event);
        publisher.publish(event);

        assertThat(outbox.findAll())
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.getRecipientType()).isEqualTo(NotificationRecipientType.USER);
                    assertThat(message.getUserId()).isEqualTo(userId);
                    assertThat(message.getStatus()).isEqualTo(NotificationOutboxStatus.PENDING);
                    assertThat(message.getAttempts()).isZero();
                });
    }

    @Test
    void concurrentPublishersRacingOnTheSameDedupeKeyPersistOneRecord() throws Exception {
        long userId = createUser("publisher-concurrent@example.com");
        enableBarkFor(userId);
        NotificationEvent event = event(userId, "trade:plan-8:fire-20260823:ETH");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<?> first = workers.submit(() -> publishAfterBarrier(event, ready, start));
            Future<?> second = workers.submit(() -> publishAfterBarrier(event, ready, start));
            ready.await();
            start.countDown();
            first.get();
            second.get();
        }

        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    void windowedEventsTwoSecondsAcrossFixedBucketBoundaryPersistOnePerAudience() {
        long userId = createUser("window-boundary@example.com");
        enableBarkFor(userId);
        NotificationDedupeWindow window = new NotificationDedupeWindow(
                "scheduler-fatal:plan-purchase:42", Duration.ofMinutes(10));

        publisher.publish(windowedSchedulerEvent(
                userId, "scheduler-fatal:plan-purchase:42:old-bucket", window));
        clock.advance(Duration.ofSeconds(2));
        publisher.publish(windowedSchedulerEvent(
                userId, "scheduler-fatal:plan-purchase:42:new-bucket", window));

        assertThat(outbox.findAll())
                .hasSize(2)
                .extracting(NotificationOutboxEntity::getRecipientType)
                .containsExactlyInAnyOrder(
                        NotificationRecipientType.USER, NotificationRecipientType.ADMIN);
    }

    @Test
    void windowAllowsAnotherOutboxRecordExactlyAtExpiry() {
        long userId = createUser("window-expiry@example.com");
        enableBarkFor(userId);
        NotificationDedupeWindow window = new NotificationDedupeWindow(
                "scheduler-fatal:plan-purchase:43", Duration.ofMinutes(10));

        publisher.publish(windowedSchedulerEvent(
                userId, "scheduler-fatal:plan-purchase:43:first", window));
        clock.advance(Duration.ofMinutes(10));
        publisher.publish(windowedSchedulerEvent(
                userId, "scheduler-fatal:plan-purchase:43:second", window));

        assertThat(outbox.findAll())
                .hasSize(4)
                .extracting(NotificationOutboxEntity::getRecipientType)
                .containsExactlyInAnyOrder(
                        NotificationRecipientType.USER, NotificationRecipientType.ADMIN,
                        NotificationRecipientType.USER, NotificationRecipientType.ADMIN);
    }

    @Test
    void concurrentWindowAcquisitionPersistsOneRecordPerAudience() throws Exception {
        long userId = createUser("window-concurrent@example.com");
        enableBarkFor(userId);
        NotificationDedupeWindow window = new NotificationDedupeWindow(
                "scheduler-fatal:plan-purchase:44", Duration.ofMinutes(10));
        NotificationEvent firstEvent = windowedSchedulerEvent(
                userId, "scheduler-fatal:plan-purchase:44:first", window);
        NotificationEvent secondEvent = windowedSchedulerEvent(
                userId, "scheduler-fatal:plan-purchase:44:second", window);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<?> first = workers.submit(() -> publishAfterBarrier(firstEvent, ready, start));
            Future<?> second = workers.submit(() -> publishAfterBarrier(secondEvent, ready, start));
            ready.await();
            start.countDown();
            first.get();
            second.get();
        }

        assertThat(outbox.findAll())
                .hasSize(2)
                .extracting(NotificationOutboxEntity::getRecipientType)
                .containsExactlyInAnyOrder(
                        NotificationRecipientType.USER, NotificationRecipientType.ADMIN);
    }

    @Test
    void duplicateUserScopeLeavesTransactionUsableAndEnqueuesAdminAudience() {
        long userId = createUser("window-audience@example.com");
        enableBarkFor(userId);
        String scope = "scheduler-fatal:plan-purchase:45";
        jdbc.update("""
                insert into notification_dedupe_window (scope_key, expires_at, updated_at)
                values (?, ?, ?)
                """, scope + ":USER:" + userId,
                java.time.LocalDateTime.ofInstant(clock.instant().plusSeconds(600), ZoneOffset.UTC),
                java.time.LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));

        publisher.publish(windowedSchedulerEvent(
                userId, "scheduler-fatal:plan-purchase:45:attempt",
                new NotificationDedupeWindow(scope, Duration.ofMinutes(10))));

        assertThat(outbox.findAll()).singleElement()
                .extracting(NotificationOutboxEntity::getRecipientType)
                .isEqualTo(NotificationRecipientType.ADMIN);
        assertThat(jdbc.queryForObject(
                "select count(*) from notification_dedupe_window where scope_key = ?",
                Integer.class, scope + ":ADMIN")).isOne();
    }

    @Test
    void nonDuplicateDatabaseErrorsPropagateFromWindowAcquisition() {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        String valueTooLongForDatabaseColumn = "x".repeat(192);

        assertThatThrownBy(() -> windows.tryAcquire(
                valueTooLongForDatabaseColumn,
                now,
                now.plusMinutes(10)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject(
                "select count(*) from notification_dedupe_window",
                Integer.class)).isZero();
    }

    @Test
    void failedOutboxTransactionDoesNotBurnTheWindow() {
        long userId = 900_007L;
        String scope = "scheduler-fatal:plan-purchase:46";
        NotificationEvent event = windowedSchedulerEvent(
                userId, "scheduler-fatal:plan-purchase:46:attempt",
                new NotificationDedupeWindow(scope, Duration.ofMinutes(10)));

        publisher.publish(event);

        assertThat(outbox.count()).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from notification_dedupe_window where scope_key like ?",
                Integer.class, scope + "%")).isZero();

        jdbc.update("insert into user (id, name, email, password) values (?, ?, ?, ?)",
                userId, "Outbox Test", "window-rollback@example.com", "unused");
        enableBarkFor(userId);
        publisher.publish(event);

        assertThat(outbox.findAll()).hasSize(2);
    }

    @Test
    void enqueueCommitsIndependentlyWhenOuterBusinessTransactionRollsBack() {
        long userId = createUser("publisher-requires-new@example.com");
        enableBarkFor(userId);
        NotificationEvent event = event(userId, "trade:plan-9:rollback-proof");
        TransactionTemplate outer = new TransactionTemplate(transactionManager);

        outer.executeWithoutResult(status -> {
            publisher.publish(event);
            status.setRollbackOnly();
        });

        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    void enqueueFailureNeverEscapesToTheBusinessCaller() {
        NotificationEvent invalid = new NotificationEvent(
                NotificationEventType.SCHEDULER_FATAL,
                999_999L,
                7L,
                null,
                Instant.parse("2026-08-23T04:00:00Z"),
                "trade:missing-user",
                Map.of("symbol", "BTCUSDT"));

        assertThatCode(() -> publisher.publish(invalid)).doesNotThrowAnyException();
        assertThat(outbox.count()).isZero();
    }

    @Test
    void marketOutageTargetsDistinctUsersAndConfiguredAdmin() {
        long firstUser = createUser("audience-first@example.com");
        long secondUser = createUser("audience-second@example.com");
        long closedUser = createUser("audience-closed@example.com");
        createPlan(firstUser, "active");
        createPlan(firstUser, "active");
        createPlan(secondUser, "active");
        createPlan(closedUser, "close");

        List<NotificationAudienceResolver.Audience> resolved = audiences.resolve(
                new NotificationEvent(
                        NotificationEventType.MARKET_OUTAGE,
                        null,
                        null,
                        null,
                        Instant.parse("2026-08-23T04:00:00Z"),
                        "market:outage:20260823T040000Z",
                        Map.of()));

        assertThat(resolved)
                .extracting(NotificationAudienceResolver.Audience::userId)
                .containsExactlyInAnyOrder(firstUser, secondUser, null);
        assertThat(resolved)
                .extracting(NotificationAudienceResolver.Audience::recipientType)
                .containsExactlyInAnyOrder(
                        NotificationRecipientType.USER,
                        NotificationRecipientType.USER,
                        NotificationRecipientType.ADMIN);
    }

    @Test
    void configuredAdminProducesAnAdminAudienceWithoutPersistingThePushUrl() {
        BarkProperties properties = new BarkProperties(
                false,
                "https://localhost/admin-secret-device-key",
                Set.of("localhost"),
                true,
                "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                true,
                false,
                "https://app.example.com");
        NotificationAudienceResolver resolver = new NotificationAudienceResolver(
                null, properties);

        assertThat(resolver.resolve(event(null, "scheduler:fatal:1")))
                .containsExactly(new NotificationAudienceResolver.Audience(
                        NotificationRecipientType.ADMIN, null));
    }

    private void publishAfterBarrier(
            NotificationEvent event, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            start.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
        publisher.publish(event);
    }

    private long createUser(String email) {
        jdbc.update("insert into user (name, email, password) values (?, ?, ?)",
                "Outbox Test", email, "unused");
        return jdbc.queryForObject("select id from user where email = ?", Long.class, email);
    }

    private void enableBarkFor(long userId) {
        jdbc.update("insert into user_bark_setting "
                        + "(user_id, server_url, device_key_ciphertext, enabled) values (?, ?, ?, ?)",
                userId, "https://localhost", "fixture-ciphertext", true);
    }

    private void createPlan(long userId, String status) {
        jdbc.update("insert into exchange (name, exchange, user_id) values (?, ?, ?)",
                "Test Exchange", "binance", userId);
        long exchangeId = jdbc.queryForObject("select last_insert_id()", Long.class);
        jdbc.update("insert into strategy (name, `condition`, user_id) values (?, ?, ?)",
                "Test Strategy", "none", userId);
        long strategyId = jdbc.queryForObject("select last_insert_id()", Long.class);
        jdbc.update("insert into plan (status, user_id, strategy_id, exchange_id) values (?, ?, ?, ?)",
                status, userId, strategyId, exchangeId);
    }

    private static NotificationEvent event(Long userId, String dedupeKey) {
        return new NotificationEvent(
                userId == null
                        ? NotificationEventType.SCHEDULER_FATAL
                        : NotificationEventType.TRADE_FAILED,
                userId,
                7L,
                null,
                Instant.parse("2026-08-23T04:00:00Z"),
                dedupeKey,
                Map.of("symbol", "BTCUSDT", "error", "insufficient balance"));
    }

    private static NotificationEvent windowedSchedulerEvent(
            long userId,
            String dedupeKey,
            NotificationDedupeWindow window) {
        return new NotificationEvent(
                NotificationEventType.SCHEDULER_FATAL,
                userId,
                42L,
                null,
                Instant.parse("2026-08-23T04:00:00Z"),
                dedupeKey,
                Map.of("status", "PLAN_PURCHASE_FAILED"),
                null,
                window);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class AdjustableClockConfiguration {
        @Bean
        AdjustableClock notificationClock() {
            return new AdjustableClock(Instant.parse("2026-08-23T04:09:59Z"));
        }
    }

    static final class AdjustableClock extends Clock {
        private final AtomicReference<Instant> current;

        AdjustableClock(Instant initial) {
            current = new AtomicReference<>(initial);
        }

        void set(Instant instant) {
            current.set(instant);
        }

        void advance(Duration duration) {
            current.updateAndGet(instant -> instant.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return zone.equals(ZoneOffset.UTC) ? this : Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }
}
