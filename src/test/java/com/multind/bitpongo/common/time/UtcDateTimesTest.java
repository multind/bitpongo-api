package com.multind.bitpongo.common.time;

import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UtcDateTimesTest {

    @Test
    void convertsLegacyDatetimeOnlyThroughUtc() {
        LocalDateTime stored = LocalDateTime.of(2026, 8, 25, 13, 0);

        assertThat(UtcDateTimes.toInstant(stored))
                .isEqualTo(Instant.parse("2026-08-25T13:00:00Z"));
        assertThat(UtcDateTimes.toDatabase(Instant.parse("2026-08-25T13:00:00Z")))
                .isEqualTo(stored);
    }

    @Test
    void preservesNullAtTheCompatibilityBoundary() {
        assertThat(UtcDateTimes.toInstant(null)).isNull();
        assertThat(UtcDateTimes.toDatabase(null)).isNull();
    }
}
