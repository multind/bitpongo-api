package com.multind.zhitoubao.plan;

import com.multind.zhitoubao.strategy.CoinEntity;
import com.multind.zhitoubao.strategy.StrategyEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class PlanDtos {
    private PlanDtos() {}

    public record PlanView(
            Long id,
            BigDecimal totalFunds,
            BigDecimal totalRevenue,
            BigDecimal totalRatio,
            BigDecimal totalValue,
            LocalDateTime nextTime,
            String status,
            Long userId,
            Integer triggeredCount,
            LocalDateTime createdAt,
            StrategyEntity strategy,
            List<CoinEntity> coins,
            List<OrderEntity> orders,
            List<SnapshotEntity> snapshots) {}
}
