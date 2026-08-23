package com.multind.bitpongo.infrastructure.persistence;

import com.multind.bitpongo.plan.PlanEntity;
import com.multind.bitpongo.plan.PlanRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(properties = {
        "zhitoubao.jwt.secret-key=database-migration-test-secret",
        "zhitoubao.notifications.bark.dispatch-enabled=false",
        "zhitoubao.market.stream-enabled=false",
        "spring.quartz.auto-startup=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LegacySchemaCompatibilityTest {

    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer(
            System.getProperty("test.mysql.image", "mysql:9.7.0"))
            .withDatabaseName("zhitoubao")
            .withInitScript("db/legacy-existing-schema.sql");

    @Autowired
    private PlanRepository plans;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void readsExistingPlanWithoutRecreatingBusinessTables() {
        PlanEntity plan = plans.findById(100L).orElseThrow();

        assertThat(plan.getStatus()).isEqualTo("active");
        assertThat(plan.getTotalFunds()).isEqualByComparingTo("100.50");
        assertThat(jdbc.queryForObject(
                "select status from user where id = 1", String.class)).isEqualTo("active");
        Integer authProviderColumns = jdbc.queryForObject(
                "select count(*) from information_schema.columns "
                        + "where table_schema = database() and table_name = 'user' "
                        + "and column_name = 'auth_provider'",
                Integer.class);
        assertThat(authProviderColumns).isZero();
    }
}
