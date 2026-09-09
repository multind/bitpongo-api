package com.multind.bitpongo.plan;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlanExecutionHistoryServiceTest {
    @Test
    void returnsOnlyOwnedPlanHistoryNewestFirst() {
        PlanRepository plans = mock(PlanRepository.class);
        PlanExecutionRepository executions = mock(PlanExecutionRepository.class);
        PlanEntity plan = new PlanEntity();
        plan.setId(42L); plan.setUserId(7L);
        when(plans.findByIdAndUserId(42L, 7L)).thenReturn(Optional.of(plan));
        PlanExecutionEntity execution = new PlanExecutionEntity();
        execution.setId(15L);
        execution.setPlanId(42);
        execution.setResultStatus("SKIPPED");
        execution.setReason("PRICE_NOT_BELOW_AVERAGE");
        execution.setSymbols("BTCUSDT,ETHUSDT");
        execution.setScheduledFireTime(LocalDateTime.parse("2026-09-08T13:00:00"));
        execution.setCompletedAt(LocalDateTime.parse("2026-09-08T13:00:01"));
        when(executions.findByPlanId(eq(42), any(Pageable.class))).thenReturn(
                new PageImpl<>(List.of(execution)));

        PlanDtos.ExecutionPage result =
                new PlanExecutionHistoryService(plans, executions).list(7L, 42L, 0, 20);

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.resultStatus()).isEqualTo("SKIPPED");
            assertThat(item.reason()).isEqualTo("PRICE_NOT_BELOW_AVERAGE");
            assertThat(item.symbols()).containsExactly("BTCUSDT", "ETHUSDT");
            assertThat(item.scheduledAt()).hasToString("2026-09-08T13:00:00Z");
        });
        verify(plans).findByIdAndUserId(42L, 7L);
        verify(executions).findByPlanId(eq(42), any(Pageable.class));
    }
}
