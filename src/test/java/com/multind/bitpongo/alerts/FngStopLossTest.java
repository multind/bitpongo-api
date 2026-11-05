package com.multind.bitpongo.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class FngStopLossTest {
    @Test
    void exitsWhenLossReachesStopLossPercent() {
        assertThat(FngExecutionService.hitsStopLoss(new BigDecimal("30"), new BigDecimal("-30"))).isTrue();
        assertThat(FngExecutionService.hitsStopLoss(new BigDecimal("30"), new BigDecimal("-45.5"))).isTrue();
        assertThat(FngExecutionService.hitsStopLoss(new BigDecimal("30"), new BigDecimal("-29.99"))).isFalse();
        assertThat(FngExecutionService.hitsStopLoss(new BigDecimal("30"), new BigDecimal("12"))).isFalse();
    }

    @Test
    void legacyStrategiesWithoutStopLossNeverStop() {
        assertThat(FngExecutionService.hitsStopLoss(null, new BigDecimal("-99"))).isFalse();
        assertThat(FngExecutionService.hitsStopLoss(BigDecimal.ZERO, new BigDecimal("-99"))).isFalse();
    }

    @Test
    void leverageIsCappedAtTwenty() {
        assertThat(FngStrategyService.MAX_LEVERAGE).isEqualTo(20);
    }
}
