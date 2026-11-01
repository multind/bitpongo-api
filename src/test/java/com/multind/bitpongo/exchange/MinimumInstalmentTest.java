package com.multind.bitpongo.exchange;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MinimumInstalmentTest {
    private static Map<String, BigDecimal> notionals() {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        values.put("BTC", new BigDecimal("5"));
        values.put("ETH", new BigDecimal("5"));
        return values;
    }

    @Test
    void unevenProportionsRaiseTheMinimum() {
        // ETH gets 10%, so 5 USDT minimum needs a 50 USDT instalment.
        assertThat(ExchangeApplicationService.minimumInstalment(notionals(),
                Map.of("BTC", new BigDecimal("90"), "ETH", new BigDecimal("10"))))
                .isEqualByComparingTo("50");
    }

    @Test
    void evenSplitAndRoundingUp() {
        assertThat(ExchangeApplicationService.minimumInstalment(notionals(),
                Map.of("BTC", new BigDecimal("50"), "ETH", new BigDecimal("50"))))
                .isEqualByComparingTo("10");
        assertThat(ExchangeApplicationService.minimumInstalment(Map.of("BTC", new BigDecimal("5")),
                Map.of("BTC", new BigDecimal("30"))))
                .isEqualByComparingTo("17");
    }

    @Test
    void withoutProportionsFallsBackToSum() {
        assertThat(ExchangeApplicationService.minimumInstalment(notionals(), null)).isEqualByComparingTo("10");
    }
}
