package com.multind.bitpongo.market;

import java.math.BigDecimal;
import java.time.Instant;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

public final class MarketDtos {
    private MarketDtos() {}
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Symbol(String symbol, String baseAsset, String quoteAsset) {}
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Ticker(String symbol, BigDecimal lastPrice, BigDecimal changePercent, BigDecimal volume, Instant updatedAt) {}
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Candle(long openTime, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, BigDecimal volume, long closeTime) {}
}
