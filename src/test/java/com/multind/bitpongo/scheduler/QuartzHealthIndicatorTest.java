package com.multind.bitpongo.scheduler;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.boot.health.contributor.Health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuartzHealthIndicatorTest {

    @Test
    void reportsDownWhenAnyQuartzTriggerIsInError() throws Exception {
        Scheduler scheduler = mock(Scheduler.class);
        TriggerKey broken = TriggerKey.triggerKey("trigger_asset_snapshot", "system");
        when(scheduler.isStarted()).thenReturn(true);
        when(scheduler.isInStandbyMode()).thenReturn(false);
        when(scheduler.isShutdown()).thenReturn(false);
        when(scheduler.getTriggerKeys(any(GroupMatcher.class))).thenReturn(Set.of(broken));
        when(scheduler.getTriggerState(broken)).thenReturn(Trigger.TriggerState.ERROR);

        Health health = new QuartzHealthIndicator(scheduler).health();

        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(health.getDetails()).containsEntry(
                "errorTriggers", "system.trigger_asset_snapshot");
    }
}
