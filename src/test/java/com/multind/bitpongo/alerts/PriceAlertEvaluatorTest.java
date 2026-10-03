package com.multind.bitpongo.alerts;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PriceAlertEvaluatorTest {
    private boolean matches(String mode, String before, String after) {
        return PriceAlertEvaluator.matches(mode, before == null ? null : new BigDecimal(before),
                new BigDecimal(after), new BigDecimal("100"), new BigDecimal("90"), new BigDecimal("110"));
    }
    @Test void crossingRequiresPreviousSampleAndDoesNotRepeatAtBoundary() {
        assertThat(matches("CROSS", null, "101")).isFalse();
        assertThat(matches("CROSS", "99", "100")).isTrue();
        assertThat(matches("CROSS", "100", "100")).isFalse();
        assertThat(matches("CROSS", "101", "100")).isTrue();
    }
    @Test void respectsCrossingDirection() {
        assertThat(matches("CROSS_UP", "99", "101")).isTrue();
        assertThat(matches("CROSS_UP", "101", "99")).isFalse();
        assertThat(matches("CROSS_DOWN", "101", "99")).isTrue();
        assertThat(matches("CROSS_DOWN", "99", "101")).isFalse();
    }
    @Test void greaterAndLessAreStrict() {
        assertThat(matches("ABOVE", null, "101")).isTrue();
        assertThat(matches("ABOVE", null, "100")).isFalse();
        assertThat(matches("BELOW", null, "99")).isTrue();
        assertThat(matches("BELOW", null, "100")).isFalse();
    }
    @Test void detectsLeavingThePriceChannel() {
        assertThat(matches("CHANNEL_OUTSIDE", "100", "111")).isTrue();
        assertThat(matches("CHANNEL_OUTSIDE", "100", "89")).isTrue();
        assertThat(matches("CHANNEL_OUTSIDE", "111", "112")).isFalse();
        assertThat(matches("CHANNEL_OUTSIDE", "100", "110")).isFalse();
    }
}
