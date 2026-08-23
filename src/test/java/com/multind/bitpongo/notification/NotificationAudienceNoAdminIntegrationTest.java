package com.multind.bitpongo.notification;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

@Testcontainers
@SpringBootTest(properties = {
        "zhitoubao.jwt.secret-key=no-admin-audience-test-secret",
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
class NotificationAudienceNoAdminIntegrationTest {

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
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearOutbox() {
        outbox.deleteAll();
    }

    @Test
    void missingAdminConfigurationNeverCreatesAdminRows() {
        long user = createUser("no-admin-user");
        configureUser(user, true);

        publisher.publish(event(NotificationEventType.SCHEDULER_FATAL, null, "no-admin:fatal"));
        assertThat(outbox.count()).isZero();

        publisher.publish(event(NotificationEventType.SERVICE_STARTED, user, "no-admin:start"));
        assertThat(outbox.count()).isZero();

        publisher.publish(event(NotificationEventType.TRADE_FAILED, user, "no-admin:trade-user"));
        assertRecipients(tuple(NotificationRecipientType.USER, user));

        outbox.deleteAll();
        long unset = createUser("no-admin-unset");
        publisher.publish(event(
                NotificationEventType.TRADE_FAILED, unset, "no-admin:trade-fallback"));
        assertThat(outbox.count()).isZero();

        publisher.publish(new NotificationEvent(
                NotificationEventType.SYSTEM_RECOVERED,
                null,
                null,
                null,
                Instant.parse("2026-08-23T04:00:00Z"),
                "no-admin:recovered",
                Map.of(),
                new NotificationAudienceContext(Set.of(user), true)));
        assertRecipients(tuple(NotificationRecipientType.USER, user));
    }

    private void assertRecipients(org.assertj.core.groups.Tuple... expected) {
        assertThat(outbox.findAll())
                .extracting(
                        NotificationOutboxEntity::getRecipientType,
                        NotificationOutboxEntity::getUserId)
                .containsExactlyInAnyOrder(expected);
    }

    private long createUser(String name) {
        String email = name + '-' + System.nanoTime() + "@example.com";
        jdbc.update("insert into user (name, email, password) values (?, ?, ?)",
                "No Admin Test", email, "unused");
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    private void configureUser(long userId, boolean enabled) {
        jdbc.update("""
                insert into user_bark_setting (
                    user_id, server_url, device_key_ciphertext, enabled, locale, timezone)
                values (?, 'https://localhost', 'ciphertext', ?, 'en-US', 'UTC')
                """, userId, enabled);
    }

    private static NotificationEvent event(
            NotificationEventType type,
            Long userId,
            String dedupeKey) {
        return new NotificationEvent(
                type,
                userId,
                7L,
                null,
                Instant.parse("2026-08-23T04:00:00Z"),
                dedupeKey,
                Map.of("error", "safe summary"));
    }
}
