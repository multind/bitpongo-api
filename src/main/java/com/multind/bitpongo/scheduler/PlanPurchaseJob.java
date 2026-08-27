package com.multind.bitpongo.scheduler;

import com.multind.bitpongo.notification.NotificationDedupeWindow;
import com.multind.bitpongo.notification.NotificationEvent;
import com.multind.bitpongo.notification.NotificationEventType;
import com.multind.bitpongo.notification.NotificationMessageRenderer;
import com.multind.bitpongo.notification.NotificationPublisher;
import com.multind.bitpongo.plan.PlanRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Autowired;

@DisallowConcurrentExecution
public class PlanPurchaseJob implements Job {
    private static final Logger log = LoggerFactory.getLogger(PlanPurchaseJob.class);
    @Autowired private ScheduledPurchaseUseCase purchases;
    @Autowired private NotificationPublisher notifications;
    @Autowired private PlanExecutionMetrics metrics;
    @Autowired private PlanRepository plans;
    private Clock clock = Clock.systemUTC();

    public PlanPurchaseJob() {}

    PlanPurchaseJob(ScheduledPurchaseUseCase purchases) {
        this.purchases = purchases;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        long planId = context.getMergedJobDataMap().getLong("planId");
        Instant actualStartedAt = clock.instant();
        Instant scheduled = context.getScheduledFireTime() == null
                ? actualStartedAt : context.getScheduledFireTime().toInstant();
        long delayMs = Math.max(0L, Duration.between(scheduled, actualStartedAt).toMillis());
        Instant nextFireAt = context.getNextFireTime() == null
                ? null : context.getNextFireTime().toInstant();
        boolean recovering = context.isRecovering();
        log.info("计划任务触发 planId={} scheduledAt={} actualStartedAt={} delayMs={} "
                        + "recovering={} nextFireAt={}",
                planId, scheduled, actualStartedAt, delayMs, recovering, nextFireAt);
        if (recovering) {
            recordMetric("recovery_skipped");
            publishRecoverySkipped(planId, scheduled, actualStartedAt);
            return;
        }
        recordMetric(delayMs > 1000 ? "delayed" : "on_time");
        try {
            if (nextFireAt != null) {
                purchases.updateNextFireTime(planId, nextFireAt);
            }
            purchases.execute(planId, scheduled);
        } catch (RuntimeException failure) {
            publishFailure(planId, scheduled, failure);
            throw new JobExecutionException(failure, false);
        }
    }

    private void recordMetric(String result) {
        if (metrics != null) {
            metrics.record(result);
        }
    }

    private void publishRecoverySkipped(long planId, Instant scheduledAt, Instant occurredAt) {
        Long userId = plans == null ? null : plans.findById(planId)
                .map(plan -> plan.getUserId())
                .orElse(null);
        NotificationEvent event = new NotificationEvent(
                NotificationEventType.PLAN_EXECUTION_SKIPPED,
                userId,
                planId,
                null,
                scheduledAt,
                occurredAt,
                "plan-execution-skipped:recovery:" + planId + ":" + scheduledAt,
                Map.of("status", "RECOVERY_SKIPPED"));
        try {
            notifications.publish(event);
        } catch (RuntimeException notificationFailure) {
            log.warn("恢复补单跳过通知发布失败 planId={} errorType={}",
                    planId, notificationFailure.getClass().getSimpleName());
        }
    }

    private void publishFailure(long planId, Instant scheduledAt, RuntimeException failure) {
        Instant occurredAt = clock.instant();
        NotificationEvent event = new NotificationEvent(
                NotificationEventType.SCHEDULER_FATAL,
                null,
                planId,
                null,
                scheduledAt,
                occurredAt,
                "scheduler-fatal:plan-purchase:" + planId,
                Map.of(
                        "status", "PLAN_PURCHASE_FAILED",
                        "errorSummary", NotificationMessageRenderer.sanitizeError(
                                failure.getMessage() == null
                                        ? failure.getClass().getSimpleName()
                                        : failure.getMessage())),
                null,
                new NotificationDedupeWindow(
                        "scheduler-fatal:plan-purchase:" + planId, Duration.ofMinutes(10)));
        try {
            notifications.publish(event);
        } catch (RuntimeException notificationFailure) {
            log.warn("计划任务失败通知发布失败 planId={} errorType={}",
                    planId, notificationFailure.getClass().getSimpleName());
        }
    }
}
