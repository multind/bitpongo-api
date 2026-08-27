package com.multind.bitpongo.scheduler;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanExecutionMetricsTest {

    @Test
    void recordsOnlyBoundedExecutionResults() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PlanExecutionMetrics metrics = new PlanExecutionMetrics(registry);

        metrics.record("on_time");
        metrics.record("delayed");
        metrics.record("misfire_skipped");
        metrics.record("recovery_skipped");

        for (String result : PlanExecutionMetrics.ALLOWED_RESULTS) {
            assertThat(registry.get("bitpongo.plan.execution")
                    .tag("result", result).counter().count()).isEqualTo(1);
        }
        assertThatThrownBy(() -> metrics.record("plan_42"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
