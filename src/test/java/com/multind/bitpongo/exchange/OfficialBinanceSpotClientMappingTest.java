package com.multind.bitpongo.exchange;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OfficialBinanceSpotClientMappingTest {
    @Test
    void preservesZeroExecutedQuantityForOpenOrder() {
        OrderResult result = OfficialBinanceSpotClient.orderResult(
                "BTCUSDT", 99L, "client-1", "NEW", "0", "0.25", "0", "0");

        assertThat(result.quantity()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.totalCost()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
