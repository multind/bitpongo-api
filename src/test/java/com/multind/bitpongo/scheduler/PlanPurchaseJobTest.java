package com.multind.bitpongo.scheduler;

import com.multind.bitpongo.notification.NotificationEvent;
import com.multind.bitpongo.notification.NotificationEventType;
import com.multind.bitpongo.notification.NotificationPublisher;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanPurchaseJobTest {
    @Test
    void publishesSanitizedFatalEventAndPreservesQuartzFailureSemantics() throws Exception {
        ScheduledPurchaseUseCase purchases = mock(ScheduledPurchaseUseCase.class);
        CollectingNotificationPublisher notifications = new CollectingNotificationPublisher();
        JobExecutionContext context = mock(JobExecutionContext.class);
        JobDataMap data = new JobDataMap();
        data.put("planId", 42L);
        Instant scheduled = Instant.parse("2026-08-09T00:00:00Z");
        Instant next = Instant.parse("2026-08-10T00:00:00Z");
        when(context.getMergedJobDataMap()).thenReturn(data);
        when(context.getScheduledFireTime()).thenReturn(Date.from(scheduled));
        when(context.getNextFireTime()).thenReturn(Date.from(next));
        IllegalStateException failure = new IllegalStateException(
                "POST https://private.example/device-key accessKey=fake-access");
        org.mockito.Mockito.doThrow(failure)
                .when(purchases).execute(42L, scheduled);
        PlanPurchaseJob job = new PlanPurchaseJob(purchases);
        injectIfPresent(job, "notifications", notifications);
        injectIfPresent(job, "clock", Clock.fixed(scheduled.plusSeconds(91), ZoneOffset.UTC));

        assertThatThrownBy(() -> job.execute(context))
                .isInstanceOf(JobExecutionException.class)
                .hasCause(failure)
                .satisfies(error -> assertThat(((JobExecutionException) error).refireImmediately())
                        .isFalse());

        InOrder order = inOrder(purchases);
        order.verify(purchases).updateNextFireTime(42L, next);
        order.verify(purchases).execute(42L, scheduled);
        assertThat(notifications.events()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(NotificationEventType.SCHEDULER_FATAL);
            assertThat(event.userId()).isNull();
            assertThat(event.planId()).isEqualTo(42L);
            assertThat(event.intentId()).isNull();
            assertThat(event.occurredAt()).isEqualTo(scheduled.plusSeconds(91));
            assertThat(event.dedupeKey()).isEqualTo("scheduler-fatal:plan-purchase:42:2977056");
            assertThat(event.attributes()).containsEntry("status", "PLAN_PURCHASE_FAILED");
            assertThat(event.attributes().get("errorSummary").toString())
                    .contains("<redacted-uri>", "accessKey=<redacted>")
                    .doesNotContain("private.example", "device-key", "fake-access");
        });
    }

    @Test
    void notificationFailureDoesNotReplaceOriginalQuartzFailure() throws Exception {
        ScheduledPurchaseUseCase purchases = mock(ScheduledPurchaseUseCase.class);
        CollectingNotificationPublisher notifications = new CollectingNotificationPublisher();
        notifications.failPublishing = true;
        JobExecutionContext context = mock(JobExecutionContext.class);
        JobDataMap data = new JobDataMap();
        data.put("planId", 42L);
        Instant scheduled = Instant.parse("2026-08-09T00:00:00Z");
        when(context.getMergedJobDataMap()).thenReturn(data);
        when(context.getScheduledFireTime()).thenReturn(Date.from(scheduled));
        IllegalStateException failure = new IllegalStateException("purchase failed");
        org.mockito.Mockito.doThrow(failure).when(purchases).execute(42L, scheduled);
        PlanPurchaseJob job = new PlanPurchaseJob(purchases);
        injectIfPresent(job, "notifications", notifications);

        assertThatThrownBy(() -> job.execute(context))
                .isInstanceOf(JobExecutionException.class)
                .hasCause(failure);
        assertThat(notifications.attempted).isEqualTo(1);
    }

    private static void injectIfPresent(Object target, String name, Object value) throws Exception {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (NoSuchFieldException ignored) {
            // Keeps RED focused on missing behavior before the dependency exists.
        }
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

        List<NotificationEvent> events() {
            return List.copyOf(events);
        }
    }
}
