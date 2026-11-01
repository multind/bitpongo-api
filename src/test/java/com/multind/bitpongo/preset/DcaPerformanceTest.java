package com.multind.bitpongo.preset;

import com.multind.bitpongo.market.PriceCache;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DcaPerformanceTest {
    @Test void calculatesLossAndRejectsMissingOrStalePrices() {
        var cache = new PriceCache(Duration.ofSeconds(60));
        var now = Instant.parse("2026-11-01T00:00:00Z");
        var positions = List.of(new DcaPerformance.Position("BTC", new BigDecimal("2")));
        assertThat(DcaPerformance.calculate(cache, "binance", positions, new BigDecimal("200"), now)).isNull();
        cache.put("binance", "BTC/USDT", new BigDecimal("96.8621"), now);
        var result = DcaPerformance.calculate(cache, "binance", positions, new BigDecimal("200"), now);
        assertThat(result.ratio()).isEqualByComparingTo("-3.1379");
        assertThat(result.revenue()).isEqualByComparingTo("-6.2758");
        assertThat(DcaPerformance.calculate(cache, "binance", positions, new BigDecimal("200"), now.plusSeconds(61))).isNull();
    }
}
