package com.multind.bitpongo.exchange;

import java.math.BigDecimal;
import java.util.Map;

public record OrderResult(
        String symbol,
        String orderId,
        String clientOrderId,
        String status,
        BigDecimal quantity,
        BigDecimal totalCost,
        BigDecimal averagePrice,
        Map<String, BigDecimal> fees) {
    public OrderResult {
        fees = fees == null ? Map.of() : Map.copyOf(fees);
    }
}
