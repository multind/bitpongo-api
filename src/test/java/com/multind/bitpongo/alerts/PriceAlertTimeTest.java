package com.multind.bitpongo.alerts;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PriceAlertTimeTest {
    @Test void databaseWallTimeIsUtcRegardlessOfJvmTimezone() {
        TimeZone previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            assertThat(PriceAlertService.instant(Timestamp.valueOf("2026-10-03 12:00:00")))
                    .isEqualTo(Instant.parse("2026-10-03T12:00:00Z"));
        } finally { TimeZone.setDefault(previous); }
    }
}
