package com.multind.bitpongo.scheduler;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

@DisallowConcurrentExecution
public class PlanPurchaseJob implements Job {
    private static final Logger log = LoggerFactory.getLogger(PlanPurchaseJob.class);
    @Autowired private ScheduledPurchaseUseCase purchases;

    public PlanPurchaseJob() {}

    PlanPurchaseJob(ScheduledPurchaseUseCase purchases) {
        this.purchases = purchases;
    }

    @Override
    public void execute(JobExecutionContext context) {
        long planId = context.getMergedJobDataMap().getLong("planId");
        log.info("计划任务触发 planId={} scheduledFireTime={} nextFireTime={}",
                planId, context.getScheduledFireTime(), context.getNextFireTime());
        Instant scheduled = context.getScheduledFireTime() == null
                ? Instant.now() : context.getScheduledFireTime().toInstant();
        if (context.getNextFireTime() != null) {
            purchases.updateNextFireTime(planId, context.getNextFireTime().toInstant());
        }
        purchases.execute(planId, scheduled);
    }
}
