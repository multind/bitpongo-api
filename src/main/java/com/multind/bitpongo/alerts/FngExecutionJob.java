package com.multind.bitpongo.alerts;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class FngExecutionJob {
    private final FngExecutionService execution;
    private final boolean enabled;

    public FngExecutionJob(
            FngExecutionService execution,
            @Value("${zhitoubao.fng.execution-enabled:false}") boolean enabled) {
        this.execution = execution;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${FNG_EXECUTION_POLL_DELAY:5000}",
            initialDelayString = "${FNG_EXECUTION_INITIAL_DELAY:15000}")
    public void poll() {
        if (enabled) execution.process();
    }
}