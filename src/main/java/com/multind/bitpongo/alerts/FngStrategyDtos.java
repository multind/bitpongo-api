package com.multind.bitpongo.alerts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

public final class FngStrategyDtos {
    private FngStrategyDtos() {}

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Create(
            String name,
            String exchange,
            Long exchangeId,
            String symbol,
            Integer upperLimitValue,
            Integer upperLimitInterval,
            Integer lowerLimitValue,
            Integer lowerLimitInterval,
            BigDecimal basicAmount,
            BigDecimal increaseRatio,
            Integer leverage,
            BigDecimal maxTradeRounds,
            String direction,
            // Exit when unrealized profit reaches this percentage.
            BigDecimal profitTargetPercent,
            // Trailing stop relative to peak profit: peak 20% with 5% drawdown exits at 19%.
            BigDecimal profitDrawdownPercent,
            // Stop loss: exit when the loss reaches this percentage of the position margin.
            BigDecimal stopLossPercent,
            String profitTargetType,
            // Maximum number of open orders (not total historical orders).
            Integer maxOrders,
            // Maximum number of concurrently open positions.
            Integer maxPositions) {}

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record View(
            long id,
            String name,
            String exchange,
            Long exchangeId,
            String symbol,
            int upperLimitValue,
            int upperLimitInterval,
            int lowerLimitValue,
            int lowerLimitInterval,
            String basicAmount,
            String increaseRatio,
            int leverage,
            Integer maxTradeRounds,
            String direction,
            String profitTargetPercent,
            String profitDrawdownPercent,
            String stopLossPercent,
            String profitTargetType,
            int maxOrders,
            int maxPositions,
            boolean enabled,
            long completedTradeRounds,
            Instant createdAt) {}

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Position(
            long id,
            String symbol,
            String side,
            String status,
            String quantity,
            String remainingQuantity,
            String margin,
            String entryPrice,
            String exitPrice,
            String realizedPnl,
            String pnlPercent,
            int leverage,
            String closeReason,
            Instant openedAt,
            Instant closedAt) {}

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record PositionPage(List<Position> items, int page, int size, long total, boolean hasMore) {}

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Execution(
            long id,
            long positionId,
            String event,
            String status,
            String symbol,
            String side,
            String price,
            String quantity,
            String reason,
            Instant occurredAt) {}

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record ExecutionPage(List<Execution> items, int page, int size, long total, boolean hasMore) {}

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Performance(
            String totalMargin,
            String realizedPnl,
            String winRate,
            long totalTrades,
            long openPositions,
            long closedPositions,
            List<Point> curve) {}

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Point(Instant time, String pnl) {}
}
