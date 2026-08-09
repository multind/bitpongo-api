package com.multind.zhitoubao.scheduler;

import java.time.Instant;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

@DisallowConcurrentExecution
public class PlanPurchaseJob implements Job {
    @Autowired private ScheduledPurchaseUseCase purchases;

    @Override
    public void execute(JobExecutionContext context) {
        long planId = context.getMergedJobDataMap().getLong("planId");
        Instant scheduled = context.getScheduledFireTime() == null
                ? Instant.now() : context.getScheduledFireTime().toInstant();
        purchases.execute(planId, scheduled);
    }
}
