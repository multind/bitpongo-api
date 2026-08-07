package com.multind.zhitoubao.market;

import java.time.Duration;

public interface MarketTaskScheduler {
    Cancellable schedule(Runnable action, Duration delay);
}
