package com.multind.bitpongo.market;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BinanceMarketStreamLifecycleTest {
    @Test
    void startsOnceUpdatesCacheRotatesAndStops() {
        FakeClient client = new FakeClient();
        FakeScheduler scheduler = new FakeScheduler();
        PriceCache cache = new PriceCache(Duration.ofSeconds(60));
        BinanceMarketStreamLifecycle lifecycle = new BinanceMarketStreamLifecycle(
                client, cache, new SymbolNormalizer(), scheduler, Duration.ofHours(23).plusMinutes(50));

        lifecycle.start();
        lifecycle.start();
        assertThat(client.connectCount).isEqualTo(1);
        assertThat(scheduler.tasks.getFirst().delay()).isEqualTo(Duration.ofHours(23).plusMinutes(50));

        Instant time = Instant.parse("2026-01-01T00:00:00Z");
        client.ticker.accept(new TickerEvent("BTCUSDT", new BigDecimal("62000"), time));
        assertThat(cache.getFresh("binance", "BTC/USDT", time)).contains(new BigDecimal("62000"));

        scheduler.tasks.getFirst().run();
        assertThat(client.connectCount).isEqualTo(2);
        assertThat(client.closedHandles).isEqualTo(1);

        lifecycle.stop();
        assertThat(client.closedHandles).isEqualTo(2);
        assertThat(lifecycle.isRunning()).isFalse();
    }

    @Test
    void failedConnectionsBackOffOneTwoFourSeconds() {
        FakeClient client = new FakeClient();
        client.failConnects = 3;
        FakeScheduler scheduler = new FakeScheduler();
        BinanceMarketStreamLifecycle lifecycle = new BinanceMarketStreamLifecycle(
                client, new PriceCache(Duration.ofSeconds(60)), new SymbolNormalizer(),
                scheduler, Duration.ofHours(23).plusMinutes(50));

        lifecycle.start();
        assertThat(scheduler.last().delay()).isEqualTo(Duration.ofSeconds(1));
        scheduler.last().run();
        assertThat(scheduler.last().delay()).isEqualTo(Duration.ofSeconds(2));
        scheduler.last().run();
        assertThat(scheduler.last().delay()).isEqualTo(Duration.ofSeconds(4));
    }

    private static final class FakeClient implements BinanceMarketStreamClient {
        int connectCount;
        int failConnects;
        int closedHandles;
        Consumer<TickerEvent> ticker;

        @Override
        public StreamHandle connect(
                Consumer<TickerEvent> onTicker,
                Consumer<Throwable> onFailure,
                Runnable onClosed) {
            connectCount++;
            if (failConnects-- > 0) throw new IllegalStateException("connect failed");
            ticker = onTicker;
            AtomicBoolean closed = new AtomicBoolean();
            return () -> {
                if (closed.compareAndSet(false, true)) closedHandles++;
            };
        }
    }

    private static final class FakeScheduler implements MarketTaskScheduler {
        final List<Task> tasks = new ArrayList<>();

        @Override
        public Cancellable schedule(Runnable action, Duration delay) {
            Task task = new Task(action, delay);
            tasks.add(task);
            return () -> task.cancelled = true;
        }

        Task last() { return tasks.getLast(); }

        static final class Task {
            private final Runnable action;
            private final Duration delay;
            private boolean cancelled;

            Task(Runnable action, Duration delay) {
                this.action = action;
                this.delay = delay;
            }

            Duration delay() { return delay; }
            void run() { if (!cancelled) action.run(); }
        }
    }
}
