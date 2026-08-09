package com.multind.zhitoubao.scheduler;

import java.time.Instant;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

@DisallowConcurrentExecution
public class PlanPurchaseJob implements Job {
    @Autowired private ScheduledPurchaseUseCase purchases;

    public PlanPurchaseJob() {}

    PlanPurchaseJob(ScheduledPurchaseUseCase purchases) {
        this.purchases = purchases;
    }

    @Override
    public void execute(JobExecutionContext context) {
        long planId = context.getMergedJobDataMap().getLong("planId");
        Instant scheduled = context.getScheduledFireTime() == null
                ? Instant.now() : context.getScheduledFireTime().toInstant();
        if (context.getNextFireTime() != null) {
            purchases.updateNextFireTime(planId, context.getNextFireTime().toInstant());
        }
        purchases.execute(planId, scheduled);
    }
}
