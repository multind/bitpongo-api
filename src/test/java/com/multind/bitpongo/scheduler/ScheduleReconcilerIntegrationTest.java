package com.multind.bitpongo.scheduler;

import com.multind.bitpongo.plan.PlanEntity;
import com.multind.bitpongo.plan.PlanRepository;
import com.multind.bitpongo.strategy.StrategyEntity;
import com.multind.bitpongo.strategy.StrategyRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduleReconcilerIntegrationTest {
    @Test
    void restoresOnlyActivePlansWithoutBackfillingOldExecutions() {
        PlanRepository plans = mock(PlanRepository.class);
        StrategyRepository strategies = mock(StrategyRepository.class);
        PlanScheduleService schedules = mock(PlanScheduleService.class);
        ObjectProvider<PlanScheduleService> schedulesProvider = mock(ObjectProvider.class);
        ObjectProvider<PlanRepository> plansProvider = mock(ObjectProvider.class);
        ObjectProvider<StrategyRepository> strategiesProvider = mock(ObjectProvider.class);
        when(schedulesProvider.getIfAvailable()).thenReturn(schedules);
        when(plansProvider.getIfAvailable()).thenReturn(plans);
        when(strategiesProvider.getIfAvailable()).thenReturn(strategies);
        PlanEntity active = plan(1L, "active", 11L);
        PlanEntity stopped = plan(2L, "stop", 12L);
        StrategyEntity strategy = new StrategyEntity(); strategy.setId(11L); strategy.setCron("0 8 * * *");
        when(plans.findAll()).thenReturn(List.of(active, stopped));
        when(strategies.findById(11L)).thenReturn(Optional.of(strategy));

        new ScheduleReconciler(plansProvider, strategiesProvider, schedulesProvider).reconcile();

        verify(schedules).schedule(1L, "0 0 8 * * ?");
        verify(schedules).remove(2L);
        verify(schedules, never()).resume(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
    }

    private PlanEntity plan(long id, String status, long strategyId) {
        PlanEntity plan = new PlanEntity(); plan.setId(id); plan.setStatus(status); plan.setStrategyId(strategyId); return plan;
    }
}
