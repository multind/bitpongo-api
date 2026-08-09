package com.multind.zhitoubao.market;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "zhitoubao.market", name = "stream-enabled", havingValue = "true", matchIfMissing = true)
public class BinanceMarketStreamLifecycle implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(BinanceMarketStreamLifecycle.class);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(60);

    private final BinanceMarketStreamClient client;
    private final PriceCache prices;
    private final SymbolNormalizer symbols;
    private final MarketTaskScheduler scheduler;
    private final Duration rotationInterval;

    private volatile boolean running;
    private volatile boolean connected;
    private volatile Instant lastMessageAt;
    private StreamHandle current;
    private Cancellable scheduled;
    private Duration nextBackoff = Duration.ofSeconds(1);
    private long generation;

    @Autowired
    public BinanceMarketStreamLifecycle(
            BinanceMarketStreamClient client,
            PriceCache prices,
            SymbolNormalizer symbols,
            MarketTaskScheduler scheduler,
            @Value("${zhitoubao.market.connection-rotation:PT23H50M}") Duration rotationInterval) {
        this.client = client;
        this.prices = prices;
        this.symbols = symbols;
        this.scheduler = scheduler;
        this.rotationInterval = rotationInterval;
    }

    @Override
    public synchronized void start() {
        if (running) return;
        running = true;
        connectNow();
    }

    private synchronized void connectNow() {
        if (!running) return;
        cancelScheduled();
        long connectionGeneration = ++generation;
        try {
            current = client.connect(
                    this::onTicker,
                    failure -> onFailure(connectionGeneration, failure),
                    () -> onClosed(connectionGeneration));
            connected = true;
            nextBackoff = Duration.ofSeconds(1);
            scheduled = scheduler.schedule(this::rotate, rotationInterval);
        } catch (RuntimeException failure) {
            connected = false;
            log.warn("Binance 行情连接失败，将自动重试: {}", failure.getMessage());
            scheduleReconnect();
        }
    }

    private void onTicker(TickerEvent event) {
        try {
            String internal = symbols.toInternal(event.symbol());
            prices.put("binance", internal, event.price(), event.eventTime());
            lastMessageAt = event.eventTime();
        } catch (IllegalArgumentException ignored) {
            // 全市场流中可能包含非 USDT 交易对，本应用无需缓存。
        }
    }

    private synchronized void onFailure(long connectionGeneration, Throwable failure) {
        if (!running || connectionGeneration != generation) return;
        log.warn("Binance 行情流异常，将自动重连: {}", failure.getMessage());
        invalidateAndClose();
        scheduleReconnect();
    }

    private synchronized void onClosed(long connectionGeneration) {
        if (!running || connectionGeneration != generation) return;
        connected = false;
        scheduleReconnect();
    }

    private synchronized void rotate() {
        if (!running) return;
        invalidateAndClose();
        connectNow();
    }

    private void scheduleReconnect() {
        cancelScheduled();
        Duration delay = nextBackoff;
        nextBackoff = nextBackoff.multipliedBy(2).compareTo(MAX_BACKOFF) > 0
                ? MAX_BACKOFF : nextBackoff.multipliedBy(2);
        scheduled = scheduler.schedule(this::connectNow, delay);
    }

    private void invalidateAndClose() {
        generation++;
        connected = false;
        StreamHandle handle = current;
        current = null;
        if (handle != null) handle.close();
    }

    private void cancelScheduled() {
        if (scheduled != null) {
            scheduled.cancel();
            scheduled = null;
        }
    }

    @Override
    public synchronized void stop() {
        if (!running) return;
        running = false;
        cancelScheduled();
        invalidateAndClose();
    }

    @Override public boolean isRunning() { return running; }
    public boolean isConnected() { return connected; }
    public Instant lastMessageAt() { return lastMessageAt; }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 100; }
}
