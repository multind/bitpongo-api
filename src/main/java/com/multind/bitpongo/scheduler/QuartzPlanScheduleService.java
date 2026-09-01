package com.multind.bitpongo.scheduler;

import java.time.ZoneId;
import java.util.TimeZone;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class QuartzPlanScheduleService implements PlanScheduleService {
    private static final Logger log = LoggerFactory.getLogger(QuartzPlanScheduleService.class);
    private static final String GROUP = "plans";
    private final ObjectProvider<Scheduler> schedulerProvider;
    private final TimeZone timeZone;

    @Autowired
    public QuartzPlanScheduleService(
            ObjectProvider<Scheduler> schedulerProvider,
            @Value("${zhitoubao.scheduling-zone:Asia/Shanghai}") String schedulingZone) {
        this(schedulerProvider, ZoneId.of(schedulingZone));
    }

    QuartzPlanScheduleService(ObjectProvider<Scheduler> schedulerProvider, ZoneId schedulingZone) {
        this.schedulerProvider = schedulerProvider;
        this.timeZone = TimeZone.getTimeZone(schedulingZone);
    }

    private Scheduler requireScheduler(long planId) {
        Scheduler scheduler = schedulerProvider.getIfAvailable();
        if (scheduler == null) {
            throw new IllegalStateException("Quartz 调度器未就绪，无法注册计划任务: " + planId);
        }
        return scheduler;
    }

    @Override
    public void schedule(long planId, String cron, ZoneId zone) {
        Scheduler scheduler = requireScheduler(planId);
        try {
            log.info("注册计划触发器 planId={} cron={} scheduleZone={}", planId, cron, zone);
            JobKey jobKey = jobKey(planId);
            TriggerKey triggerKey = triggerKey(planId);
            CronTrigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity(triggerKey)
                    .forJob(jobKey)
                    .withSchedule(CronScheduleBuilder.cronSchedule(cron)
                            .inTimeZone(TimeZone.getTimeZone(zone))
                            .withMisfireHandlingInstructionDoNothing())
                    .build();
            if (scheduler.checkExists(jobKey)) {
                if (scheduler.checkExists(triggerKey)) scheduler.rescheduleJob(triggerKey, trigger);
                else scheduler.scheduleJob(trigger);
                return;
            }
            JobDetail job = JobBuilder.newJob(PlanPurchaseJob.class)
                    .withIdentity(jobKey)
                    .usingJobData("planId", planId)
                    .build();
            scheduler.scheduleJob(job, trigger);
        } catch (SchedulerException | RuntimeException exception) {
            throw new IllegalStateException("计划任务调度失败: " + planId, exception);
        }
    }

    @Override
    public void pause(long planId) {
        Scheduler scheduler = requireScheduler(planId);
        run(() -> scheduler.pauseJob(jobKey(planId)), planId);
    }

    @Override
    public void resume(long planId, String cron, ZoneId zone) {
        schedule(planId, cron, zone);
        Scheduler scheduler = requireScheduler(planId);
        run(() -> scheduler.resumeJob(jobKey(planId)), planId);
    }

    @Override
    public void remove(long planId) {
        Scheduler scheduler = requireScheduler(planId);
        run(() -> scheduler.deleteJob(jobKey(planId)), planId);
    }

    public void scheduleAssetSnapshot() {
        Scheduler scheduler = schedulerProvider.getIfAvailable();
        if (scheduler == null) return;
        try {
            JobKey key = JobKey.jobKey("job_asset_snapshot", "system");
            TriggerKey triggerKey = TriggerKey.triggerKey("trigger_asset_snapshot", "system");
            boolean jobExists = scheduler.checkExists(key);
            Trigger existingTrigger = scheduler.getTrigger(triggerKey);
            Trigger.TriggerState triggerState = existingTrigger == null
                    ? Trigger.TriggerState.NONE : scheduler.getTriggerState(triggerKey);
            if (jobExists && existingTrigger != null
                    && triggerState != Trigger.TriggerState.ERROR
                    && triggerState != Trigger.TriggerState.COMPLETE
                    && triggerState != Trigger.TriggerState.NONE) {
                return;
            }
            JobDetail job = JobBuilder.newJob(AssetSnapshotJob.class).withIdentity(key).build();
            CronTrigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity(triggerKey)
                    .forJob(key)
                    .withSchedule(CronScheduleBuilder.cronSchedule("0 0 * * * ?")
                            .inTimeZone(timeZone)
                            .withMisfireHandlingInstructionDoNothing())
                    .build();
            if (!jobExists) {
                scheduler.scheduleJob(job, trigger);
            } else if (existingTrigger == null) {
                scheduler.scheduleJob(trigger);
            } else {
                log.warn("重建异常资产快照触发器，原状态={}", triggerState);
                scheduler.rescheduleJob(triggerKey, trigger);
            }
            scheduler.triggerJob(key);
        } catch (SchedulerException exception) {
            throw new IllegalStateException("资产快照任务调度失败", exception);
        }
    }

    private void run(SchedulerAction action, long planId) {
        try { action.run(); }
        catch (SchedulerException exception) {
            throw new IllegalStateException("计划任务调度失败: " + planId, exception);
        }
    }

    private static JobKey jobKey(long planId) { return JobKey.jobKey("job_plan_" + planId, GROUP); }
    private static TriggerKey triggerKey(long planId) {
        return TriggerKey.triggerKey("trigger_plan_" + planId, GROUP);
    }
    @FunctionalInterface private interface SchedulerAction { void run() throws SchedulerException; }
}
