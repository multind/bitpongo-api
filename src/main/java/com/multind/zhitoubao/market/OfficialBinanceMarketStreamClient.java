package com.multind.zhitoubao.market;

import com.binance.connector.client.spot.websocket.stream.SpotWebSocketStreamsUtil;
import com.binance.connector.client.spot.websocket.stream.api.SpotWebSocketStreams;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OfficialBinanceMarketStreamClient implements BinanceMarketStreamClient {
    private final String streamPath;

    public OfficialBinanceMarketStreamClient(
            @Value("${zhitoubao.binance.market-stream-url:wss://stream.binance.com:9443}") String streamUrl) {
        this.streamPath = normalizeStreamPath(streamUrl);
    }

    /**
     * The Binance connector appends the configured fragment to its fixed base
     * {@code wss://stream.binance.com:9443}, so only the path part of the configured
     * stream URL is forwarded.
     */
    static String normalizeStreamPath(String streamUrl) {
        String path = URI.create(streamUrl).getPath();
        return path == null ? "" : path;
    }

    @Override
    public StreamHandle connect(
            Consumer<TickerEvent> onTicker,
            Consumer<Throwable> onFailure,
            Runnable onClosed) {
        var configuration = SpotWebSocketStreamsUtil.getClientConfiguration(streamPath);
        SpotWebSocketStreams streams = new SpotWebSocketStreams(configuration);
        var queue = streams.allMiniTicker();
        AtomicBoolean closed = new AtomicBoolean();
        Thread reader = Thread.ofVirtual().name("binance-all-mini-ticker").start(() -> {
            try {
                while (!closed.get()) {
                    var batch = queue.take();
                    for (var item : batch) {
                        if (item.getsLowerCase() != null && item.getcLowerCase() != null) {
                            Instant eventTime = item.getE() == null
                                    ? Instant.now() : Instant.ofEpochMilli(item.getE());
                            onTicker.accept(new TickerEvent(
                                    item.getsLowerCase(), new BigDecimal(item.getcLowerCase()), eventTime));
                        }
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Throwable failure) {
                if (!closed.get()) onFailure.accept(failure);
            } finally {
                stop(streams);
                onClosed.run();
            }
        });
        return () -> {
            if (closed.compareAndSet(false, true)) {
                reader.interrupt();
                stop(streams);
            }
        };
    }

    private static void stop(SpotWebSocketStreams streams) {
        try {
            streams.stop();
        } catch (Exception ignored) {
            // Closing an already failed connection is best effort.
        }
    }
}
