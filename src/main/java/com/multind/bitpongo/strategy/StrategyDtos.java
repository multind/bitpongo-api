package com.multind.bitpongo.strategy;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.multind.bitpongo.plan.PlanEntity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;

public final class StrategyDtos {
    private StrategyDtos() {}

    public record CoinRequest(
            @NotNull @Positive BigDecimal proportion,
            String icon,
            @NotNull BigDecimal min,
            @NotNull BigDecimal max,
            @JsonProperty("average_down") boolean averageDown,
            @NotBlank String symbol,
            boolean checked) {}

    public record StrategyCreateRequest(
            @NotBlank String name,
            @NotNull @Positive BigDecimal instalment,
            @JsonProperty("exchange_id") long exchangeId,
            String frequency,
            @NotBlank String cron,
            @JsonProperty("schedule_timezone") String scheduleTimezone,
            String condition,
            @NotEmpty List<@Valid CoinRequest> coins) {}

    public record StrategyCreatedData(
            StrategyEntity strategy,
            PlanEntity plan,
            List<CoinEntity> coins) {}
}
