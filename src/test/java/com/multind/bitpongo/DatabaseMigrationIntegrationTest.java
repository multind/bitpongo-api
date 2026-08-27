package com.multind.bitpongo;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.TreeSet;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class DatabaseMigrationIntegrationTest {

    @Container
    static final MySQLContainer mysql = new MySQLContainer(
            System.getProperty("test.mysql.image", "mysql:9.7.0"))
            .withDatabaseName("zhitoubao");

    @Test
    void addsTimezoneContractWithoutShiftingLegacyDatetimes() throws Exception {
        flywayAt(MigrationVersion.fromVersion("10")).migrate();

        LocalDateTime legacyCreatedAt = LocalDateTime.of(2026, 8, 25, 13, 0);
        try (Connection connection = connection()) {
            connection.createStatement().executeUpdate("""
                    INSERT INTO `user` (id, name, email, password, created_at)
                    VALUES (1, 'legacy', 'legacy@example.com', 'hash', '2026-08-25 13:00:00')
                    """);
            connection.createStatement().executeUpdate("""
                    INSERT INTO exchange (id, name, exchange, user_id, created_at)
                    VALUES (1, 'legacy', 'binance', 1, '2026-08-25 13:00:00')
                    """);
            connection.createStatement().executeUpdate("""
                    INSERT INTO strategy
                        (id, name, instalment, exchange_id, frequency, cron, `condition`, user_id, created_at)
                    VALUES
                        (1, 'legacy', 10, 1, 'daily', '0 0 21 * * ?', 'none', 1, '2026-08-25 13:00:00')
                    """);
        }

        flywayAt(null).migrate();

        try (Connection connection = connection()) {
            assertThat(columns(connection, "strategy"))
                    .contains("schedule_timezone");
            assertThat(columns(connection, "user"))
                    .contains("display_timezone_mode", "display_timezone", "last_device_timezone");
            assertThat(queryString(connection,
                    "SELECT schedule_timezone FROM strategy WHERE id = 1"))
                    .isEqualTo("Asia/Shanghai");
            assertThat(queryString(connection,
                    "SELECT display_timezone_mode FROM `user` WHERE id = 1"))
                    .isEqualTo("FOLLOW_DEVICE");
            assertThat(queryTimestamp(connection,
                    "SELECT created_at FROM strategy WHERE id = 1").toLocalDateTime())
                    .isEqualTo(legacyCreatedAt);
        }
    }

    private Flyway flywayAt(MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    }

    private static Set<String> columns(Connection connection, String table) throws Exception {
        Set<String> columns = new TreeSet<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ?
                """)) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    columns.add(result.getString(1));
                }
            }
        }
        return columns;
    }

    private static String queryString(Connection connection, String sql) throws Exception {
        try (ResultSet result = connection.createStatement().executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private static Timestamp queryTimestamp(Connection connection, String sql) throws Exception {
        try (ResultSet result = connection.createStatement().executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getTimestamp(1);
        }
    }
}
