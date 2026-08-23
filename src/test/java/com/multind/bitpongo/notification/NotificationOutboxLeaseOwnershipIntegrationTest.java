package com.multind.bitpongo.notification;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(properties = {
        "zhitoubao.jwt.secret-key=outbox-lease-owner-test-secret",
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
class NotificationOutboxLeaseOwnershipIntegrationTest {

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
    private NotificationOutboxDeliveryStore deliveries;

    @Autowired
    private ApplicationContext context;

    private long userId;

    @BeforeEach
    void resetDatabase() {
        jdbc.update("delete from notification_outbox");
        jdbc.update("insert into user (name, email, password) values (?, ?, ?)",
                "Lease Owner Test",
                "lease-owner-" + System.nanoTime() + "@example.com",
                "unused");
        userId = jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    @Test
    void claimingRowsAssignsANonBlankOwnerToken() {
        long id = insertMessage(
                "lease-owner-token", "10_TIME_SENSITIVE", NOW.minusMinutes(1), 1);

        assertThat(leases.leaseDue(NOW)).hasSize(1);

        assertThat(jdbc.queryForObject(
                "select lease_token from notification_outbox where id = ?",
                String.class,
                id)).isNotBlank();
    }

    @Test
    void expiredFormerOwnerCannotRenewLoadOrCompleteAfterAnotherWorkerReclaims() {
        long id = insertMessage(
                "lease-owner-reclaimed", "10_TIME_SENSITIVE", NOW.minusMinutes(1), 1);
        NotificationOutboxLeaseService.Lease formerOwner =
                leases.leaseDue(NOW).getFirst();
        NotificationOutboxLeaseService.Lease currentOwner =
                leases.leaseDue(NOW.plusSeconds(31)).getFirst();

        assertThat(currentOwner.id()).isEqualTo(id);
        assertThat(currentOwner.token()).isNotEqualTo(formerOwner.token());
        assertThat(leases.renew(formerOwner, NOW.plusSeconds(31))).isFalse();
        assertThat(deliveries.load(formerOwner)).isEmpty();
        assertThat(deliveries.currentAttempts(formerOwner)).isEmpty();
        assertThat(deliveries.markSent(formerOwner, NOW.plusSeconds(31))).isFalse();
        assertThat(deliveries.markSkipped(formerOwner, NOW.plusSeconds(31))).isFalse();
        assertThat(deliveries.markRetry(
                formerOwner,
                1,
                NOW.plusMinutes(1),
                "stale owner",
                NOW.plusSeconds(31))).isFalse();
        assertThat(deliveries.markDead(
                formerOwner,
                10,
                "stale owner",
                NOW.plusSeconds(31))).isFalse();

        assertThat(leases.renew(currentOwner, NOW.plusSeconds(31))).isTrue();
        assertThat(deliveries.load(currentOwner)).isPresent();
        assertThat(deliveries.markSent(currentOwner, NOW.plusSeconds(32))).isTrue();
        assertThat(jdbc.queryForObject(
                "select status from notification_outbox where id = ?",
                String.class,
                id)).isEqualTo("SENT");
    }

    @Test
    void disablingScheduledTriggerKeepsCoreOutboxBeansAvailable() {
        assertThat(context.getBeansOfType(NotificationOutboxDispatchScheduler.class)).isEmpty();
        assertThat(context.getBean(NotificationOutboxDispatcher.class)).isNotNull();
        assertThat(context.getBean(NotificationOutboxLeaseService.class)).isSameAs(leases);
        assertThat(context.getBean(NotificationOutboxDeliveryStore.class)).isSameAs(deliveries);
    }

    @Test
    void mixedPrioritiesAreClaimedInPriorityThenCreationOrder() {
        long passive = insertMessage(
                "lease-priority-passive", "30_PASSIVE", NOW.minusMinutes(4), 1);
        long active = insertMessage(
                "lease-priority-active", "20_ACTIVE", NOW.minusMinutes(3), 2);
        long criticalLater = insertMessage(
                "lease-priority-critical-later", "00_CRITICAL", NOW.minusMinutes(1), 4);
        long criticalEarlier = insertMessage(
                "lease-priority-critical-earlier", "00_CRITICAL", NOW.minusMinutes(2), 3);

        List<NotificationOutboxLeaseService.Lease> claimed = leases.leaseDue(NOW);

        assertThat(claimed)
                .extracting(NotificationOutboxLeaseService.Lease::id)
                .containsExactly(criticalEarlier, criticalLater, active, passive);
    }

    private long insertMessage(
            String dedupeKey,
            String priority,
            LocalDateTime createdAt,
            int sequence) {
        jdbc.update("""
                insert into notification_outbox (
                    event_type, recipient_type, user_id, title_key, body_payload,
                    dedupe_key, priority, status, attempts, next_attempt_at,
                    lease_until, created_at, updated_at)
                values ('TRADE_FAILED', 'USER', ?, 'notification.trade_failed.title',
                    json_object('attributes', json_object('symbol', ?)),
                    ?, ?, 'PENDING', 0, ?, null, ?, ?)
                """, userId, "SYMBOL" + sequence, dedupeKey, priority,
                NOW.minusSeconds(1), createdAt, createdAt);
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }
}
