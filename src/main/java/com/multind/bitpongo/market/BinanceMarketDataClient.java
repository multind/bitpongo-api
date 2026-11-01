package com.multind.bitpongo.market;

import com.multind.bitpongo.common.api.BusinessException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class BinanceMarketDataClient implements MarketDataClient {
    private final String baseUrl;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public BinanceMarketDataClient(@Value("${zhitoubao.market.rest-base-url:https://data-api.binance.vision}") String baseUrl, ObjectMapper json) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.json = json;
    }

    @Override public List<MarketDtos.Symbol> symbols() {
        var result = new ArrayList<MarketDtos.Symbol>();
        for (JsonNode s : get("/api/v3/exchangeInfo").path("symbols")) {
            if ("TRADING".equals(s.path("status").asText()) && s.path("isSpotTradingAllowed").asBoolean()) {
                String tickSize = null;
                for (JsonNode filter : s.path("filters")) {
                    if ("PRICE_FILTER".equals(filter.path("filterType").asText())) {
                        String candidate = filter.path("tickSize").asText();
                        try { if (new BigDecimal(candidate).signum() > 0) tickSize = candidate; } catch (NumberFormatException ignored) { }
                        break;
                    }
                }
                result.add(new MarketDtos.Symbol(s.path("symbol").asText(), s.path("baseAsset").asText(), s.path("quoteAsset").asText(), tickSize));
            }
        }
        return List.copyOf(result);
    }

    @Override public List<MarketDtos.Ticker> tickers() {
        var result = new ArrayList<MarketDtos.Ticker>();
        for (JsonNode t : get("/api/v3/ticker/24hr")) {
            result.add(new MarketDtos.Ticker(t.path("symbol").asText(), decimal(t.path("lastPrice")),
                    decimal(t.path("priceChangePercent")), decimal(t.path("quoteVolume")), Instant.ofEpochMilli(t.path("closeTime").asLong())));
        }
        return List.copyOf(result);
    }

    @Override public List<MarketDtos.Candle> candles(String symbol, String interval, int limit) {
        var result = new ArrayList<MarketDtos.Candle>();
        for (JsonNode c : get("/api/v3/klines?symbol=" + symbol + "&interval=" + interval + "&limit=" + limit)) {
            if (!c.isArray() || c.size() < 7) throw new BusinessException(502, "行情数据格式无效");
            result.add(new MarketDtos.Candle(c.get(0).asLong(), decimal(c.get(1)), decimal(c.get(2)),
                    decimal(c.get(3)), decimal(c.get(4)), decimal(c.get(5)), c.get(6).asLong()));
        }
        return List.copyOf(result);
    }

    private JsonNode get(String path) {
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(10)).GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new BusinessException(503, "行情服务暂时不可用");
            JsonNode value = json.readTree(response.body());
            if (value == null || (path.equals("/api/v3/exchangeInfo")
                    ? !value.path("symbols").isArray() : !value.isArray())) {
                throw new BusinessException(502, "行情数据格式无效");
            }
            return value;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(503, "行情请求已中断");
        } catch (BusinessException e) { throw e;
        } catch (Exception e) { throw new BusinessException(503, "行情服务暂时不可用"); }
    }
    private static BigDecimal decimal(JsonNode value) {
        try { return new BigDecimal(value.asText()); }
        catch (RuntimeException e) { throw new BusinessException(502, "行情数据格式无效"); }
    }
}
