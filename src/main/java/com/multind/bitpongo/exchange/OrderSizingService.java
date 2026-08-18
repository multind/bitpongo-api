package com.multind.bitpongo.exchange;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;

@Service
public class OrderSizingService {
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    public BigDecimal calculate(
            BigDecimal instalment,
            BigDecimal proportion,
            BigDecimal price,
            MarketRules rules) {
        requirePositive(instalment, "定投金额");
        requirePositive(price, "价格");
        if (proportion == null || proportion.signum() <= 0
                || proportion.compareTo(ONE_HUNDRED) > 0) {
            throw new IllegalArgumentException("币种比例必须大于 0 且不超过 100");
        }

        BigDecimal allocated = instalment.multiply(proportion)
                .divide(ONE_HUNDRED, 18, RoundingMode.DOWN);
        BigDecimal spend = allocated.max(rules.minimumNotional());
        BigDecimal target = spend.divide(price, 18, RoundingMode.UP)
                .max(rules.minimumQuantity());
        BigDecimal quantity = alignDown(target, rules.stepSize());

        if (quantity.multiply(price).compareTo(rules.minimumNotional()) < 0
                || quantity.compareTo(rules.minimumQuantity()) < 0) {
            quantity = alignUp(target, rules.stepSize());
        }
        if (quantity.compareTo(rules.maximumQuantity()) > 0) {
            quantity = alignDown(rules.maximumQuantity(), rules.stepSize());
        }
        if (quantity.signum() <= 0
                || quantity.compareTo(rules.minimumQuantity()) < 0
                || quantity.multiply(price).compareTo(rules.minimumNotional()) < 0) {
            throw new IllegalArgumentException("交易规则限制下无法满足最低成交额");
        }
        return quantity.stripTrailingZeros();
    }

    private static BigDecimal alignDown(BigDecimal value, BigDecimal step) {
        return value.divide(step, 0, RoundingMode.DOWN).multiply(step);
    }

    private static BigDecimal alignUp(BigDecimal value, BigDecimal step) {
        return value.divide(step, 0, RoundingMode.UP).multiply(step);
    }

    private static void requirePositive(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(field + "必须大于 0");
        }
    }
}
