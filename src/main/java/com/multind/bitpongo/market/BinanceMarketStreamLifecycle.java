package com.multind.bitpongo.market;

import com.multind.bitpongo.notification.NotificationAudienceContext;
import com.multind.bitpongo.notification.NotificationAudienceResolver;
import com.multind.bitpongo.notification.NotificationEvent;
import com.multind.bitpongo.notification.NotificationEventType;
import com.multind.bitpongo.notification.NotificationPublisher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "zhitoubao.market", name = "stream-enabled",
        havingValue = "true", matchIfMissing = true)
public class BinanceMarketStreamLifecycle implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(BinanceMarketStreamLifecycle.class);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(60);

    private final BinanceMarketStreamClient client;
    private final PriceCache prices;
    private final SymbolNormalizer symbols;
    private final MarketTaskScheduler scheduler;
    private final NotificationPublisher notifications;
    private final Supplier<NotificationAudienceContext> marketOutageAudience;
    private final Duration rotationInterval;
    private final Duration healthMaxSilence;
    private final Clock clock;
    private final Supplier<String> cycleIds;

    private volatile boolean running;
    private volatile boolean connected;
    private volatile Instant lastMessageAt;
    private StreamHandle current;
    private Cancellable rotationScheduled;
    private Cancellable reconnectScheduled;
    private Cancellable outageScheduled;
    private Duration nextBackoff = Duration.ofSeconds(1);
    private long generation;
    private OutageCycle outageCycle;

    @Autowired
    public BinanceMarketStreamLifecycle(
            BinanceMarketStreamClient client,
            PriceCache prices,
            SymbolNormalizer symbols,
            MarketTaskScheduler scheduler,
            NotificationPublisher notifications,
            NotificationAudienceResolver audiences,
            @Value("${zhitoubao.market.connection-rotation:PT23H50M}")
                    Duration rotationInterval,
            @Value("${zhitoubao.market.health-max-silence:120s}")
                    Duration healthMaxSilence) {
        this(client, prices, symbols, scheduler, notifications,
                audiences::snapshotMarketOutageAudience, rotationInterval,
                healthMaxSilence, Clock.systemUTC(), () -> UUID.randomUUID().toString());
    }

    BinanceMarketStreamLifecycle(
            BinanceMarketStreamClient client,
            PriceCache prices,
            SymbolNormalizer symbols,
            MarketTaskScheduler scheduler,
            NotificationPublisher notifications,
            Supplier<NotificationAudienceContext> marketOutageAudience,
            Duration rotationInterval,
            Duration healthMaxSilence,
            Clock clock,
            Supplier<String> cycleIds) {
        this.client = Objects.requireNonNull(client, "client");
        this.prices = Objects.requireNonNull(prices, "prices");
        this.symbols = Objects.requireNonNull(symbols, "symbols");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
        this.marketOutageAudience = Objects.requireNonNull(
                marketOutageAudience, "marketOutageAudience");
        this.rotationInterval = positive(rotationInterval, "rotationInterval");
        this.healthMaxSilence = positive(healthMaxSilence, "healthMaxSilence");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.cycleIds = Objects.requireNonNull(cycleIds, "cycleIds");
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    @Override
    public synchronized void start() {
        if (running) return;
        running = true;
        connectNow();
    }

    private synchronized void connectNow() {
        if (!running) return;
        cancelReconnect();
        long connectionGeneration = ++generation;
        try {
            current = client.connect(
                    ticker -> onTicker(connectionGeneration, ticker),
                    failure -> onFailure(connectionGeneration, failure),
                    () -> onClosed(connectionGeneration));
            connected = true;
            nextBackoff = Duration.ofSeconds(1);
            cancelRotation();
            rotationScheduled = scheduler.schedule(this::rotate, rotationInterval);
        } catch (RuntimeException failure) {
            connected = false;
            revokePendingRecovery();
            beginOutageCycle();
            log.warn("Binance 行情连接失败，将自动重试: {}", failure.getMessage());
            scheduleReconnect();
        }
    }

    private void onTicker(long connectionGeneration, TickerEvent event) {
        if (!running || connectionGeneration != generation || event == null
                || event.price() == null || event.price().signum() <= 0
                || event.eventTime() == null) {
            return;
        }
        String internal;
        try {
            internal = symbols.toInternal(event.symbol());
        } catch (IllegalArgumentException ignored) {
            return;
        }
        RecoveryAttempt recovery;
        synchronized (this) {
            if (!running || !connected || connectionGeneration != generation) return;
            prices.put("binance", internal, event.price(), event.eventTime());
            lastMessageAt = event.eventTime();
            recovery = recoverFromOutageLocked(connectionGeneration);
        }
        publishRecoveryIfCurrent(recovery);
    }

    private RecoveryAttempt recoverFromOutageLocked(long connectionGeneration) {
        OutageCycle cycle = outageCycle;
        if (cycle == null) return null;
        if (!cycle.announcementStarted) {
            cancelOutage();
            outageCycle = null;
            return null;
        }
        if (!cycle.publishComplete) {
            cycle.recoveryPendingGeneration = connectionGeneration;
            return null;
        }
        outageCycle = null;
        return new RecoveryAttempt(recoveryEvent(cycle), connectionGeneration);
    }

    private void publishRecoveryIfCurrent(RecoveryAttempt attempt) {
        if (attempt == null) return;
        synchronized (this) {
            if (!running || !connected || attempt.connectionGeneration != generation) return;
        }
        safePublish(attempt.event);
    }

    private NotificationEvent recoveryEvent(OutageCycle cycle) {
        return new NotificationEvent(
                NotificationEventType.SYSTEM_RECOVERED,
                null,
                null,
                null,
                clock.instant(),
                "system-recovered:" + cycle.id,
                Map.of("status", "RECOVERED", "originalEventType", "MARKET_OUTAGE"),
                cycle.audience);
    }

    private synchronized void onFailure(long connectionGeneration, Throwable failure) {
        if (!running || connectionGeneration != generation) return;
        log.warn("Binance 行情流异常，将自动重连: {}", failure.getMessage());
        revokePendingRecovery();
        beginOutageCycle();
        invalidateAndClose();
        scheduleReconnect();
    }

    private synchronized void onClosed(long connectionGeneration) {
        if (!running || connectionGeneration != generation) return;
        generation++;
        connected = false;
        current = null;
        revokePendingRecovery();
        beginOutageCycle();
        scheduleReconnect();
    }

    private void revokePendingRecovery() {
        if (outageCycle != null) outageCycle.recoveryPendingGeneration = null;
    }

    private synchronized void beginOutageCycle() {
        if (outageCycle != null) return;
        String id = Objects.requireNonNull(cycleIds.get(), "cycleId");
        if (id.isBlank()) throw new IllegalStateException("cycleId must not be blank");
        outageCycle = new OutageCycle(id, clock.instant());
        outageScheduled = scheduler.schedule(() -> announceOutage(id), healthMaxSilence);
    }

    private void announceOutage(String cycleId) {
        OutageCycle cycle;
        synchronized (this) {
            if (!running || outageCycle == null || !outageCycle.id.equals(cycleId)
                    || outageCycle.announcementStarted) {
                return;
            }
            outageScheduled = null;
            cycle = outageCycle;
            cycle.announcementStarted = true;
        }

        NotificationAudienceContext audience;
        try {
            audience = Objects.requireNonNull(
                    marketOutageAudience.get(), "market outage audience");
        } catch (RuntimeException failure) {
            log.warn("行情告警接收人快照失败 errorType={}",
                    failure.getClass().getSimpleName());
            audience = new NotificationAudienceContext(Set.of(), false);
        }

        NotificationEvent outage;
        synchronized (this) {
            if (!running || outageCycle != cycle) return;
            cycle.audience = audience;
            cycle.outagePublishing = true;
            outage = new NotificationEvent(
                    NotificationEventType.MARKET_OUTAGE,
                    null,
                    null,
                    null,
                    clock.instant(),
                    "market-outage:" + cycle.id,
                    Map.of("status", "UNAVAILABLE"),
                    cycle.audience);
        }
        safePublish(outage);

        RecoveryAttempt recovery = null;
        synchronized (this) {
            if (!running || outageCycle != cycle) return;
            cycle.outagePublishing = false;
            cycle.publishComplete = true;
            Long pendingGeneration = cycle.recoveryPendingGeneration;
            if (connected && pendingGeneration != null && pendingGeneration == generation) {
                outageCycle = null;
                recovery = new RecoveryAttempt(
                        recoveryEvent(cycle), pendingGeneration);
            }
        }
        publishRecoveryIfCurrent(recovery);
    }

    private void safePublish(NotificationEvent event) {
        try {
            notifications.publish(event);
        } catch (RuntimeException failure) {
            log.warn("行情通知发布失败 eventType={} errorType={}",
                    event.type(), failure.getClass().getSimpleName());
        }
    }

    private synchronized void rotate() {
        rotationScheduled = null;
        if (!running) return;
        invalidateAndClose();
        connectNow();
    }

    private void scheduleReconnect() {
        cancelReconnect();
        Duration delay = nextBackoff;
        Duration doubled = nextBackoff.multipliedBy(2);
        nextBackoff = doubled.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : doubled;
        reconnectScheduled = scheduler.schedule(this::connectNow, delay);
    }

    private void invalidateAndClose() {
        generation++;
        connected = false;
        StreamHandle handle = current;
        current = null;
        if (handle != null) handle.close();
    }

    private void cancelRotation() {
        if (rotationScheduled != null) {
            rotationScheduled.cancel();
            rotationScheduled = null;
        }
    }

    private void cancelReconnect() {
        if (reconnectScheduled != null) {
            reconnectScheduled.cancel();
            reconnectScheduled = null;
        }
    }

    private void cancelOutage() {
        if (outageScheduled != null) {
            outageScheduled.cancel();
            outageScheduled = null;
        }
    }

    @Override
    public synchronized void stop() {
        if (!running) return;
        running = false;
        cancelRotation();
        cancelReconnect();
        cancelOutage();
        outageCycle = null;
        invalidateAndClose();
    }

    @Override public boolean isRunning() { return running; }
    public boolean isConnected() { return connected; }
    public Instant lastMessageAt() { return lastMessageAt; }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 100; }

    private static final class OutageCycle {
        private final String id;
        @SuppressWarnings("unused")
        private final Instant startedAt;
        private boolean announcementStarted;
        private boolean outagePublishing;
        private boolean publishComplete;
        private Long recoveryPendingGeneration;
        private NotificationAudienceContext audience;

        private OutageCycle(String id, Instant startedAt) {
            this.id = id;
            this.startedAt = startedAt;
        }
    }

    private record RecoveryAttempt(NotificationEvent event, long connectionGeneration) {
    }
}
