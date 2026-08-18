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
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnBean(Scheduler.class)
public class QuartzPlanScheduleService implements PlanScheduleService {
    private static final String GROUP = "plans";
    private final Scheduler scheduler;
    private final TimeZone timeZone;

    @Autowired
    public QuartzPlanScheduleService(
            Scheduler scheduler,
            @Value("${zhitoubao.scheduling-zone:Asia/Shanghai}") String schedulingZone) {
        this(scheduler, ZoneId.of(schedulingZone));
    }

    QuartzPlanScheduleService(Scheduler scheduler, ZoneId schedulingZone) {
        this.scheduler = scheduler;
        this.timeZone = TimeZone.getTimeZone(schedulingZone);
    }

    @Override
    public void schedule(long planId, String cron) {
        try {
            JobKey jobKey = jobKey(planId);
            TriggerKey triggerKey = triggerKey(planId);
            CronTrigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity(triggerKey)
                    .forJob(jobKey)
                    .withSchedule(CronScheduleBuilder.cronSchedule(cron)
                            .inTimeZone(timeZone)
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
                    .requestRecovery(true)
                    .build();
            scheduler.scheduleJob(job, trigger);
        } catch (SchedulerException | RuntimeException exception) {
            throw new IllegalStateException("计划任务调度失败: " + planId, exception);
        }
    }

    @Override
    public void pause(long planId) {
        run(() -> scheduler.pauseJob(jobKey(planId)), planId);
    }

    @Override
    public void resume(long planId, String cron) {
        schedule(planId, cron);
        run(() -> scheduler.resumeJob(jobKey(planId)), planId);
    }

    @Override
    public void remove(long planId) {
        run(() -> scheduler.deleteJob(jobKey(planId)), planId);
    }

    public void scheduleAssetSnapshot() {
        try {
            JobKey key = JobKey.jobKey("job_asset_snapshot", "system");
            if (scheduler.checkExists(key)) return;
            JobDetail job = JobBuilder.newJob(AssetSnapshotJob.class).withIdentity(key).build();
            CronTrigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity("trigger_asset_snapshot", "system")
                    .withSchedule(CronScheduleBuilder.cronSchedule("0 0 * * * ?")
                            .inTimeZone(timeZone)
                            .withMisfireHandlingInstructionDoNothing())
                    .build();
            scheduler.scheduleJob(job, trigger);
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
