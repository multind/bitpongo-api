package com.multind.zhitoubao.scheduler;

import java.time.Instant;

public interface ScheduledPurchaseUseCase {
    void execute(long planId, Instant scheduledFireTime);
    void updateNextFireTime(long planId, Instant nextFireTime);
}
