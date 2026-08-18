package com.multind.bitpongo.infrastructure;

import java.sql.Connection;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("databaseConnectionHealth")
@ConditionalOnBean(DataSource.class)
public class DatabaseHealthIndicator implements HealthIndicator {
    private final DataSource dataSource;
    public DatabaseHealthIndicator(DataSource dataSource) { this.dataSource = dataSource; }

    @Override
    public Health health() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(2) ? Health.up().build()
                    : Health.down().withDetail("reason", "validation-failed").build();
        } catch (Exception exception) {
            return Health.down(exception).build();
        }
    }
}
