package com.multind.bitpongo.alerts;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FngZoneTest {
    @Test void recognisesTheTwoConfiguredBands() {
        assertThat(FngZone.of(20, 25, 5, 75, 5)).isEqualTo(FngZone.LOWER);
        assertThat(FngZone.of(22, 25, 5, 75, 5)).isEqualTo(FngZone.LOWER);
        assertThat(FngZone.of(25, 25, 5, 75, 5)).isEqualTo(FngZone.LOWER);
        assertThat(FngZone.of(50, 25, 5, 75, 5)).isEqualTo(FngZone.INSIDE);
        assertThat(FngZone.of(75, 25, 5, 75, 5)).isEqualTo(FngZone.UPPER);
        assertThat(FngZone.of(78, 25, 5, 75, 5)).isEqualTo(FngZone.UPPER);
        assertThat(FngZone.of(80, 25, 5, 75, 5)).isEqualTo(FngZone.UPPER);
    }

    @Test void treatsValuesOutsideBothBandsAsOutside() {
        assertThat(FngZone.of(19, 25, 5, 75, 5)).isEqualTo(FngZone.OUTSIDE);
        assertThat(FngZone.of(81, 25, 5, 75, 5)).isEqualTo(FngZone.OUTSIDE);
    }

    @Test void clampsBandsToTheZeroToOneHundredIndexRange() {
        assertThat(FngZone.of(0, 2, 5, 75, 5)).isEqualTo(FngZone.LOWER);
        assertThat(FngZone.of(100, 25, 5, 99, 5)).isEqualTo(FngZone.UPPER);
    }
}