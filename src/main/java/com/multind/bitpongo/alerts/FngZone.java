package com.multind.bitpongo.alerts;

/**
 * The two configured F&G trigger bands. Only one band exists on each side of the range:
 *
 * <ul>
 *   <li>lower band: {@code [lowerLimit - lowerInterval, lowerLimit]}</li>
 *   <li>upper band: {@code [upperLimit, upperLimit + upperInterval]}</li>
 * </ul>
 *
 * Values between the limits are {@link #INSIDE}; values outside both bands are {@link #OUTSIDE}.
 */
public enum FngZone {
    LOWER,
    UPPER,
    INSIDE,
    OUTSIDE;

    public static FngZone of(int index, int lowerLimit, int lowerInterval, int upperLimit, int upperInterval) {
        int lowerStart = Math.max(0, lowerLimit - lowerInterval);
        int upperEnd = Math.min(100, upperLimit + upperInterval);
        if (index >= lowerStart && index <= lowerLimit) return LOWER;
        if (index >= upperLimit && index <= upperEnd) return UPPER;
        if (index > lowerLimit && index < upperLimit) return INSIDE;
        return OUTSIDE;
    }
}