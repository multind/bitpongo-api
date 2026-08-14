package com.multind.zhitoubao.infrastructure.persistence;

import com.multind.zhitoubao.plan.PlanEntity;
import com.multind.zhitoubao.plan.PlanRepository;
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
                "select auth_provider from user where id = 1", String.class)).isEqualTo("local");
        assertThat(jdbc.queryForObject(
                "select status from user where id = 1", String.class)).isEqualTo("active");
    }
}
