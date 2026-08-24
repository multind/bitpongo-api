package com.multind.bitpongo.plan;

import com.multind.bitpongo.exchange.*;
import com.multind.bitpongo.notification.NotificationDedupeWindow;
import com.multind.bitpongo.notification.NotificationEvent;
import com.multind.bitpongo.notification.NotificationEventType;
import com.multind.bitpongo.notification.NotificationPublisher;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class AssetSnapshotServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-09T00:01:31Z");

    @Test
    void capturesEachActivePlansOwnUsdtBalanceAndContinuesAfterFailure() {
        PlanRepository plans = mock(PlanRepository.class);
        SnapshotRepository snapshots = mock(SnapshotRepository.class);
        ExchangeRepository exchanges = mock(ExchangeRepository.class);
        ExchangeGatewayRegistry gateways = mock(ExchangeGatewayRegistry.class);
        ExchangeGateway gateway = mock(ExchangeGateway.class);
        PlanEntity first = plan(1L, 7L, 3L); PlanEntity second = plan(2L, 8L, 4L);
        ExchangeEntity firstExchange = exchange(3L, 7L, "a1", "s1");
        ExchangeEntity secondExchange = exchange(4L, 8L, "a2", "s2");
        when(plans.findByStatus("active")).thenReturn(List.of(first, second));
        when(exchanges.findByIdAndUserId(3L, 7L)).thenReturn(Optional.of(firstExchange));
        when(exchanges.findByIdAndUserId(4L, 8L)).thenReturn(Optional.of(secondExchange));
        when(gateways.require("binance")).thenReturn(gateway);
        when(gateway.verifyCredentials(any())).thenReturn(
                new AccountBalance("USDT", new BigDecimal("12.30"), BigDecimal.ZERO),
                new AccountBalance("USDT", new BigDecimal("5"), BigDecimal.ZERO));

        new AssetSnapshotService(
                plans, snapshots, exchanges, gateways, null, event -> {},
                Clock.fixed(Instant.parse("2026-08-09T00:00:00Z"), ZoneOffset.UTC)).captureAll();

        verify(snapshots).save(argThat(value -> value.getPlanId() == 1L && value.getUserId() == 7L
                && value.getType().equals("asset") && value.getValue().equals("12.30")));
        verify(snapshots).save(argThat(value -> value.getPlanId() == 2L && value.getUserId() == 8L
                && value.getValue().equals("5")));
    }

    @Test
    void failedPlanPublishesOneSanitizedEventPerThirtyMinuteWindowAndContinues()
            throws Exception {
        PlanRepository plans = mock(PlanRepository.class);
        SnapshotRepository snapshots = mock(SnapshotRepository.class);
        ExchangeRepository exchanges = mock(ExchangeRepository.class);
        ExchangeGatewayRegistry gateways = mock(ExchangeGatewayRegistry.class);
        ExchangeGateway gateway = mock(ExchangeGateway.class);
        PlanEntity first = plan(1L, 7L, 3L); PlanEntity second = plan(2L, 8L, 4L);
        when(plans.findByStatus("active")).thenReturn(List.of(first, second));
        when(exchanges.findByIdAndUserId(3L, 7L))
                .thenReturn(Optional.of(exchange(3L, 7L, "a1", "s1")));
        when(exchanges.findByIdAndUserId(4L, 8L))
                .thenReturn(Optional.of(exchange(4L, 8L, "a2", "s2")));
        when(gateways.require("binance")).thenReturn(gateway);
        when(gateway.verifyCredentials(any()))
                .thenThrow(new IllegalStateException(
                        "GET https://private.example/account token=fake-token"))
                .thenReturn(new AccountBalance("USDT", new BigDecimal("5"), BigDecimal.ZERO))
                .thenThrow(new IllegalStateException(
                        "GET https://private.example/account token=fake-token"))
                .thenReturn(new AccountBalance("USDT", new BigDecimal("5"), BigDecimal.ZERO));
        CollectingNotificationPublisher notifications = new CollectingNotificationPublisher();
        AssetSnapshotService service = new AssetSnapshotService(
                plans, snapshots, exchanges, gateways, null, notifications, Clock.fixed(NOW, ZoneOffset.UTC));

        service.captureAll();
        service.captureAll();

        verify(snapshots, times(2)).save(argThat(value -> value.getPlanId() == 2L));
        assertThat(notifications.events()).hasSize(2).allSatisfy(event -> {
            assertThat(event.type()).isEqualTo(NotificationEventType.ASSET_SNAPSHOT_FAILED);
            assertThat(event.userId()).isEqualTo(7L);
            assertThat(event.planId()).isEqualTo(1L);
            assertThat(event.intentId()).isNull();
            assertThat(event.occurredAt()).isEqualTo(NOW);
            assertThat(event.dedupeKey()).isEqualTo("asset-snapshot-failed:1");
            assertThat(event.dedupeWindow()).isEqualTo(new NotificationDedupeWindow(
                    "asset-snapshot-failed:1", Duration.ofMinutes(30)));
            assertThat(event.attributes()).containsEntry("status", "ASSET_SNAPSHOT_FAILED");
            assertThat(event.attributes().get("errorSummary").toString())
                    .contains("<redacted-uri>", "token=<redacted>")
                    .doesNotContain("private.example", "fake-token");
        });
    }

    @Test
    void notificationFailureDoesNotInterruptRemainingPlans() throws Exception {
        PlanRepository plans = mock(PlanRepository.class);
        SnapshotRepository snapshots = mock(SnapshotRepository.class);
        ExchangeRepository exchanges = mock(ExchangeRepository.class);
        ExchangeGatewayRegistry gateways = mock(ExchangeGatewayRegistry.class);
        ExchangeGateway gateway = mock(ExchangeGateway.class);
        PlanEntity first = plan(1L, 7L, 3L); PlanEntity second = plan(2L, 8L, 4L);
        when(plans.findByStatus("active")).thenReturn(List.of(first, second));
        when(exchanges.findByIdAndUserId(3L, 7L))
                .thenReturn(Optional.of(exchange(3L, 7L, "a1", "s1")));
        when(exchanges.findByIdAndUserId(4L, 8L))
                .thenReturn(Optional.of(exchange(4L, 8L, "a2", "s2")));
        when(gateways.require("binance")).thenReturn(gateway);
        when(gateway.verifyCredentials(any()))
                .thenThrow(new IllegalStateException("snapshot failed"))
                .thenReturn(new AccountBalance("USDT", new BigDecimal("5"), BigDecimal.ZERO));
        CollectingNotificationPublisher notifications = new CollectingNotificationPublisher();
        notifications.failPublishing = true;
        AssetSnapshotService service = new AssetSnapshotService(
                plans, snapshots, exchanges, gateways, null, notifications, Clock.fixed(NOW, ZoneOffset.UTC));

        assertDoesNotThrow(service::captureAll);

        assertThat(notifications.attempted).isEqualTo(1);
        verify(snapshots).save(argThat(value -> value.getPlanId() == 2L));
    }


    private static final class CollectingNotificationPublisher implements NotificationPublisher {
        private final List<NotificationEvent> events = new ArrayList<>();
        private int attempted;
        private boolean failPublishing;

        @Override
        public void publish(NotificationEvent event) {
            attempted++;
            if (failPublishing) throw new RuntimeException("notification unavailable");
            events.add(event);
        }

        List<NotificationEvent> events() { return List.copyOf(events); }
    }

    private PlanEntity plan(long id, long user, long exchange) {
        PlanEntity plan = new PlanEntity(); plan.setId(id); plan.setUserId(user); plan.setExchangeId(exchange); return plan;
    }
    private ExchangeEntity exchange(long id, long user, String access, String secret) {
        ExchangeEntity value = new ExchangeEntity(); value.setId(id); value.setUserId(user);
        value.setExchange("binance"); value.setAccessKey(access); value.setSecretKey(secret); return value;
    }
}
