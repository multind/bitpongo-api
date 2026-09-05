package com.multind.bitpongo.plan;

import com.multind.bitpongo.common.api.BusinessException;
import com.multind.bitpongo.strategy.CoinRepository;
import com.multind.bitpongo.strategy.StrategyEntity;
import com.multind.bitpongo.strategy.StrategyRepository;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlanRenameTest {
    private final PlanRepository plans = mock(PlanRepository.class);
    private final StrategyRepository strategies = mock(StrategyRepository.class);
    private final PlanApplicationService service = new PlanApplicationService(
            plans, strategies, mock(CoinRepository.class), mock(OrderRepository.class),
            mock(SnapshotRepository.class), mock(com.multind.bitpongo.exchange.ExchangeRepository.class),
            mock(com.multind.bitpongo.market.PriceCache.class), mock(PortfolioCalculator.class),
            null, Clock.systemUTC());

    @Test
    void renamesTheStrategyOwnedThroughThePlan() {
        PlanEntity plan = new PlanEntity();
        plan.setId(42L); plan.setUserId(7L); plan.setStrategyId(11L);
        StrategyEntity strategy = new StrategyEntity();
        strategy.setId(11L); strategy.setUserId(7L); strategy.setName("旧名称");
        when(plans.findByIdAndUserId(42L, 7L)).thenReturn(Optional.of(plan));
        when(strategies.findByIdAndUserId(11L, 7L)).thenReturn(Optional.of(strategy));

        service.rename(7L, 42L, "新名称");

        assertEquals("新名称", strategy.getName());
        verify(strategies).save(strategy);
    }

    @Test
    void cannotRenameAnotherUsersPlan() {
        when(plans.findByIdAndUserId(42L, 7L)).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> service.rename(7L, 42L, "新名称"));

        verify(strategies, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
