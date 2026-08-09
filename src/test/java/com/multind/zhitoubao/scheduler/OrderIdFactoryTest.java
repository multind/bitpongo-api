package com.multind.zhitoubao.scheduler;

import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderIdFactoryTest {
    @Test
    void orderIdIsStableForSameScheduledFire() {
        OrderIdFactory factory = new OrderIdFactory();
        String first = factory.create(42L, "BTCUSDT", Instant.parse("2026-08-08T00:00:00Z"));
        String second = factory.create(42L, "BTCUSDT", Instant.parse("2026-08-08T00:00:00Z"));
        assertThat(first).isEqualTo(second).hasSizeLessThanOrEqualTo(36)
                .matches("[a-zA-Z0-9_-]+");
    }
}
