package com.multind.bitpongo.market;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PriceCacheTest {
    @Test
    void returnsOnlyFreshPrice() {
        PriceCache cache = new PriceCache(Duration.ofSeconds(60));
        Instant instant = Instant.parse("2026-01-01T00:00:00Z");
        cache.put("binance", "BTC/USDT", new BigDecimal("62000"), instant);

        assertThat(cache.getFresh("binance", "BTC/USDT", instant.plusSeconds(20)))
                .contains(new BigDecimal("62000"));
        assertThat(cache.getFresh("binance", "BTC/USDT", instant.plusSeconds(61))).isEmpty();
    }
}
