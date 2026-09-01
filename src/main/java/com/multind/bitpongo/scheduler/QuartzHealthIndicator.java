package com.multind.bitpongo.scheduler;

import java.util.List;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("quartzSchedulerHealth")
@ConditionalOnBean(Scheduler.class)
public class QuartzHealthIndicator implements HealthIndicator {
    private final Scheduler scheduler;
    public QuartzHealthIndicator(Scheduler scheduler) { this.scheduler = scheduler; }

    @Override
    public Health health() {
        try {
            boolean healthy = scheduler.isStarted() && !scheduler.isInStandbyMode() && !scheduler.isShutdown();
            if (!healthy) return Health.down().withDetail("state", "not-running").build();
            List<String> errorTriggers = scheduler
                    .getTriggerKeys(GroupMatcher.anyTriggerGroup()).stream()
                    .filter(this::isError)
                    .map(TriggerKey::toString)
                    .sorted()
                    .toList();
            if (!errorTriggers.isEmpty()) {
                return Health.down()
                        .withDetail("errorTriggers", String.join(",", errorTriggers))
                        .build();
            }
            return Health.up().build();
        } catch (SchedulerException exception) {
            return Health.down(exception).build();
        }
    }

    private boolean isError(TriggerKey key) {
        try {
            return scheduler.getTriggerState(key) == Trigger.TriggerState.ERROR;
        } catch (SchedulerException exception) {
            return true;
        }
    }
}
