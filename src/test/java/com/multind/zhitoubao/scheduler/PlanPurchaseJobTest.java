package com.multind.zhitoubao.scheduler;

import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanPurchaseJobTest {
    @Test
    void recordsNextFireBeforePurchaseEvenWhenPurchaseFails() {
        ScheduledPurchaseUseCase purchases = mock(ScheduledPurchaseUseCase.class);
        JobExecutionContext context = mock(JobExecutionContext.class);
        JobDataMap data = new JobDataMap();
        data.put("planId", 42L);
        Instant scheduled = Instant.parse("2026-08-09T00:00:00Z");
        Instant next = Instant.parse("2026-08-10T00:00:00Z");
        when(context.getMergedJobDataMap()).thenReturn(data);
        when(context.getScheduledFireTime()).thenReturn(Date.from(scheduled));
        when(context.getNextFireTime()).thenReturn(Date.from(next));
        org.mockito.Mockito.doThrow(new IllegalStateException("boom"))
                .when(purchases).execute(42L, scheduled);

        assertThatThrownBy(() -> new PlanPurchaseJob(purchases).execute(context))
                .isInstanceOf(IllegalStateException.class);

        InOrder order = inOrder(purchases);
        order.verify(purchases).updateNextFireTime(42L, next);
        order.verify(purchases).execute(42L, scheduled);
    }
}
