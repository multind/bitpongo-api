package com.multind.zhitoubao.plan;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortfolioCalculatorTest {
    private final PortfolioCalculator calculator = new PortfolioCalculator();

    @Test
    void calculatesPortfolioWithBigDecimal() {
        BigDecimal value = calculator.value(List.of(
                new PortfolioCalculator.Position(new BigDecimal("0.0100"), new BigDecimal("62000")),
                new PortfolioCalculator.Position(new BigDecimal("0.5000"), new BigDecimal("3200"))));
        assertThat(value).isEqualByComparingTo("2220.0000");
        assertThat(calculator.ratio(value, new BigDecimal("2000"))).isEqualByComparingTo("11.0000");
        assertThat(calculator.revenue(value, new BigDecimal("2000"))).isEqualByComparingTo("220.0000");
    }

    @Test
    void zeroFundsReturnZeroAndMissingPriceIsRejected() {
        assertThat(calculator.ratio(BigDecimal.TEN, BigDecimal.ZERO)).isEqualByComparingTo("0.0000");
        assertThatThrownBy(() -> calculator.value(List.of(
                new PortfolioCalculator.Position(BigDecimal.ONE, null))))
                .hasMessageContaining("行情价格不可用");
    }
}
