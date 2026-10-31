package com.multind.bitpongo.alerts;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FngAmountCalculatorTest {
    private static FngAmountCalculator.Plan plan(BigDecimal basic, BigDecimal ratio, int index) {
        return FngAmountCalculator.plan(basic, ratio, 25, 5, 75, 5, index);
    }

    @Test void keepsTheBaseAmountOutsideTheTwoBands() {
        var result = plan(new BigDecimal("100"), new BigDecimal("10"), 50);
        assertThat(result.zone()).isEqualTo(FngZone.INSIDE);
        assertThat(result.multiplier()).isEqualByComparingTo("1");
        assertThat(result.suggestedAmount()).isEqualByComparingTo("100");
        var below = plan(new BigDecimal("100"), new BigDecimal("10"), 19);
        assertThat(below.zone()).isEqualTo(FngZone.OUTSIDE);
        assertThat(below.multiplier()).isEqualByComparingTo("1");
        var above = plan(new BigDecimal("100"), new BigDecimal("10"), 81);
        assertThat(above.zone()).isEqualTo(FngZone.OUTSIDE);
        assertThat(above.multiplier()).isEqualByComparingTo("1");
    }

    @Test void increasesAmountInsideTheLowerBand() {
        for (int index : new int[] {20, 22, 25}) {
            var result = plan(new BigDecimal("100"), new BigDecimal("10"), index);
            assertThat(result.zone()).isEqualTo(FngZone.LOWER);
            assertThat(result.multiplier()).isEqualByComparingTo("1.10");
            assertThat(result.suggestedAmount()).isEqualByComparingTo("110");
        }
    }

    @Test void decreasesAmountInsideTheUpperBand() {
        for (int index : new int[] {75, 78, 80}) {
            var result = plan(new BigDecimal("100"), new BigDecimal("10"), index);
            assertThat(result.zone()).isEqualTo(FngZone.UPPER);
            assertThat(result.multiplier()).isEqualByComparingTo("0.90");
            assertThat(result.suggestedAmount()).isEqualByComparingTo("90");
        }
    }

    @Test void zeroIncreaseRatioKeepsTheBaseAmount() {
        var lower = plan(new BigDecimal("100"), BigDecimal.ZERO, 20);
        assertThat(lower.zone()).isEqualTo(FngZone.LOWER);
        assertThat(lower.multiplier()).isEqualByComparingTo("1");
        assertThat(lower.suggestedAmount()).isEqualByComparingTo("100");
    }

    @Test void clampsExtremeMultipliers() {
        var high = FngAmountCalculator.plan(new BigDecimal("100"), new BigDecimal("1000"), 25, 5, 75, 5, 20);
        assertThat(high.multiplier()).isEqualByComparingTo("10");
        var low = FngAmountCalculator.plan(new BigDecimal("100"), new BigDecimal("200"), 25, 5, 75, 5, 76);
        assertThat(low.multiplier()).isEqualByComparingTo("0.1");
    }

    @Test void rejectsInvalidInputs() {
        assertThatThrownBy(() -> plan(BigDecimal.ZERO, BigDecimal.ONE, 50)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> plan(BigDecimal.ONE, BigDecimal.ONE, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}