package com.multind.bitpongo.market;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PriceCache {
    private final Duration maxAge;
    private final Map<PriceKey, MarketPrice> prices = new ConcurrentHashMap<>();

    @Autowired
    public PriceCache(@Value("${zhitoubao.market.price-max-age:60s}") Duration maxAge) {
        if (maxAge == null || maxAge.isNegative() || maxAge.isZero()) {
            throw new IllegalArgumentException("行情有效期必须大于 0");
        }
        this.maxAge = maxAge;
    }

    public void put(String exchange, String symbol, BigDecimal price, Instant updatedAt) {
        if (price == null || price.signum() <= 0 || updatedAt == null) {
            return;
        }
        prices.put(key(exchange, symbol), new MarketPrice(price, updatedAt));
    }

    public Optional<BigDecimal> getFresh(String exchange, String symbol, Instant now) {
        MarketPrice value = prices.get(key(exchange, symbol));
        if (value == null || now == null || value.updatedAt().plus(maxAge).isBefore(now)) {
            return Optional.empty();
        }
        return Optional.of(value.price());
    }

    public Optional<MarketPrice> get(String exchange, String symbol) {
        return Optional.ofNullable(prices.get(key(exchange, symbol)));
    }

    private static PriceKey key(String exchange, String symbol) {
        return new PriceKey(normalize(exchange), normalize(symbol));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private record PriceKey(String exchange, String symbol) {}
}
