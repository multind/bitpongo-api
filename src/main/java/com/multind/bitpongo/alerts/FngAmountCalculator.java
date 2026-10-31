package com.multind.bitpongo.alerts;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Turns the Fear & Greed index into a suggested order amount.
 *
 * <p>The strategy has exactly two trigger bands: the lower band
 * {@code [lowerLimit - lowerInterval, lowerLimit]} and the upper band
 * {@code [upperLimit, upperLimit + upperInterval]}. Entering the lower band multiplies the
 * base amount by {@code 1 + increaseRatio%}; entering the upper band multiplies it by
 * {@code 1 - increaseRatio%}. Every other value keeps the base amount.</p>
 */
public final class FngAmountCalculator {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal MIN_MULTIPLIER = new BigDecimal("0.1");
    private static final BigDecimal MAX_MULTIPLIER = BigDecimal.TEN;

    private FngAmountCalculator() {}

    public record Plan(BigDecimal multiplier, BigDecimal suggestedAmount, FngZone zone) {}

    public static Plan plan(
            BigDecimal basicAmount,
            BigDecimal increaseRatio,
            int lowerLimit,
            int lowerInterval,
            int upperLimit,
            int upperInterval,
            int index) {
        if (basicAmount == null || basicAmount.signum() <= 0) {
            throw new IllegalArgumentException("basic amount must be positive");
        }
        if (lowerLimit >= upperLimit || index < 0 || index > 100) {
            throw new IllegalArgumentException("invalid F&G range");
        }
        BigDecimal ratio = increaseRatio == null ? BigDecimal.ZERO : increaseRatio;
        FngZone zone = FngZone.of(index, lowerLimit, lowerInterval, upperLimit, upperInterval);
        BigDecimal change = ratio.divide(HUNDRED, 8, RoundingMode.HALF_UP);
        BigDecimal multiplier = switch (zone) {
            case LOWER -> BigDecimal.ONE.add(change);
            case UPPER -> BigDecimal.ONE.subtract(change);
            default -> BigDecimal.ONE;
        };
        if (multiplier.compareTo(MIN_MULTIPLIER) < 0) multiplier = MIN_MULTIPLIER;
        if (multiplier.compareTo(MAX_MULTIPLIER) > 0) multiplier = MAX_MULTIPLIER;
        BigDecimal suggested = basicAmount.multiply(multiplier).setScale(8, RoundingMode.HALF_UP).stripTrailingZeros();
        return new Plan(multiplier.stripTrailingZeros(), suggested, zone);
    }
}