package com.multind.bitpongo.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TimezoneAuditDocumentationTest {

    @Test
    void preflightHandlesDatabasesBeforeAndAfterV11() throws Exception {
        String sql = Files.readString(Path.of("scripts/audit-timezone-data.sql"));

        assertThat(sql).contains("information_schema.COLUMNS");
        assertThat(sql).contains("PREPARE strategy_audit");
        assertThat(sql).contains("NULL AS schedule_timezone");
        assertThat(sql).contains("schedule_timezone FROM strategy");
    }
}
