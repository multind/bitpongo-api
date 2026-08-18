package com.multind.bitpongo.market;

import java.time.Duration;

public interface MarketTaskScheduler {
    Cancellable schedule(Runnable action, Duration delay);
}
