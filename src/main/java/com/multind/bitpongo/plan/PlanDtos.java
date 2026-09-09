package com.multind.bitpongo.plan;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.multind.bitpongo.strategy.CoinEntity;
import com.multind.bitpongo.strategy.StrategyEntity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class PlanDtos {
    private PlanDtos() {}

    public record PlanRenameRequest(
            @NotBlank @Size(max = 100) String name) {}

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
            List<OrderView> items,
            int page,
            int size,
            long total,
            boolean hasMore) {}

    public record ExecutionPage(
            List<ExecutionView> items,
            int page,
            int size,
            long total,
            boolean hasMore) {}

    public record ExecutionView(
            Long id,
            @JsonProperty("result_status") String resultStatus,
            String reason,
            List<String> symbols,
            @JsonProperty("scheduled_at") Instant scheduledAt,
            @JsonProperty("completed_at") Instant completedAt) {
        public static ExecutionView from(PlanExecutionEntity execution) {
            return new ExecutionView(
                    execution.getId(),
                    execution.getResultStatus(),
                    execution.getReason(),
                    execution.getSymbolList(),
                    execution.getScheduledAtInstant(),
                    execution.getCompletedAtInstant());
        }
    }

    public record OrderView(
            Long id,
            String symbol,
            String orderNo,
            String clientOrderId,
            BigDecimal totalAmount,
            BigDecimal averagePrice,
            BigDecimal totalCost,
            BigDecimal fee,
            Long userId,
            @JsonProperty("created_at") Instant createdAt,
            Long planId) {
        public static OrderView from(OrderEntity order) {
            return new OrderView(
                    order.getId(), order.getSymbol(), order.getOrderNo(), order.getClientOrderId(),
                    order.getTotalAmount(), order.getAveragePrice(), order.getTotalCost(),
                    order.getFee(), order.getUserId(), order.getCreatedAtInstant(), order.getPlanId());
        }
    }
}
