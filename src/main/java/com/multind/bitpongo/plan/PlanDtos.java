package com.multind.bitpongo.plan;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.multind.bitpongo.strategy.CoinEntity;
import com.multind.bitpongo.strategy.StrategyEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class PlanDtos {
    private PlanDtos() {}

    public record PlanView(
            Long id,
            BigDecimal totalFunds,
            BigDecimal totalRevenue,
            BigDecimal totalRatio,
            BigDecimal totalValue,
            @JsonProperty("next_execution_at") Instant nextExecutionAt,
            @JsonProperty("next_time") Instant nextTime,
            String status,
            Long userId,
            Integer triggeredCount,
            Instant createdAt,
            StrategyEntity strategy,
            List<CoinEntity> coins,
            List<OrderEntity> orders,
            List<SnapshotEntity> snapshots) {}

    public record OrderPage(
            List<OrderEntity> items,
            int page,
            int size,
            long total,
            boolean hasMore) {}
}
