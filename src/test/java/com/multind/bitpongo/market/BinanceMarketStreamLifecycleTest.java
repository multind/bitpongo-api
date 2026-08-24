package com.multind.bitpongo.market;

import com.multind.bitpongo.notification.NotificationAudienceContext;
import com.multind.bitpongo.notification.NotificationEvent;
import com.multind.bitpongo.notification.NotificationEventType;
import com.multind.bitpongo.notification.NotificationPublisher;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BinanceMarketStreamLifecycleTest {
    private static final Duration ROTATION = Duration.ofHours(23).plusMinutes(50);
    private static final Duration MAX_SILENCE = Duration.ofSeconds(120);
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final NotificationAudienceContext AUDIENCE =
            new NotificationAudienceContext(Set.of(11L, 22L), true);

    @Test
    void startsOnceCachesValidTickerRotatesSilentlyAndStopsAllTasks() {
        Fixture f = new Fixture();
        f.lifecycle.start();
        f.lifecycle.start();
        assertThat(f.client.connectCount).isEqualTo(1);
        assertThat(f.scheduler.activeDelays()).containsExactly(ROTATION);
        f.client.emit(ticker("BTCUSDT", "62000"));
        assertThat(f.cache.getFresh("binance", "BTC/USDT", f.clock.instant()))
                .contains(new BigDecimal("62000"));
        f.scheduler.advance(ROTATION);
        assertThat(f.client.connectCount).isEqualTo(2);
        assertThat(f.client.closedHandles).isEqualTo(1);
        assertThat(f.notifications.events).isEmpty();
        f.client.fail(new IllegalStateException("disconnected"));
        f.lifecycle.stop();
        assertThat(f.scheduler.activeTaskCount()).isZero();
        f.scheduler.advance(Duration.ofDays(2));
        assertThat(f.client.connectCount).isEqualTo(2);
        assertThat(f.notifications.events).isEmpty();
        assertThat(f.lifecycle.isRunning()).isFalse();
    }

    @Test
    void failedConnectionsBackOffOneTwoFourSecondsWhileOneOutageTimerRemains() {
        Fixture f = new Fixture();
        f.client.failConnects = 3;
        f.lifecycle.start();
        assertThat(f.scheduler.activeDelays())
                .containsExactlyInAnyOrder(Duration.ofSeconds(1), MAX_SILENCE);
        f.scheduler.advance(Duration.ofSeconds(1));
        assertThat(f.scheduler.activeDelays())
                .containsExactlyInAnyOrder(Duration.ofSeconds(2), Duration.ofSeconds(119));
        f.scheduler.advance(Duration.ofSeconds(2));
        assertThat(f.scheduler.activeDelays())
                .containsExactlyInAnyOrder(Duration.ofSeconds(4), Duration.ofSeconds(117));
    }

    @Test
    void failureFollowedByClosedCallbackKeepsTheFirstReconnectDelay() {
        Fixture f = new Fixture();
        f.lifecycle.start();
        f.client.failThenClose(new IllegalStateException("reader failed"));
        assertThat(f.scheduler.activeDelays())
                .containsExactlyInAnyOrder(
                        ROTATION, Duration.ofSeconds(1), MAX_SILENCE);
        f.scheduler.advance(Duration.ofSeconds(1));
        assertThat(f.client.connectCount).isEqualTo(2);
    }

    @Test
    void transientFailureReconnectsAndValidTickerBeforeThresholdCancelsOutageSilently() {
        Fixture f = new Fixture();
        f.lifecycle.start();
        f.client.fail(new IllegalStateException("closed"));
        f.scheduler.advance(Duration.ofSeconds(1));
        f.scheduler.advance(Duration.ofSeconds(30));
        f.client.emit(ticker("BTCUSDT", "61000"));
        f.scheduler.advance(Duration.ofMinutes(5));
        assertThat(f.notifications.events).isEmpty();
        assertThat(f.cache.get("binance", "BTC/USDT")).isPresent();
    }

    @Test
    void successfulReconnectWithoutTickerStillPublishesOneOutageForTheCycle() {
        Fixture f = new Fixture();
        f.lifecycle.start();
        f.client.fail(new IllegalStateException("closed"));
        f.scheduler.advance(Duration.ofSeconds(1));
        assertThat(f.client.connectCount).isEqualTo(2);
        f.scheduler.advance(Duration.ofSeconds(119));
        assertThat(f.notifications.events).extracting(NotificationEvent::type)
                .containsExactly(NotificationEventType.MARKET_OUTAGE);
        f.client.close();
        f.client.failConnects = 2;
        f.scheduler.advance(Duration.ofMinutes(5));
        f.client.close();
        f.scheduler.advance(Duration.ofMinutes(5));
        assertThat(f.notifications.events).extracting(NotificationEvent::type)
                .containsExactly(NotificationEventType.MARKET_OUTAGE);
    }

    @Test
    void invalidTickerDoesNotRecoverAndOnlyFirstValidCachedTickerRecovers() {
        Fixture f = new Fixture();
        f.lifecycle.start();
        f.client.fail(new IllegalStateException("closed"));
        f.scheduler.advance(MAX_SILENCE);
        f.client.emit(ticker("ETHBTC", "0.04"));
        f.client.emit(ticker("BTCUSDT", "0"));
        assertThat(f.notifications.events).extracting(NotificationEvent::type)
                .containsExactly(NotificationEventType.MARKET_OUTAGE);
        f.client.emit(ticker("BTCUSDT", "62000"));
        f.client.emit(ticker("ETHUSDT", "3500"));
        assertThat(f.notifications.events).extracting(NotificationEvent::type)
                .containsExactly(NotificationEventType.MARKET_OUTAGE,
                        NotificationEventType.SYSTEM_RECOVERED);
        NotificationEvent outage = f.notifications.events.get(0);
        NotificationEvent recovery = f.notifications.events.get(1);
        assertThat(outage.dedupeKey()).isEqualTo("market-outage:cycle-8");
        assertThat(recovery.dedupeKey()).isEqualTo("system-recovered:cycle-8");
        assertThat(outage.audienceContext()).isSameAs(AUDIENCE);
        assertThat(recovery.audienceContext()).isSameAs(AUDIENCE);
        assertThat(outage.attributes()).doesNotContainKeys(
                "recipientUserIds", "audienceContext", "cycleId");
        assertThat(recovery.attributes()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "status", "RECOVERED", "originalEventType", "MARKET_OUTAGE"));
    }

    @Test
    void publisherFailuresDoNotBreakReconnectOrCachingAndAreNotRetriedForever() {
        Fixture f = new Fixture();
        f.notifications.throwOnPublish = true;
        f.lifecycle.start();
        f.client.fail(new IllegalStateException("closed"));
        f.scheduler.advance(MAX_SILENCE);
        f.scheduler.advance(Duration.ofMinutes(10));
        assertThat(f.notifications.attempts).isEqualTo(1);
        f.client.emit(ticker("BTCUSDT", "62000"));
        f.client.emit(ticker("ETHUSDT", "3500"));
        assertThat(f.cache.get("binance", "BTC/USDT")).isPresent();
        assertThat(f.notifications.attempts).isEqualTo(2);
        f.scheduler.advance(Duration.ofDays(1));
        assertThat(f.notifications.attempts).isEqualTo(2);
    }

    private static TickerEvent ticker(String symbol, String price) {
        return new TickerEvent(symbol, new BigDecimal(price), START.plusSeconds(10));
    }

    private static final class Fixture {
        final FakeClient client = new FakeClient();
        final MutableClock clock = new MutableClock(START);
        final FakeScheduler scheduler = new FakeScheduler(clock);
        final PriceCache cache = new PriceCache(Duration.ofSeconds(60));
        final RecordingPublisher notifications = new RecordingPublisher();
        final BinanceMarketStreamLifecycle lifecycle = new BinanceMarketStreamLifecycle(
                client, cache, new SymbolNormalizer(), scheduler, notifications,
                () -> AUDIENCE, ROTATION, MAX_SILENCE, clock, () -> "cycle-8");
    }

    private static final class RecordingPublisher implements NotificationPublisher {
        final List<NotificationEvent> events = new ArrayList<>();
        int attempts;
        boolean throwOnPublish;
        @Override public void publish(NotificationEvent event) {
            attempts++;
            if (throwOnPublish) throw new IllegalStateException("outbox unavailable");
            events.add(event);
        }
    }

    private static final class FakeClient implements BinanceMarketStreamClient {
        int connectCount;
        int failConnects;
        int closedHandles;
        Connection latest;
        @Override public StreamHandle connect(Consumer<TickerEvent> onTicker,
                Consumer<Throwable> onFailure, Runnable onClosed) {
            connectCount++;
            if (failConnects-- > 0) throw new IllegalStateException("connect failed");
            latest = new Connection(onTicker, onFailure, onClosed);
            AtomicBoolean closed = new AtomicBoolean();
            return () -> { if (closed.compareAndSet(false, true)) closedHandles++; };
        }
        void emit(TickerEvent ticker) { latest.onTicker.accept(ticker); }
        void fail(Throwable failure) { latest.onFailure.accept(failure); }
        void close() { latest.onClosed.run(); }
        void failThenClose(Throwable failure) {
            latest.onFailure.accept(failure);
            latest.onClosed.run();
        }
        private record Connection(Consumer<TickerEvent> onTicker,
                Consumer<Throwable> onFailure, Runnable onClosed) {}
    }

    private static final class FakeScheduler implements MarketTaskScheduler {
        private final MutableClock clock;
        private final List<Task> tasks = new ArrayList<>();
        FakeScheduler(MutableClock clock) { this.clock = clock; }
        @Override public Cancellable schedule(Runnable action, Duration delay) {
            Task task = new Task(action, clock.instant().plus(delay));
            tasks.add(task);
            return () -> task.cancelled = true;
        }
        void advance(Duration duration) {
            Instant target = clock.instant().plus(duration);
            while (true) {
                Task next = tasks.stream()
                        .filter(task -> !task.cancelled && !task.ran
                                && !task.dueAt.isAfter(target))
                        .min(Comparator.comparing(task -> task.dueAt)).orElse(null);
                if (next == null) break;
                clock.set(next.dueAt);
                next.ran = true;
                next.action.run();
            }
            clock.set(target);
        }
        List<Duration> activeDelays() {
            return tasks.stream().filter(task -> !task.cancelled && !task.ran)
                    .map(task -> Duration.between(clock.instant(), task.dueAt)).toList();
        }
        long activeTaskCount() {
            return tasks.stream().filter(task -> !task.cancelled && !task.ran).count();
        }
        private static final class Task {
            private final Runnable action;
            private final Instant dueAt;
            private boolean cancelled;
            private boolean ran;
            private Task(Runnable action, Instant dueAt) {
                this.action = action;
                this.dueAt = dueAt;
            }
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        MutableClock(Instant instant) { this.instant = instant; }
        void set(Instant instant) { this.instant = instant; }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
