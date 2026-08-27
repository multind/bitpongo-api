package com.multind.bitpongo.scheduler;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class PlanExecutionMetrics {

    public static final Set<String> ALLOWED_RESULTS = Set.of(
            "on_time", "delayed", "misfire_skipped", "recovery_skipped");

    private final Map<String, Counter> counters;

    public PlanExecutionMetrics(MeterRegistry registry) {
        counters = ALLOWED_RESULTS.stream().collect(Collectors.toUnmodifiableMap(
                result -> result,
                result -> Counter.builder("bitpongo.plan.execution")
                        .description("Plan scheduler execution outcomes")
                        .tag("result", result)
                        .register(registry)));
    }

    public void record(String result) {
        Counter counter = counters.get(result);
        if (counter == null) {
            throw new IllegalArgumentException("Unsupported plan execution result: " + result);
        }
        counter.increment();
    }
}
