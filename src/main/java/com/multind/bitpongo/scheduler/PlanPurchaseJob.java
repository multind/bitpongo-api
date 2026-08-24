package com.multind.bitpongo.scheduler;

import com.multind.bitpongo.notification.NotificationEvent;
import com.multind.bitpongo.notification.NotificationEventType;
import com.multind.bitpongo.notification.NotificationMessageRenderer;
import com.multind.bitpongo.notification.NotificationPublisher;
import java.time.Clock;
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
    private Clock clock = Clock.systemUTC();

    public PlanPurchaseJob() {}

    PlanPurchaseJob(ScheduledPurchaseUseCase purchases) {
        this.purchases = purchases;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        long planId = context.getMergedJobDataMap().getLong("planId");
        log.info("计划任务触发 planId={} scheduledFireTime={} nextFireTime={}",
                planId, context.getScheduledFireTime(), context.getNextFireTime());
        Instant scheduled = context.getScheduledFireTime() == null
                ? Instant.now() : context.getScheduledFireTime().toInstant();
        try {
            if (context.getNextFireTime() != null) {
                purchases.updateNextFireTime(planId, context.getNextFireTime().toInstant());
            }
            purchases.execute(planId, scheduled);
        } catch (RuntimeException failure) {
            publishFailure(planId, failure);
            throw new JobExecutionException(failure, false);
        }
    }

    private void publishFailure(long planId, RuntimeException failure) {
        Instant occurredAt = clock.instant();
        NotificationEvent event = new NotificationEvent(
                NotificationEventType.SCHEDULER_FATAL,
                null,
                planId,
                null,
                occurredAt,
                "scheduler-fatal:plan-purchase:" + planId + ":"
                        + occurredAt.getEpochSecond() / 600,
                Map.of(
                        "status", "PLAN_PURCHASE_FAILED",
                        "errorSummary", NotificationMessageRenderer.sanitizeError(
                                failure.getMessage() == null
                                        ? failure.getClass().getSimpleName()
                                        : failure.getMessage())));
        try {
            notifications.publish(event);
        } catch (RuntimeException notificationFailure) {
            log.warn("计划任务失败通知发布失败 planId={} errorType={}",
                    planId, notificationFailure.getClass().getSimpleName());
        }
    }
}
