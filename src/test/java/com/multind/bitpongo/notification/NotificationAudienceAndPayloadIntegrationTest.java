package com.multind.bitpongo.notification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
        "zhitoubao.jwt.secret-key=audience-payload-test-secret",
        "zhitoubao.notifications.bark.admin-push-url="
                + "https://localhost/admin-secret-device-key",
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
class NotificationAudienceAndPayloadIntegrationTest {

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
    private NotificationMessageRenderer renderer;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearOutbox() {
        outbox.deleteAll();
    }

    @Test
    void criticalAudienceMatrixPersistsOnlyAuthorizedRows() {
        long schedulerUser = createUser("audience-scheduler");
        publisher.publish(event(
                NotificationEventType.SCHEDULER_FATAL,
                schedulerUser,
                "audience:scheduler:user"));
        assertRecipients(
                tuple(NotificationRecipientType.USER, schedulerUser),
                tuple(NotificationRecipientType.ADMIN, null));

        outbox.deleteAll();
        publisher.publish(event(
                NotificationEventType.SCHEDULER_FATAL,
                null,
                "audience:scheduler:admin"));
        assertRecipients(tuple(NotificationRecipientType.ADMIN, null));

        outbox.deleteAll();
        long manualUser = createUser("audience-manual");
        publisher.publish(event(
                NotificationEventType.ORDER_MANUAL_REVIEW,
                manualUser,
                "audience:manual:user"));
        assertRecipients(
                tuple(NotificationRecipientType.USER, manualUser),
                tuple(NotificationRecipientType.ADMIN, null));

        outbox.deleteAll();
        publisher.publish(event(
                NotificationEventType.ORDER_MANUAL_REVIEW,
                null,
                "audience:manual:no-user"));
        assertRecipients(tuple(NotificationRecipientType.ADMIN, null));
    }

    @Test
    void tradeFailureUsesEnabledUserOrAdminFallbackButNeverBoth() {
        long enabled = createUser("audience-trade-enabled");
        configureUser(enabled, true);
        publisher.publish(event(
                NotificationEventType.TRADE_FAILED, enabled, "audience:trade:enabled"));
        assertRecipients(tuple(NotificationRecipientType.USER, enabled));

        outbox.deleteAll();
        long disabled = createUser("audience-trade-disabled");
        configureUser(disabled, false);
        publisher.publish(event(
                NotificationEventType.TRADE_FAILED, disabled, "audience:trade:disabled"));
        assertRecipients(tuple(NotificationRecipientType.ADMIN, null));

        outbox.deleteAll();
        long unset = createUser("audience-trade-unset");
        publisher.publish(event(
                NotificationEventType.TRADE_FAILED, unset, "audience:trade:unset"));
        assertRecipients(tuple(NotificationRecipientType.ADMIN, null));
    }

    @Test
    void marketOutageTargetsAdminAndDistinctActivePlanUsers() {
        long first = createUser("audience-market-first");
        long second = createUser("audience-market-second");
        long closed = createUser("audience-market-closed");
        createPlan(first, "active");
        createPlan(first, "active");
        createPlan(second, "active");
        createPlan(closed, "close");

        publisher.publish(event(
                NotificationEventType.MARKET_OUTAGE,
                null,
                "audience:market:outage"));

        assertRecipients(
                tuple(NotificationRecipientType.USER, first),
                tuple(NotificationRecipientType.USER, second),
                tuple(NotificationRecipientType.ADMIN, null));
    }

