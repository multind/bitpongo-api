package com.multind.bitpongo.notification;

import com.multind.bitpongo.common.api.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@Testcontainers
@SpringBootTest(properties = {
        "zhitoubao.jwt.secret-key=outbox-dispatcher-test-secret",
        "zhitoubao.notifications.bark.admin-push-url="
                + "https://localhost/admin-environment-device-key",
        "zhitoubao.notifications.bark.allowed-hosts=localhost",
        "zhitoubao.notifications.bark.allow-private-hosts=true",
        "zhitoubao.notifications.bark.credential-encryption-key="
                + "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        "zhitoubao.notifications.bark.app-public-url=https://app.example.com",
        "zhitoubao.notifications.bark.dispatch-delay=24h",
        "zhitoubao.notifications.bark.dispatch-initial-delay=24h",
        "zhitoubao.market.stream-enabled=false",
        "spring.quartz.auto-startup=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationOutboxDispatcherTest {

    private static final Instant NOW_INSTANT = Instant.parse("2026-08-23T12:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(NOW_INSTANT, ZoneOffset.UTC);
    private static final String CIPHER_KEY =
            "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer(
            System.getProperty("test.mysql.image", "mysql:9.7.0"))
            .withDatabaseName("zhitoubao");

    @Autowired
    private NotificationOutboxDispatcher dispatcher;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RecordingBarkClient bark;

    private final BarkCredentialCipher cipher = new BarkCredentialCipher(CIPHER_KEY);

    @BeforeEach
    void resetDatabaseAndClient() {
        jdbc.update("delete from notification_outbox");
        jdbc.update("delete from user_bark_setting");
        bark.reset();
    }

    @Test
    void successfulUserAndAdminDeliveryMarksRowsSentAndSendsOutsideTransaction() {
        long userId = createUser("dispatcher-success@example.com");
        configureUser(userId, true, "stored-user-device-key");
        long userMessage = insertMessage(
                "dispatch-success-user", NotificationRecipientType.USER, userId, 0);
        long adminMessage = insertMessage(
                "dispatch-success-admin", NotificationRecipientType.ADMIN, null, 0);

        dispatcher.dispatchDue();

        assertThat(status(userMessage)).isEqualTo("SENT");
        assertThat(status(adminMessage)).isEqualTo("SENT");
        assertThat(jdbc.queryForList(
                "select sent_at from notification_outbox order by id", LocalDateTime.class))
                .containsOnly(NOW);
        assertThat(jdbc.queryForObject(
                "select count(*) from notification_outbox where lease_until is null",
                Integer.class)).isEqualTo(2);
        assertThat(bark.deliveries())
                .extracting(delivery -> delivery.target().deviceKey())
                .containsExactlyInAnyOrder(
                        "stored-user-device-key", "admin-environment-device-key");
        assertThat(bark.deliveries())
                .extracting(Delivery::transactionActive)
                .containsOnly(false);
        assertThat(bark.deliveries())
                .extracting(delivery -> delivery.message().url())
                .containsOnly("https://app.example.com");
    }

    @Test
    void nullPayloadAttributeDoesNotPreventDelivery() {
        long messageId = insertMessage(
                "dispatch-null-attribute", NotificationRecipientType.ADMIN, null, 0);
        jdbc.update("""
                update notification_outbox
                   set body_payload = json_object(
                       'occurredAt', '2026-08-23T11:59:00Z',
                       'attributes', json_object('status', null))
                 where id = ?
                """, messageId);

        dispatcher.dispatchDue();

        assertThat(status(messageId)).isEqualTo("SENT");
        assertThat(bark.deliveries()).hasSize(1);
    }

    @Test
    void failedDeliveriesPersistExactBackoffAndSanitizedErrorAndTenthAttemptDies() {
        long userId = createUser("dispatcher-retry@example.com");
        configureUser(userId, true, "retry-user-device-key");
        List<Long> messages = new ArrayList<>();
        for (int attempts : List.of(0, 1, 2, 3, 4, 9)) {
            messages.add(insertMessage(
                    "dispatch-retry-" + attempts,
                    NotificationRecipientType.USER,
                    userId,
                    attempts));
        }
        bark.failWith(new BusinessException(502,
                "failed https://localhost/secret-device-key "
                        + "token=top-secret user@example.com"));

        assertThatCode(dispatcher::dispatchDue).doesNotThrowAnyException();

        assertRetry(messages.get(0), 1, NOW.plusSeconds(30));
        assertRetry(messages.get(1), 2, NOW.plusMinutes(2));
        assertRetry(messages.get(2), 3, NOW.plusMinutes(10));
        assertRetry(messages.get(3), 4, NOW.plusMinutes(30));
        assertRetry(messages.get(4), 5, NOW.plusMinutes(30));
        assertThat(status(messages.get(5))).isEqualTo("DEAD");
        assertThat(attempts(messages.get(5))).isEqualTo(10);

        assertThat(jdbc.queryForList(
                "select last_error from notification_outbox", String.class))
                .allSatisfy(error -> assertThat(error)
                        .contains("<redacted-uri>", "token=<redacted>", "<redacted-email>")
                        .doesNotContain("secret-device-key", "top-secret", "user@example.com"));
    }

    @Test
    void disabledOrDeletedUserTargetIsSkippedWithoutCallingBark() {
        long disabledUser = createUser("dispatcher-disabled@example.com");
        configureUser(disabledUser, false, "disabled-user-device-key");
        long deletedSettingUser = createUser("dispatcher-deleted-setting@example.com");
        long disabledMessage = insertMessage(
                "dispatch-disabled", NotificationRecipientType.USER, disabledUser, 0);
        long deletedMessage = insertMessage(
                "dispatch-deleted", NotificationRecipientType.USER, deletedSettingUser, 0);

        dispatcher.dispatchDue();

        assertThat(status(disabledMessage)).isEqualTo("SKIPPED");
        assertThat(status(deletedMessage)).isEqualTo("SKIPPED");
        assertThat(bark.deliveries()).isEmpty();
    }

    private void assertRetry(long id, int expectedAttempts, LocalDateTime nextAttemptAt) {
        assertThat(status(id)).isEqualTo("PENDING");
        assertThat(attempts(id)).isEqualTo(expectedAttempts);
        assertThat(jdbc.queryForObject(
                "select next_attempt_at from notification_outbox where id = ?",
                LocalDateTime.class,
                id)).isEqualTo(nextAttemptAt);
        assertThat(jdbc.queryForObject(
                "select lease_until is null from notification_outbox where id = ?",
                Boolean.class,
                id)).isTrue();
    }

    private String status(long id) {
        return jdbc.queryForObject(
                "select status from notification_outbox where id = ?", String.class, id);
    }

    private int attempts(long id) {
        return jdbc.queryForObject(
                "select attempts from notification_outbox where id = ?", Integer.class, id);
    }

    private long createUser(String email) {
        jdbc.update("insert into user (name, email, password) values (?, ?, ?)",
                "Dispatcher Test", email, "unused");
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    private void configureUser(long userId, boolean enabled, String deviceKey) {
        jdbc.update("""
                insert into user_bark_setting (
                    user_id, server_url, device_key_ciphertext, enabled, locale, timezone)
                values (?, 'https://localhost', ?, ?, 'en-US', 'UTC')
                """, userId, cipher.encrypt(deviceKey), enabled);
    }

    private long insertMessage(
            String dedupeKey,
            NotificationRecipientType recipientType,
            Long userId,
            int attempts) {
        jdbc.update("""
                insert into notification_outbox (
                    event_type, recipient_type, user_id, title_key, body_payload,
                    dedupe_key, priority, status, attempts, next_attempt_at,
                    lease_until, created_at, updated_at)
                values ('TRADE_FAILED', ?, ?, 'notification.trade_failed.title',
                    json_object(
                        'userId', ?,
                        'planId', 7,
                        'occurredAt', '2026-08-23T11:59:00Z',
                        'attributes', json_object(
                            'symbol', 'BTCUSDT', 'error', 'insufficient balance')),
                    ?, '10_TIME_SENSITIVE', 'PENDING', ?, ?, null, ?, ?)
                """, recipientType.name(), userId, userId, dedupeKey, attempts,
                NOW.minusSeconds(1), NOW.minusMinutes(1), NOW.minusMinutes(1));
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DispatcherTestConfiguration {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW_INSTANT, ZoneOffset.UTC);
        }

        @Bean
        @Primary
        RecordingBarkClient recordingBarkClient() {
            return new RecordingBarkClient();
        }
    }

    static final class RecordingBarkClient implements BarkClient {
        private final List<Delivery> deliveries = new CopyOnWriteArrayList<>();
        private volatile RuntimeException failure;

        @Override
        public void send(BarkTarget target, BarkMessage message) {
            deliveries.add(new Delivery(
                    target,
                    message,
                    TransactionSynchronizationManager.isActualTransactionActive()));
            if (failure != null) {
                throw failure;
            }
        }

        void failWith(RuntimeException failure) {
            this.failure = failure;
        }

        void reset() {
            deliveries.clear();
            failure = null;
        }

        List<Delivery> deliveries() {
            return List.copyOf(deliveries);
        }
    }

    private record Delivery(
            BarkTarget target, BarkMessage message, boolean transactionActive) {
    }
}
