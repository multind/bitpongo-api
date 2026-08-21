package com.multind.bitpongo.infrastructure.persistence;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(properties = {
        "zhitoubao.jwt.secret-key=database-migration-test-secret",
        "zhitoubao.market.stream-enabled=false",
        "spring.quartz.auto-startup=false"
})
class EmptySchemaMigrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer(
            System.getProperty("test.mysql.image", "mysql:9.7.0"))
            .withDatabaseName("zhitoubao");

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void createsBusinessQuartzAndIdempotencySchema() {
        List<String> expectedTables = List.of(
                "user", "exchange", "strategy", "plan", "coin", "order", "snapshot", "dict",
                "order_intent",
                "QRTZ_JOB_DETAILS", "QRTZ_TRIGGERS", "QRTZ_LOCKS");

        for (String table : expectedTables) {
            Integer count = jdbc.queryForObject(
                    "select count(*) from information_schema.tables "
                            + "where table_schema = database() and lower(table_name) = lower(?)",
                    Integer.class,
                    table);
            assertThat(count).as("table %s", table).isEqualTo(1);
        }

        Integer orderIndex = jdbc.queryForObject(
                "select count(*) from information_schema.statistics "
                        + "where table_schema = database() and table_name = 'order' "
                        + "and index_name = 'uk_order_client_order_id' and non_unique = 0",
                Integer.class);
        Integer intentIndex = jdbc.queryForObject(
                "select count(*) from information_schema.statistics "
                        + "where table_schema = database() and table_name = 'order_intent' "
                        + "and index_name = 'uk_order_intent_client_order_id' and non_unique = 0",
                Integer.class);

        assertThat(orderIndex).isEqualTo(1);
        assertThat(intentIndex).isEqualTo(1);

        Integer lifecycleColumns = jdbc.queryForObject(
                "select count(*) from information_schema.columns "
                        + "where table_schema = database() and table_name = 'user' "
                        + "and column_name in ('status', 'deleted_at')",
                Integer.class);
        Integer authProviderColumns = jdbc.queryForObject(
                "select count(*) from information_schema.columns "
                        + "where table_schema = database() and table_name = 'user' "
                        + "and column_name = 'auth_provider'",
                Integer.class);
        Integer externalIdentityTables = jdbc.queryForObject(
                "select count(*) from information_schema.tables "
                        + "where table_schema = database() and table_name = 'deleted_external_identity'",
                Integer.class);

        assertThat(lifecycleColumns).isEqualTo(2);
        assertThat(authProviderColumns).isZero();
        assertThat(externalIdentityTables).isZero();
    }
}
