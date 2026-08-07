package com.multind.zhitoubao.market;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class ExecutorMarketTaskScheduler implements MarketTaskScheduler {
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("binance-market-scheduler").factory());

    @Override
    public Cancellable schedule(Runnable action, Duration delay) {
        var future = executor.schedule(action, delay.toMillis(), TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
