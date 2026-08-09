package com.multind.zhitoubao.plan;

import com.multind.zhitoubao.common.api.BusinessException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class PortfolioCalculator {
    private static final int SCALE = 4;

    public record Position(BigDecimal amount, BigDecimal price) {}

    public BigDecimal value(List<Position> positions) {
        BigDecimal result = BigDecimal.ZERO;
        for (Position position : positions) {
            if (position.price() == null) {
                throw new BusinessException(503, "行情价格不可用");
            }
            BigDecimal amount = position.amount() == null ? BigDecimal.ZERO : position.amount();
            result = result.add(amount.multiply(position.price()));
        }
        return result.setScale(SCALE, RoundingMode.HALF_UP);
    }

    public BigDecimal revenue(BigDecimal value, BigDecimal invested) {
        return safe(value).subtract(safe(invested)).setScale(SCALE, RoundingMode.HALF_UP);
    }

    public BigDecimal ratio(BigDecimal value, BigDecimal invested) {
        BigDecimal funds = safe(invested);
        if (funds.signum() == 0) return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        return safe(value).subtract(funds)
                .multiply(new BigDecimal("100"))
                .divide(funds, SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
