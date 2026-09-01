package com.multind.bitpongo.scheduler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.TriggerKey;
import org.quartz.Trigger;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.CronTrigger;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuartzPlanScheduleServiceTest {
    private Scheduler scheduler;
    private QuartzPlanScheduleService service;

    @BeforeEach
    void setUp() throws Exception {
        scheduler = new StdSchedulerFactory().getScheduler();
        scheduler.start();
        scheduler.clear();
        org.springframework.beans.factory.ObjectProvider<Scheduler> provider =
                new org.springframework.beans.factory.ObjectProvider<>() {
                    @Override public Scheduler getObject() { return scheduler; }
                    @Override public Scheduler getObject(java.lang.Object... args) { return scheduler; }
                    @Override public Scheduler getIfAvailable() { return scheduler; }
                    @Override public Scheduler getIfUnique() { return scheduler; }
                };
        service = new QuartzPlanScheduleService(provider, ZoneId.of("Asia/Shanghai"));
    }

    @AfterEach
    void tearDown() throws Exception { scheduler.shutdown(true); }

    @Test
    void usesStablePlanJobIdentityAndOperationsAreIdempotent() throws Exception {
        ZoneId planZone = ZoneId.of("America/New_York");
        service.schedule(42L, "0 0 8 * * ?", planZone);
        service.schedule(42L, "0 30 8 * * ?", planZone);
        assertThat(scheduler.checkExists(JobKey.jobKey("job_plan_42", "plans"))).isTrue();
        assertThat(scheduler.getJobDetail(JobKey.jobKey("job_plan_42", "plans"))
                .requestsRecovery()).isFalse();
        assertThat(scheduler.getTrigger(TriggerKey.triggerKey("trigger_plan_42", "plans"))
                .getJobKey()).isEqualTo(JobKey.jobKey("job_plan_42", "plans"));
        assertThat(((CronTrigger) scheduler.getTrigger(
                TriggerKey.triggerKey("trigger_plan_42", "plans"))).getTimeZone().getID())
                .isEqualTo("America/New_York");
        assertThat(((CronTrigger) scheduler.getTrigger(
                TriggerKey.triggerKey("trigger_plan_42", "plans"))).getMisfireInstruction())
                .isEqualTo(CronTrigger.MISFIRE_INSTRUCTION_DO_NOTHING);

        service.pause(42L);
        assertThat(scheduler.getTriggerState(TriggerKey.triggerKey("trigger_plan_42", "plans")))
                .isEqualTo(Trigger.TriggerState.PAUSED);
        service.resume(42L, "0 0 9 * * ?", planZone);
        service.remove(42L);
        assertThat(scheduler.checkExists(JobKey.jobKey("job_plan_42", "plans"))).isFalse();
    }

    @Test
    void skipsTheNewYorkSpringGapInsteadOfCreatingACatchUpFire() throws Exception {
        service.schedule(43L, "0 30 2 * * ?", ZoneId.of("America/New_York"));
        CronTrigger trigger = (CronTrigger) scheduler.getTrigger(
                TriggerKey.triggerKey("trigger_plan_43", "plans"));

        Date next = trigger.getFireTimeAfter(Date.from(Instant.parse("2027-03-14T06:00:00Z")));

        assertThat(next.toInstant()).isEqualTo(Instant.parse("2027-03-15T06:30:00Z"));
    }

    @Test
    void schedulesAtMostOneFireForTheNewYorkFallOverlap() throws Exception {
        service.schedule(44L, "0 30 1 * * ?", ZoneId.of("America/New_York"));
        CronTrigger trigger = (CronTrigger) scheduler.getTrigger(
                TriggerKey.triggerKey("trigger_plan_44", "plans"));

        Date first = trigger.getFireTimeAfter(Date.from(Instant.parse("2026-11-01T04:00:00Z")));
        Date second = trigger.getFireTimeAfter(first);

        assertThat(first.toInstant()).isIn(
                Instant.parse("2026-11-01T05:30:00Z"),
                Instant.parse("2026-11-01T06:30:00Z"));
        assertThat(second.toInstant()).isEqualTo(Instant.parse("2026-11-02T06:30:00Z"));
    }

    @Test
    void repairsAnExistingAssetSnapshotTriggerInErrorState() throws Exception {
        Scheduler broken = mock(Scheduler.class);
        JobKey jobKey = JobKey.jobKey("job_asset_snapshot", "system");
        TriggerKey triggerKey = TriggerKey.triggerKey("trigger_asset_snapshot", "system");
        when(broken.checkExists(jobKey)).thenReturn(true);
        when(broken.getTrigger(triggerKey)).thenReturn(mock(CronTrigger.class));
        when(broken.getTriggerState(triggerKey)).thenReturn(Trigger.TriggerState.ERROR);
        org.springframework.beans.factory.ObjectProvider<Scheduler> provider =
                new org.springframework.beans.factory.ObjectProvider<>() {
                    @Override public Scheduler getObject() { return broken; }
                    @Override public Scheduler getObject(java.lang.Object... args) { return broken; }
                    @Override public Scheduler getIfAvailable() { return broken; }
                    @Override public Scheduler getIfUnique() { return broken; }
                };

        new QuartzPlanScheduleService(provider, ZoneId.of("Asia/Shanghai"))
                .scheduleAssetSnapshot();

        verify(broken).rescheduleJob(
                org.mockito.ArgumentMatchers.eq(triggerKey),
                org.mockito.ArgumentMatchers.argThat(value ->
                        value.getJobKey().equals(jobKey)
                                && value instanceof CronTrigger cron
                                && cron.getCronExpression().equals("0 0 * * * ?")));
        verify(broken).triggerJob(jobKey);
    }
}
