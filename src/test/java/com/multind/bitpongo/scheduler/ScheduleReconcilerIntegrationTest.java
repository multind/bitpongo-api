package com.multind.bitpongo.scheduler;

import com.multind.bitpongo.notification.NotificationDedupeWindow;
import com.multind.bitpongo.notification.NotificationEvent;
import com.multind.bitpongo.notification.NotificationEventType;
import com.multind.bitpongo.notification.NotificationPublisher;
import com.multind.bitpongo.plan.PlanEntity;
import com.multind.bitpongo.plan.PlanRepository;
import com.multind.bitpongo.strategy.StrategyEntity;
import com.multind.bitpongo.strategy.StrategyRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduleReconcilerIntegrationTest {
    @Test
    void restoresOnlyActivePlansWithoutBackfillingOldExecutions() {
        PlanRepository plans = mock(PlanRepository.class);
        StrategyRepository strategies = mock(StrategyRepository.class);
        PlanScheduleService schedules = mock(PlanScheduleService.class);
        ObjectProvider<PlanScheduleService> schedulesProvider = mock(ObjectProvider.class);
        ObjectProvider<PlanRepository> plansProvider = mock(ObjectProvider.class);
        ObjectProvider<StrategyRepository> strategiesProvider = mock(ObjectProvider.class);
        when(schedulesProvider.getIfAvailable()).thenReturn(schedules);
        when(plansProvider.getIfAvailable()).thenReturn(plans);
        when(strategiesProvider.getIfAvailable()).thenReturn(strategies);
        PlanEntity active = plan(1L, "active", 11L);
        PlanEntity stopped = plan(2L, "stop", 12L);
        StrategyEntity strategy = new StrategyEntity(); strategy.setId(11L); strategy.setCron("0 8 * * *");
        strategy.setScheduleTimezone("America/New_York");
        when(plans.findAll()).thenReturn(List.of(active, stopped));
        when(strategies.findById(11L)).thenReturn(Optional.of(strategy));

        new ScheduleReconciler(
                plansProvider, strategiesProvider, schedulesProvider, event -> {}).reconcile();

        verify(schedules).schedule(1L, "0 0 8 * * ?", ZoneId.of("America/New_York"));
        verify(schedules).remove(2L);
        verify(schedules, never()).resume(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void planRegistrationFailurePublishesOneSanitizedEventPerTenMinuteTaskWindow()
            throws Exception {
        PlanRepository plans = mock(PlanRepository.class);
        StrategyRepository strategies = mock(StrategyRepository.class);
        PlanScheduleService schedules = mock(PlanScheduleService.class);
        ObjectProvider<PlanScheduleService> schedulesProvider = provider(schedules);
        ObjectProvider<PlanRepository> plansProvider = provider(plans);
        ObjectProvider<StrategyRepository> strategiesProvider = provider(strategies);
        PlanEntity first = plan(1L, "active", 11L); first.setUserId(7L);
        PlanEntity second = plan(2L, "active", 12L); second.setUserId(8L);
        StrategyEntity one = new StrategyEntity(); one.setId(11L); one.setCron("0 8 * * *");
        one.setScheduleTimezone("Asia/Shanghai");
        StrategyEntity two = new StrategyEntity(); two.setId(12L); two.setCron("0 9 * * *");
        two.setScheduleTimezone("America/New_York");
        when(plans.findAll()).thenReturn(List.of(first, second));
        when(strategies.findById(11L)).thenReturn(Optional.of(one));
        when(strategies.findById(12L)).thenReturn(Optional.of(two));
        doThrow(new IllegalStateException(
                "POST https://private.example/key secret_key=fake-secret"))
                .when(schedules).schedule(eq(1L), eq("0 0 8 * * ?"), eq(ZoneId.of("Asia/Shanghai")));
        CollectingNotificationPublisher notifications = new CollectingNotificationPublisher();
        ScheduleReconciler reconciler = new ScheduleReconciler(
                plansProvider, strategiesProvider, schedulesProvider, notifications,
                Clock.fixed(Instant.parse("2026-08-09T00:01:31Z"), ZoneOffset.UTC));

        reconciler.reconcile();
        reconciler.reconcile();

        verify(schedules, times(2)).schedule(
                2L, "0 0 9 * * ?", ZoneId.of("America/New_York"));
        assertThat(notifications.events()).hasSize(2).allSatisfy(event -> {
            assertThat(event.type()).isEqualTo(NotificationEventType.SCHEDULER_FATAL);
            assertThat(event.userId()).isEqualTo(7L);
            assertThat(event.planId()).isEqualTo(1L);
            assertThat(event.intentId()).isNull();
            assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-08-09T00:01:31Z"));
            assertThat(event.dedupeKey())
                    .isEqualTo("scheduler-fatal:plan-reconcile:1");
            assertThat(event.dedupeWindow()).isEqualTo(new NotificationDedupeWindow(
                    "scheduler-fatal:plan-reconcile:1", Duration.ofMinutes(10)));
            assertThat(event.attributes()).containsEntry("status", "PLAN_REGISTRATION_FAILED");
            assertThat(event.attributes().get("errorSummary").toString())
                    .contains("<redacted-uri>", "secret_key=<redacted>")
                    .doesNotContain("private.example", "fake-secret");
        });
    }

    @Test
    void notificationFailureDoesNotStopRemainingPlanReconciliation() throws Exception {
        PlanRepository plans = mock(PlanRepository.class);
        StrategyRepository strategies = mock(StrategyRepository.class);
        PlanScheduleService schedules = mock(PlanScheduleService.class);
        PlanEntity first = plan(1L, "active", 11L);
        PlanEntity second = plan(2L, "active", 12L);
        StrategyEntity one = new StrategyEntity(); one.setCron("0 8 * * *");
        one.setScheduleTimezone("Asia/Shanghai");
        StrategyEntity two = new StrategyEntity(); two.setCron("0 9 * * *");
        two.setScheduleTimezone("Asia/Tokyo");
        when(plans.findAll()).thenReturn(List.of(first, second));
        when(strategies.findById(11L)).thenReturn(Optional.of(one));
        when(strategies.findById(12L)).thenReturn(Optional.of(two));
        doThrow(new IllegalStateException("schedule failed"))
                .when(schedules).schedule(eq(1L), eq("0 0 8 * * ?"), eq(ZoneId.of("Asia/Shanghai")));
        CollectingNotificationPublisher notifications = new CollectingNotificationPublisher();
        notifications.failPublishing = true;
        ScheduleReconciler reconciler = new ScheduleReconciler(
                provider(plans), provider(strategies), provider(schedules), notifications);

        assertDoesNotThrow(reconciler::reconcile);

        assertThat(notifications.attempted).isEqualTo(1);
        verify(schedules).schedule(2L, "0 0 9 * * ?", ZoneId.of("Asia/Tokyo"));
    }

    @Test
    void assetSnapshotRegistrationFailurePublishesFatalEvent() throws Exception {
        PlanRepository plans = mock(PlanRepository.class);
        StrategyRepository strategies = mock(StrategyRepository.class);
        QuartzPlanScheduleService schedules = mock(QuartzPlanScheduleService.class);
        when(plans.findAll()).thenReturn(List.of());
        doThrow(new IllegalStateException("snapshot scheduler failed"))
                .when(schedules).scheduleAssetSnapshot();
        CollectingNotificationPublisher notifications = new CollectingNotificationPublisher();
        ScheduleReconciler reconciler = new ScheduleReconciler(
                provider(plans), provider(strategies), provider(schedules), notifications,
                Clock.fixed(Instant.parse("2026-08-09T00:01:31Z"), ZoneOffset.UTC));

        reconciler.reconcile();

        assertThat(notifications.events()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(NotificationEventType.SCHEDULER_FATAL);
            assertThat(event.userId()).isNull();
            assertThat(event.planId()).isNull();
            assertThat(event.dedupeKey())
                    .isEqualTo("scheduler-fatal:asset-snapshot-registration");
            assertThat(event.dedupeWindow()).isEqualTo(new NotificationDedupeWindow(
                    "scheduler-fatal:asset-snapshot-registration", Duration.ofMinutes(10)));
            assertThat(event.attributes())
                    .containsEntry("status", "ASSET_SNAPSHOT_REGISTRATION_FAILED");
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
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

    private PlanEntity plan(long id, String status, long strategyId) {
        PlanEntity plan = new PlanEntity(); plan.setId(id); plan.setStatus(status); plan.setStrategyId(strategyId); return plan;
    }
}
