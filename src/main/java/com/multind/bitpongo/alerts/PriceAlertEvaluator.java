package com.multind.bitpongo.alerts;

import java.math.BigDecimal;

public final class PriceAlertEvaluator {
    private PriceAlertEvaluator() {}
    public static boolean matches(String mode, BigDecimal previous, BigDecimal current,
            BigDecimal target, BigDecimal lower, BigDecimal upper) {
        return switch (mode) {
            case "ABOVE" -> current.compareTo(target) > 0;
            case "BELOW" -> current.compareTo(target) < 0;
            case "CROSS_UP" -> previous != null && previous.compareTo(target) < 0 && current.compareTo(target) >= 0;
            case "CROSS_DOWN" -> previous != null && previous.compareTo(target) > 0 && current.compareTo(target) <= 0;
            case "CROSS" -> previous != null && ((previous.compareTo(target) < 0 && current.compareTo(target) >= 0)
                    || (previous.compareTo(target) > 0 && current.compareTo(target) <= 0));
            case "CHANNEL_OUTSIDE" -> previous != null && previous.compareTo(lower) >= 0 && previous.compareTo(upper) <= 0
                    && (current.compareTo(lower) < 0 || current.compareTo(upper) > 0);
            default -> throw new IllegalArgumentException("Unsupported alert condition");
        };
    }
}
