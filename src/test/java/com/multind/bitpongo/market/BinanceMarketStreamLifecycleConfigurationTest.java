package com.multind.bitpongo.market;

import com.multind.bitpongo.notification.NotificationAudienceResolver;
import com.multind.bitpongo.notification.NotificationPublisher;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class BinanceMarketStreamLifecycleConfigurationTest {

    @Test
    void healthMaxSilenceDefaultsTo120Seconds() {
        assertOutageDelay(new ApplicationContextRunner(), Duration.ofSeconds(120));
    }

    @Test
    void healthMaxSilenceBindsConfiguredDuration() {
        assertOutageDelay(
                new ApplicationContextRunner()
                        .withPropertyValues("zhitoubao.market.health-max-silence=37s"),
                Duration.ofSeconds(37));
    }

    private static void assertOutageDelay(
            ApplicationContextRunner runner,
            Duration expectedDelay) {
        FakeClient client = new FakeClient();
        RecordingScheduler scheduler = new RecordingScheduler();
        runner.withPropertyValues("zhitoubao.market.stream-enabled=true")
                .withInitializer(context -> context.getBeanFactory()
                        .setConversionService(
                                ApplicationConversionService.getSharedInstance()))
                .withBean(BinanceMarketStreamClient.class, () -> client)
                .withBean(PriceCache.class, () -> new PriceCache(Duration.ofSeconds(60)))
                .withBean(SymbolNormalizer.class, SymbolNormalizer::new)
                .withBean(MarketTaskScheduler.class, () -> scheduler)
                .withBean(NotificationPublisher.class, () -> event -> {})
                .withBean(NotificationAudienceResolver.class,
                        () -> mock(NotificationAudienceResolver.class))
                .withUserConfiguration(BinanceMarketStreamLifecycle.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    BinanceMarketStreamLifecycle lifecycle =
                            context.getBean(BinanceMarketStreamLifecycle.class);
                    if (!lifecycle.isRunning()) lifecycle.start();
                    client.fail(new IllegalStateException("configuration test outage"));
                    assertThat(scheduler.delays).contains(expectedDelay);
                });
    }

    private static final class FakeClient implements BinanceMarketStreamClient {
        private Consumer<Throwable> onFailure;

        @Override
        public StreamHandle connect(
                Consumer<TickerEvent> onTicker,
                Consumer<Throwable> onFailure,
                Runnable onClosed) {
            this.onFailure = onFailure;
            return () -> {};
        }

        private void fail(Throwable failure) {
            onFailure.accept(failure);
        }
    }

    private static final class RecordingScheduler implements MarketTaskScheduler {
        private final List<Duration> delays = new ArrayList<>();

        @Override
        public Cancellable schedule(Runnable action, Duration delay) {
            delays.add(delay);
            return () -> {};
        }
    }
}
