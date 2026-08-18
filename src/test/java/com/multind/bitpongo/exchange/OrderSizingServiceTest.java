package com.multind.bitpongo.exchange;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderSizingServiceTest {
    private final OrderSizingService service = new OrderSizingService();

    @Test
    void calculatesProportionAndRoundsDownToStepSize() {
        MarketRules rules = rules("10", "0.0001", "0.0001", "1000");
        assertThat(service.calculate(bd("100"), bd("50"), bd("24000"), rules))
                .isEqualByComparingTo("0.0020");
    }

    @Test
    void raisesToMinimumNotionalAndRoundsUpToTradableStep() {
        MarketRules rules = rules("10", "0.0001", "0.0001", "1000");
        assertThat(service.calculate(bd("5"), bd("100"), bd("62000"), rules))
                .isEqualByComparingTo("0.0002");
    }

    @Test
    void rejectsInvalidInputsAndImpossibleLimits() {
        MarketRules rules = rules("10", "0.1", "0.1", "0.1");
        assertThatThrownBy(() -> service.calculate(bd("10"), bd("0"), bd("1"), rules))
                .hasMessageContaining("比例");
        assertThatThrownBy(() -> service.calculate(bd("10"), bd("101"), bd("1"), rules))
                .hasMessageContaining("比例");
        assertThatThrownBy(() -> service.calculate(bd("10"), bd("100"), BigDecimal.ZERO, rules))
                .hasMessageContaining("价格");
        assertThatThrownBy(() -> service.calculate(bd("10"), bd("100"), bd("1"), rules))
                .hasMessageContaining("最低成交额");
    }

    private static MarketRules rules(String minNotional, String step, String min, String max) {
        return new MarketRules(bd(minNotional), bd(step), bd(min), bd(max));
    }

    private static BigDecimal bd(String value) { return new BigDecimal(value); }
}
