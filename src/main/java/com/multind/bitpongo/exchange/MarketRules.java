package com.multind.bitpongo.exchange;

import java.math.BigDecimal;

public record MarketRules(
        BigDecimal minimumNotional,
        BigDecimal stepSize,
        BigDecimal minimumQuantity,
        BigDecimal maximumQuantity) {
    public MarketRules {
        if (minimumNotional == null || minimumNotional.signum() < 0
                || stepSize == null || stepSize.signum() <= 0
                || minimumQuantity == null || minimumQuantity.signum() < 0
                || maximumQuantity == null || maximumQuantity.signum() <= 0
                || maximumQuantity.compareTo(minimumQuantity) < 0) {
            throw new IllegalArgumentException("交易规则无效");
        }
    }
}
