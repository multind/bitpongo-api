package com.multind.bitpongo.market;

import java.math.BigDecimal;
import java.time.Instant;

public final class MarketDtos {
    private MarketDtos() {}
    public record Symbol(String symbol, String baseAsset, String quoteAsset) {}
    public record Ticker(String symbol, BigDecimal lastPrice, BigDecimal changePercent, BigDecimal volume, Instant updatedAt) {}
    public record Candle(long openTime, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, BigDecimal volume, long closeTime) {}
}
