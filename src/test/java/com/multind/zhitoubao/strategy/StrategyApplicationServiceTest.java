package com.multind.zhitoubao.strategy;

import com.multind.zhitoubao.exchange.ExchangeEntity;
import com.multind.zhitoubao.exchange.ExchangeRepository;
import com.multind.zhitoubao.plan.PlanEntity;
import com.multind.zhitoubao.plan.PlanRepository;
import com.multind.zhitoubao.scheduler.PlanScheduleService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.multind.zhitoubao.strategy.StrategyDtos.CoinRequest;
import static com.multind.zhitoubao.strategy.StrategyDtos.StrategyCreateRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StrategyApplicationServiceTest {
    private final StrategyRepository strategies = mock(StrategyRepository.class);
    private final PlanRepository plans = mock(PlanRepository.class);
    private final CoinRepository coins = mock(CoinRepository.class);
    private final ExchangeRepository exchanges = mock(ExchangeRepository.class);
    private final PlanScheduleService schedules = mock(PlanScheduleService.class);
    private StrategyApplicationService service;

    @BeforeEach
    void setUp() {
        service = new StrategyApplicationService(strategies, plans, coins, exchanges, schedules,
                Clock.fixed(Instant.parse("2026-08-09T00:00:00Z"), ZoneOffset.UTC));
        ExchangeEntity exchange = new ExchangeEntity();
        exchange.setId(3L);
        exchange.setUserId(7L);
        when(exchanges.findByIdAndUserId(3L, 7L)).thenReturn(Optional.of(exchange));
        when(strategies.save(any())).thenAnswer(invocation -> {
            StrategyEntity value = invocation.getArgument(0);
            value.setId(11L);
            return value;
        });
        when(plans.save(any())).thenAnswer(invocation -> {
            PlanEntity value = invocation.getArgument(0);
            value.setId(12L);
            return value;
        });
        when(coins.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createsStrategyPlanAndCoinsForAuthenticatedUser() {
        var result = service.create(7L, request("0 8 * * *", new BigDecimal("60"), new BigDecimal("40")));

        assertThat(result.strategy().getUserId()).isEqualTo(7L);
        assertThat(result.plan().getStatus()).isEqualTo("active");
        assertThat(result.coins()).hasSize(2).allMatch(c -> c.getPlanId().equals(12L));
        verify(schedules).schedule(12L, "0 0 8 * * ?");
    }

    @Test
    void rejectsInvalidCronProportionsAndForeignExchange() {
        assertThatThrownBy(() -> service.create(7L, request("invalid", new BigDecimal("60"), new BigDecimal("40"))))
                .hasMessageContaining("Cron");
        assertThatThrownBy(() -> service.create(7L, request("0 8 * * *", new BigDecimal("70"), new BigDecimal("40"))))
                .hasMessageContaining("100");
        when(exchanges.findByIdAndUserId(3L, 7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(7L, request("0 8 * * *", new BigDecimal("60"), new BigDecimal("40"))))
                .hasMessageContaining("交易所不存在");
    }

    private StrategyCreateRequest request(String cron, BigDecimal first, BigDecimal second) {
        return new StrategyCreateRequest("每日定投", new BigDecimal("100"), 3L, "daily", cron,
                "last_average", List.of(
                new CoinRequest(first, "btc", BigDecimal.ZERO, BigDecimal.ZERO, true, "BTC", true),
                new CoinRequest(second, "eth", BigDecimal.ZERO, BigDecimal.ZERO, false, "ETH", true)));
    }
}
