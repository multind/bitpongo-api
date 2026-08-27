package com.multind.bitpongo.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.quartz.JobKey;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;

class PlanMisfireTriggerListenerTest {

    @Test
    void recordsOnlyPlanTriggerMisfires() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PlanMisfireTriggerListener listener = new PlanMisfireTriggerListener(new PlanExecutionMetrics(registry));
        Trigger plan = TriggerBuilder.newTrigger()
                .withIdentity("trigger_plan_7", "plans")
                .forJob(JobKey.jobKey("job_plan_7", "plans"))
                .build();
        Trigger system = TriggerBuilder.newTrigger()
                .withIdentity("trigger_asset_snapshot", "system")
                .forJob(JobKey.jobKey("job_asset_snapshot", "system"))
                .build();

        listener.triggerMisfired(plan);
        listener.triggerMisfired(system);

        assertThat(registry.get("bitpongo.plan.execution")
                .tag("result", "misfire_skipped").counter().count()).isEqualTo(1.0);
    }
}
