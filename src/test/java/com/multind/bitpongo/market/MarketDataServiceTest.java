package com.multind.bitpongo.market;

import com.multind.bitpongo.common.api.BusinessException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketDataServiceTest {
    private final MarketDataClient client = mock(MarketDataClient.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-03T00:00:00Z"), ZoneOffset.UTC);
    private final MarketDataService service = new MarketDataService(client, clock);

    @Test void searchesOnlyUsdtPairsAndCachesSymbolDirectory() {
        when(client.symbols()).thenReturn(List.of(
                new MarketDtos.Symbol("BTCUSDT", "BTC", "USDT"),
                new MarketDtos.Symbol("ETHUSDT", "ETH", "USDT"),
                new MarketDtos.Symbol("ETHBTC", "ETH", "BTC")));
        assertThat(service.symbols("btc")).extracting(MarketDtos.Symbol::symbol).containsExactly("BTCUSDT");
        assertThat(service.symbols("")).hasSize(2);
        verify(client, times(1)).symbols();
    }

    @Test void rejectsInvalidCandleRequestsBeforeContactingUpstream() {
        for (String symbol : List.of("", "BTC/USDT?x=1", "BTCBTC")) {
            assertThatThrownBy(() -> service.candles(symbol, "1h", 120)).isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> service.candles("BTCUSDT", "5years", 120)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.candles("BTCUSDT", "1h", 0)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.candles("BTCUSDT", "1h", 501)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(client);
    }

    @Test void normalizesPairAndPreservesCandleDecimals() {
        var candle = new MarketDtos.Candle(1790985600000L, new BigDecimal("0.00000123"),
                new BigDecimal("0.00000125"), new BigDecimal("0.00000120"),
                new BigDecimal("0.00000124"), new BigDecimal("500"), 1790989199999L);
        when(client.candles("BTCUSDT", "1h", 120)).thenReturn(List.of(candle));
        assertThat(service.candles(" btc/usdt ", "1h", 120)).containsExactly(candle);
    }

    @Test void filtersAndCachesTickerSnapshot() {
        when(client.tickers()).thenReturn(List.of(
                new MarketDtos.Ticker("BTCUSDT", new BigDecimal("60000"), new BigDecimal("2.5"), new BigDecimal("10"), clock.instant()),
                new MarketDtos.Ticker("ETHBTC", new BigDecimal("0.04"), BigDecimal.ZERO, BigDecimal.ONE, clock.instant())));
        assertThat(service.tickers()).extracting(MarketDtos.Ticker::symbol).containsExactly("BTCUSDT");
        service.tickers();
        verify(client, times(1)).tickers();
    }

    @Test void propagatesUnavailableMarketRatherThanInventingPrices() {
        when(client.tickers()).thenThrow(new BusinessException(503, "Market unavailable"));
        assertThatThrownBy(service::tickers).isInstanceOf(BusinessException.class);
    }

    @Test void refreshesExpiredTickerCacheAndDoesNotServeStaleDataOnFailure() {
        Clock moving = mock(Clock.class);
        when(moving.instant()).thenReturn(Instant.parse("2026-10-03T00:00:00Z"));
        var cached = new MarketDataService(client, moving);
        when(client.tickers()).thenReturn(List.of(new MarketDtos.Ticker("BTCUSDT", new BigDecimal("60000"), BigDecimal.ZERO, BigDecimal.ONE, clock.instant())));
        cached.tickers();
        when(moving.instant()).thenReturn(Instant.parse("2026-10-03T00:00:16Z"));
        when(client.tickers()).thenThrow(new BusinessException(503, "Market unavailable"));
        assertThatThrownBy(cached::tickers).isInstanceOf(BusinessException.class);
        verify(client, times(2)).tickers();
    }
}
