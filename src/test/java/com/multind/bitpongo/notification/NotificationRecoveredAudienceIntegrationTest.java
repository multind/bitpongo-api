package com.multind.bitpongo.notification;

import java.time.Instant;
import java.util.LinkedHashSet;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

@Testcontainers
@SpringBootTest(properties = {
        "zhitoubao.jwt.secret-key=recovered-audience-test-secret",
        "zhitoubao.notifications.bark.admin-push-url=https://localhost/admin-device-key",
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
class NotificationRecoveredAudienceIntegrationTest {

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
    void recoveredEventReusesDefensivelyCopiedOriginalAudienceOnly() {
        long first = createUser("recovered-first");
        long second = createUser("recovered-second");
        long injectedAfterConstruction = createUser("recovered-injected");
        LinkedHashSet<Long> source = new LinkedHashSet<>(Set.of(first, second));
        NotificationAudienceContext context = new NotificationAudienceContext(source, true);
        source.clear();
        source.add(injectedAfterConstruction);

        publisher.publish(recovered("fault-cycle-17", context));

        assertThat(outbox.findAll())
                .extracting(
                        NotificationOutboxEntity::getRecipientType,
                        NotificationOutboxEntity::getUserId)
                .containsExactlyInAnyOrder(
                        tuple(NotificationRecipientType.USER, first),
                        tuple(NotificationRecipientType.USER, second),
                        tuple(NotificationRecipientType.ADMIN, null));
        assertThat(outbox.findAll())
                .extracting(NotificationOutboxEntity::getDedupeKey)
                .containsExactlyInAnyOrder(
                        "fault-cycle-17:USER:" + first,
                        "fault-cycle-17:USER:" + second,
                        "fault-cycle-17:ADMIN");
        assertThat(context.recipientUserIds()).doesNotContain(injectedAfterConstruction);
        assertThatThrownBy(() -> context.recipientUserIds().add(injectedAfterConstruction))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(outbox.findAll())
                .extracting(message -> message.getBodyPayload().toString())
                .allSatisfy(payload -> assertThat(payload)
                        .doesNotContain("audienceContext", "recipientUserIds", "fault-cycle-17"));
    }

    @Test
    void recoveredAudienceCanSelectUsersOrAdminWithoutImplicitExpansion() {
        long user = createUser("recovered-user-only");
        publisher.publish(recovered(
                "fault-cycle-user-only",
                new NotificationAudienceContext(Set.of(user), false)));
        assertThat(outbox.findAll())
                .extracting(
                        NotificationOutboxEntity::getRecipientType,
                        NotificationOutboxEntity::getUserId)
                .containsExactly(tuple(NotificationRecipientType.USER, user));

        outbox.deleteAll();
        publisher.publish(recovered(
                "fault-cycle-admin-only",
                new NotificationAudienceContext(Set.of(), true)));
        assertThat(outbox.findAll())
                .extracting(
                        NotificationOutboxEntity::getRecipientType,
                        NotificationOutboxEntity::getUserId)
                .containsExactly(tuple(NotificationRecipientType.ADMIN, null));

        outbox.deleteAll();
        publisher.publish(recovered("fault-cycle-missing-context", null));
        assertThat(outbox.count()).isZero();
    }

    @Test
    void typedContextRejectsNullOrNegativeUserIdsAndNonRecoveryUse() {
        assertThatThrownBy(() -> new NotificationAudienceContext(null, false))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NotificationAudienceContext(
                new LinkedHashSet<>(java.util.Arrays.asList(1L, null)), false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotificationAudienceContext(Set.of(-1L), false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotificationEvent(
                NotificationEventType.TRADE_FAILED,
                1L,
                7L,
                null,
                Instant.parse("2026-08-23T04:00:00Z"),
                "invalid-context",
                Map.of(),
                new NotificationAudienceContext(Set.of(1L), true)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private long createUser(String name) {
        String email = name + '-' + System.nanoTime() + "@example.com";
        jdbc.update("insert into user (name, email, password) values (?, ?, ?)",
                "Recovered Audience Test", email, "unused");
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    private static NotificationEvent recovered(
            String faultCycleDedupeKey,
            NotificationAudienceContext context) {
        return new NotificationEvent(
                NotificationEventType.SYSTEM_RECOVERED,
                null,
                null,
                null,
                Instant.parse("2026-08-23T04:00:00Z"),
                faultCycleDedupeKey,
                Map.of("status", "RECOVERED", "secret", "must-not-persist"),
                context);
    }
}