    @Test
    void userOnlyAdminOnlyAndDirectTestEventsDoNotExpandAudience() {
        long user = createUser("audience-event-matrix");
        for (NotificationEventType type : List.of(
                NotificationEventType.PLAN_EXECUTION_SKIPPED,
                NotificationEventType.TRADE_SUCCEEDED,
                NotificationEventType.ASSET_SNAPSHOT_FAILED)) {
            outbox.deleteAll();
            publisher.publish(event(type, user, "audience:user-only:" + type));
            assertRecipients(tuple(NotificationRecipientType.USER, user));
        }

        outbox.deleteAll();
        publisher.publish(event(
                NotificationEventType.SERVICE_STARTED,
                user,
                "audience:service-started"));
        assertRecipients(tuple(NotificationRecipientType.ADMIN, null));

        outbox.deleteAll();
        publisher.publish(event(NotificationEventType.BARK_TEST, user, "audience:bark-test"));
        assertThat(outbox.count()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void bodyPayloadPersistsOnlyRenderableSanitizedFields() {
        long user = createUser("payload-sanitizer");
        configureUser(user, true);
        String rawUrl = "https://internal.example/private/device-key";
        String rawToken = "top-secret-token";
        String rawResponse = "raw-response-with-secret";
        publisher.publish(new NotificationEvent(
                NotificationEventType.TRADE_FAILED,
                user,
                7L,
                81L,
                Instant.parse("2026-08-23T04:00:00Z"),
                "payload:unsafe",
                Map.of(
                        "symbol", "BTCUSDT",
                        "resultStatus", "REJECTED",
                        "error", "request " + rawUrl + " token=" + rawToken,
                        "serverUrl", rawUrl,
                        "deviceKey", "device-super-secret",
                        "accessKey", "access-super-secret",
                        "secret", "plain-secret",
                        "rawResponse", rawResponse,
                        "unknown", "must-not-persist")));

        NotificationOutboxEntity stored = outbox.findAll().getFirst();
        String json = jdbc.queryForObject(
                "select cast(body_payload as char) from notification_outbox where id = ?",
                String.class,
                stored.getId());
        assertThat(json)
                .contains("BTCUSDT", "REJECTED", "<redacted-uri>", "token=<redacted>")
                .doesNotContain(
                        rawUrl,
                        rawToken,
                        rawResponse,
                        "device-super-secret",
                        "access-super-secret",
                        "plain-secret",
                        "must-not-persist",
                        "serverUrl",
                        "deviceKey",
                        "accessKey",
                        "rawResponse",
                        "unknown");

        Map<String, Object> attributes = (Map<String, Object>)
                stored.getBodyPayload().get("attributes");
        NotificationEvent fromStorage = new NotificationEvent(
                stored.getEventType(),
                user,
                7L,
                81L,
                Instant.parse("2026-08-23T04:00:00Z"),
                stored.getDedupeKey(),
                attributes);
        assertThat(renderer.render(fromStorage, "en-US", "UTC", null).body())
                .contains("Symbol: BTCUSDT", "Result: REJECTED", "<redacted-uri>")
                .doesNotContain(rawToken, rawUrl);
    }


    @Test
    @SuppressWarnings("unchecked")
    void aggregatedSymbolsSurviveTheRealOutboxRoundTripWithinSafetyBounds() {
        long user = createUser("payload-aggregate-symbols");
        configureUser(user, true);
        String rawUrl = "https://private.example/device-key";
        String rawSecret = "super-secret-value";
        List<Object> rawSymbols = new ArrayList<>();
        rawSymbols.add("BTCUSDT");
        rawSymbols.add("ETHUSDT");
        rawSymbols.add("X".repeat(80));
        rawSymbols.add(rawUrl);
        rawSymbols.add("accessKey=" + rawSecret);
        rawSymbols.add(Map.of("nested", "must-not-survive"));
        rawSymbols.add(42);
        for (int index = 0; index < 60; index++) {
            rawSymbols.add("COIN" + index + "USDT");
        }
        publisher.publish(new NotificationEvent(
                NotificationEventType.TRADE_SUCCEEDED,
                user,
                7L,
                null,
                Instant.parse("2026-08-23T04:00:00Z"),
                "payload:aggregate-symbols",
                Map.of("symbols", rawSymbols, "status", "FILLED")));

        NotificationOutboxEntity stored = outbox.findAll().getFirst();
        Map<String, Object> attributes = (Map<String, Object>)
                stored.getBodyPayload().get("attributes");
        List<String> symbols = (List<String>) attributes.get("symbols");
        String json = jdbc.queryForObject(
                "select cast(body_payload as char) from notification_outbox where id = ?",
                String.class,
                stored.getId());

        assertThat(symbols).hasSize(50);
        assertThat(symbols.subList(0, 2)).containsExactly("BTCUSDT", "ETHUSDT");
        assertThat(symbols.get(2).codePointCount(0, symbols.get(2).length())).isEqualTo(32);
        assertThat(symbols).contains("<redacted-uri>", "accessKey=<redacted>")
                .allSatisfy(symbol -> assertThat(symbol.codePointCount(0, symbol.length()))
                        .isLessThanOrEqualTo(32));
        assertThat(json).doesNotContain(
                rawUrl, rawSecret, "must-not-survive", "nested");

        NotificationEvent fromStorage = new NotificationEvent(
                stored.getEventType(), user, 7L, null,
                Instant.parse("2026-08-23T04:00:00Z"),
                stored.getDedupeKey(), attributes);
        assertThat(renderer.render(fromStorage, "en-US", "UTC", null).body())
                .contains("Symbol: BTCUSDT, ETHUSDT", "Result: FILLED")
                .doesNotContain(rawUrl, rawSecret, "must-not-survive");
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
                "Audience Test", email, "unused");
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    private void configureUser(long userId, boolean enabled) {
        jdbc.update("""
                insert into user_bark_setting (
                    user_id, server_url, device_key_ciphertext, enabled, locale, timezone)
                values (?, 'https://localhost', 'ciphertext', ?, 'en-US', 'UTC')
                """, userId, enabled);
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
            Long userId,
            String dedupeKey) {
        return new NotificationEvent(
                type,
                userId,
                7L,
                null,
                Instant.parse("2026-08-23T04:00:00Z"),
                dedupeKey,
                Map.of("symbol", "BTCUSDT", "error", "insufficient balance"));
    }
}
