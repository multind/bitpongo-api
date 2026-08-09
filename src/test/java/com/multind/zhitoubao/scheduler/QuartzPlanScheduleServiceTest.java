package com.multind.zhitoubao.scheduler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.TriggerKey;
import org.quartz.Trigger;
import org.quartz.impl.StdSchedulerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class QuartzPlanScheduleServiceTest {
    private Scheduler scheduler;
    private QuartzPlanScheduleService service;

    @BeforeEach
    void setUp() throws Exception {
        scheduler = new StdSchedulerFactory().getScheduler();
        scheduler.start();
        scheduler.clear();
        service = new QuartzPlanScheduleService(scheduler);
    }

    @AfterEach
    void tearDown() throws Exception { scheduler.shutdown(true); }

    @Test
    void usesStablePlanJobIdentityAndOperationsAreIdempotent() throws Exception {
        service.schedule(42L, "0 0 8 * * ?");
        service.schedule(42L, "0 30 8 * * ?");
        assertThat(scheduler.checkExists(JobKey.jobKey("job_plan_42", "plans"))).isTrue();
        assertThat(scheduler.getTrigger(TriggerKey.triggerKey("trigger_plan_42", "plans"))
                .getJobKey()).isEqualTo(JobKey.jobKey("job_plan_42", "plans"));

        service.pause(42L);
        assertThat(scheduler.getTriggerState(TriggerKey.triggerKey("trigger_plan_42", "plans")))
                .isEqualTo(Trigger.TriggerState.PAUSED);
        service.resume(42L, "0 0 9 * * ?");
        service.remove(42L);
        assertThat(scheduler.checkExists(JobKey.jobKey("job_plan_42", "plans"))).isFalse();
    }
}
