package com.multind.bitpongo.scheduler;

import org.quartz.Scheduler;
import org.quartz.SchedulerException;
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
            return healthy ? Health.up().build() : Health.down().withDetail("state", "not-running").build();
        } catch (SchedulerException exception) {
            return Health.down(exception).build();
        }
    }
}
