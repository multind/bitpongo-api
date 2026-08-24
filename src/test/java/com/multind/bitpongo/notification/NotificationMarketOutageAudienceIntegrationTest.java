package com.multind.bitpongo.notification;

import java.time.Instant;
import java.util.Map;
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
        "zhitoubao.jwt.secret-key=market-outage-audience-test-secret",
        "zhitoubao.notifications.bark.admin-push-url=https://localhost/admin-device-key",
        "zhitoubao.notifications.bark.allowed-hosts=localhost",
        "zhitoubao.notifications.bark.allow-private-hosts=true",
        "zhitoubao.notifications.bark.credential-encryption-key="
                + "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        "zhitoubao.notifications.bark.dispatch-enabled=false",
        "zhitoubao.market.stream-enabled=false",
        "spring.quartz.auto-startup=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationMarketOutageAudienceIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer(
            System.getProperty("test.mysql.image", "mysql:9.7.0"))
            .withDatabaseName("zhitoubao");

    @Autowired private NotificationPublisher publisher;
    @Autowired private NotificationAudienceResolver audiences;
    @Autowired private NotificationOutboxRepository outbox;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clearOutbox() {
        outbox.deleteAll();
    }

    @Test
    void outageAndRecoveryReuseThresholdSnapshotWithoutPersistingOrExpandingIt() {
        long original = createUser("market-original");
        createPlan(original, "active");
        createPlan(original, "active");
        NotificationAudienceContext snapshot = audiences.snapshotMarketOutageAudience();

        long addedAfterThreshold = createUser("market-added-later");
        createPlan(addedAfterThreshold, "active");

        publisher.publish(event(NotificationEventType.MARKET_OUTAGE,
                "market-outage:cycle-snapshot", Map.of("status", "UNAVAILABLE"), snapshot));
        publisher.publish(event(NotificationEventType.SYSTEM_RECOVERED,
                "system-recovered:cycle-snapshot",
                Map.of("status", "RECOVERED", "originalEventType", "MARKET_OUTAGE"),
                snapshot));

        assertThat(outbox.findAll())
                .extracting(NotificationOutboxEntity::getEventType,
                        NotificationOutboxEntity::getRecipientType,
                        NotificationOutboxEntity::getUserId)
                .containsExactlyInAnyOrder(
                        tuple(NotificationEventType.MARKET_OUTAGE,
                                NotificationRecipientType.USER, original),
                        tuple(NotificationEventType.MARKET_OUTAGE,
                                NotificationRecipientType.ADMIN, null),
                        tuple(NotificationEventType.SYSTEM_RECOVERED,
                                NotificationRecipientType.USER, original),
                        tuple(NotificationEventType.SYSTEM_RECOVERED,
                                NotificationRecipientType.ADMIN, null));
        assertThat(outbox.findAll())
                .extracting(NotificationOutboxEntity::getUserId)
                .doesNotContain(addedAfterThreshold);
        assertThat(jdbc.queryForList(
                "select cast(body_payload as char) from notification_outbox", String.class))
                .allSatisfy(payload -> assertThat(payload)
                        .doesNotContain("audienceContext", "recipientUserIds", "userId"));
        assertThat(jdbc.queryForObject("""
                select count(*) from notification_outbox
                 where json_extract(body_payload, '$.audienceContext') is not null
                    or json_extract(body_payload, '$.recipientUserIds') is not null
                    or json_extract(body_payload, '$.userId') is not null
                """, Integer.class)).isZero();
    }

    private long createUser(String name) {
        String email = name + '-' + System.nanoTime() + "@example.com";
        jdbc.update("insert into user (name, email, password) values (?, ?, ?)",
                "Market Audience Test", email, "unused");
        return jdbc.queryForObject("select last_insert_id()", Long.class);
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

    private static NotificationEvent event(
            NotificationEventType type,
            String dedupeKey,
            Map<String, Object> attributes,
            NotificationAudienceContext snapshot) {
        return new NotificationEvent(type, null, null, null,
                Instant.parse("2026-08-23T04:00:00Z"), dedupeKey, attributes, snapshot);
    }
}
