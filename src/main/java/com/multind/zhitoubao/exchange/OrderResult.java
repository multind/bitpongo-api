package com.multind.zhitoubao.exchange;

import java.math.BigDecimal;

public record OrderResult(
        String symbol,
        String orderId,
        String clientOrderId,
        String status,
        BigDecimal quantity,
        BigDecimal totalCost,
        BigDecimal averagePrice) {}
