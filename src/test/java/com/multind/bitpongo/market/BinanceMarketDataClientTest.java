package com.multind.bitpongo.market;

import com.multind.bitpongo.common.api.BusinessException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class BinanceMarketDataClientTest {
    @Test void parsesPublicSpotSymbolsAndRejectsSuspendedPairs() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("""
                {"symbols":[{"symbol":"BTCUSDT","baseAsset":"BTC","quoteAsset":"USDT","status":"TRADING","isSpotTradingAllowed":true},
                {"symbol":"OLDUSDT","baseAsset":"OLD","quoteAsset":"USDT","status":"BREAK","isSpotTradingAllowed":true}]}
                """));
            var client = new BinanceMarketDataClient(server.url("/").toString(), JsonMapper.builder().build());
            assertThat(client.symbols()).extracting(MarketDtos.Symbol::symbol).containsExactly("BTCUSDT");
            assertThat(server.takeRequest().getPath()).isEqualTo("/api/v3/exchangeInfo");
        }
    }
    @Test void parsesCandleTimesAndDecimals() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("[[1790985600000,\"60000.1\",\"61000\",\"59000\",\"60500\",\"10\",1790989199999]]"));
            var client = new BinanceMarketDataClient(server.url("/").toString(), JsonMapper.builder().build());
            var candle = client.candles("BTCUSDT", "1h", 120).getFirst();
            assertThat(candle.open()).isEqualByComparingTo("60000.1");
            assertThat(candle.openTime()).isEqualTo(1790985600000L);
            assertThat(server.takeRequest().getPath()).isEqualTo("/api/v3/klines?symbol=BTCUSDT&interval=1h&limit=120");
        }
    }
    @Test void rejectsMalformedSuccessPayloads() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"error\":\"upstream\"}"));
            var client = new BinanceMarketDataClient(server.url("/").toString(), JsonMapper.builder().build());
            assertThatThrownBy(client::tickers).isInstanceOf(BusinessException.class);
        }
    }
    @Test void surfacesUpstreamFailure() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(429).setBody("rate limit"));
            var client = new BinanceMarketDataClient(server.url("/").toString(), JsonMapper.builder().build());
            assertThatThrownBy(client::symbols).isInstanceOf(BusinessException.class);
        }
    }
}
