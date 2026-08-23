package com.multind.bitpongo.notification;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@Testcontainers
@SpringBootTest(properties = {
        "zhitoubao.jwt.secret-key=outbox-publisher-test-secret",
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

    @BeforeEach
    void clearOutbox() {
        outbox.deleteAll();
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
                NotificationEventType.TRADE_FAILED,
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
    void marketOutageTargetsDistinctUsersWithActivePlansAndNoAdminWhenUnconfigured() {
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
                .containsExactlyInAnyOrder(firstUser, secondUser);
        assertThat(resolved)
                .extracting(NotificationAudienceResolver.Audience::recipientType)
                .containsOnly(NotificationRecipientType.USER);
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
}
