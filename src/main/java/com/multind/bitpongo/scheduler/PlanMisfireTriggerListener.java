package com.multind.bitpongo.scheduler;

import org.quartz.Trigger;
import org.quartz.listeners.TriggerListenerSupport;
import org.springframework.stereotype.Component;

@Component
public class PlanMisfireTriggerListener extends TriggerListenerSupport {

    private final PlanExecutionMetrics metrics;

    public PlanMisfireTriggerListener(PlanExecutionMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public String getName() {
        return "plan-misfire-metrics";
    }

    @Override
    public void triggerMisfired(Trigger trigger) {
        if ("plans".equals(trigger.getJobKey().getGroup())) {
            metrics.record("misfire_skipped");
        }
    }
}
