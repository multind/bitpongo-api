package com.multind.bitpongo.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.multind.bitpongo.strategy.CoinEntity;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PriceRangeTest {
    private static CoinEntity coin(String min, String max) {
        CoinEntity coin = new CoinEntity();
        coin.setMin(new BigDecimal(min));
        coin.setMax(new BigDecimal(max));
        return coin;
    }

    @Test
    void zeroBoundsMeanNoLimit() {
        assertThat(ScheduledPurchaseService.withinPriceRange(coin("0", "0"), new BigDecimal("123456"))).isTrue();
    }

    @Test
    void boundsAreInclusive() {
        CoinEntity coin = coin("100", "200");
        assertThat(ScheduledPurchaseService.withinPriceRange(coin, new BigDecimal("100"))).isTrue();
        assertThat(ScheduledPurchaseService.withinPriceRange(coin, new BigDecimal("200"))).isTrue();
        assertThat(ScheduledPurchaseService.withinPriceRange(coin, new BigDecimal("99.99"))).isFalse();
        assertThat(ScheduledPurchaseService.withinPriceRange(coin, new BigDecimal("200.01"))).isFalse();
    }

    @Test
    void singleSidedBounds() {
        assertThat(ScheduledPurchaseService.withinPriceRange(coin("100", "0"), new BigDecimal("1000000"))).isTrue();
        assertThat(ScheduledPurchaseService.withinPriceRange(coin("0", "100"), new BigDecimal("101"))).isFalse();
    }
}
