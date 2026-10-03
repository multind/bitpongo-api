package com.multind.bitpongo.market;

import com.multind.bitpongo.common.api.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MarketDataService {
    private static final Set<String> INTERVALS = Set.of("1m", "5m", "15m", "30m", "1h", "4h", "1d", "1w");
    private final MarketDataClient client;
    private final Clock clock;
    private List<MarketDtos.Symbol> directory;
    private Instant directoryExpires = Instant.MIN;
    private List<MarketDtos.Ticker> snapshot;
    private Instant snapshotExpires = Instant.MIN;

    @Autowired public MarketDataService(MarketDataClient client) { this(client, Clock.systemUTC()); }
    MarketDataService(MarketDataClient client, Clock clock) { this.client = client; this.clock = clock; }

    public synchronized List<MarketDtos.Symbol> symbols(String query) {
        if (directory == null || !clock.instant().isBefore(directoryExpires)) {
            directory = client.symbols().stream().filter(s -> "USDT".equals(s.quoteAsset())).toList();
            directoryExpires = clock.instant().plusSeconds(3600);
        }
        String search = query == null ? "" : query.trim().toUpperCase(Locale.ROOT);
        if (search.length() > 40) throw new BusinessException(400, "搜索内容过长");
        return directory.stream().filter(s -> s.symbol().contains(search)).toList();
    }

    public synchronized List<MarketDtos.Ticker> tickers() {
        if (snapshot == null || !clock.instant().isBefore(snapshotExpires)) {
            snapshot = client.tickers().stream().filter(t -> t.symbol().endsWith("USDT")).toList();
            snapshotExpires = clock.instant().plusSeconds(15);
        }
        return snapshot;
    }

    public List<MarketDtos.Candle> candles(String symbol, String interval, int limit) {
        String pair = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT).replace("/", "");
        if (!pair.matches("[A-Z0-9]{1,20}USDT") || !INTERVALS.contains(interval) || limit < 1 || limit > 500) {
            throw new BusinessException(400, "交易对、时间周期或数据条数无效");
        }
        return client.candles(pair, interval, limit);
    }
}
