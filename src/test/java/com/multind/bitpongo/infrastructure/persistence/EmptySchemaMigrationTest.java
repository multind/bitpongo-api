package com.multind.bitpongo.infrastructure.persistence;

import com.multind.bitpongo.common.api.BusinessException;
import com.multind.bitpongo.preset.PresetDtos;
import com.multind.bitpongo.preset.PresetService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(properties = {
        "zhitoubao.jwt.secret-key=database-migration-test-secret",
        "zhitoubao.notifications.bark.dispatch-enabled=false",
        "zhitoubao.market.stream-enabled=false",
        "spring.quartz.auto-startup=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EmptySchemaMigrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer(
            System.getProperty("test.mysql.image", "mysql:9.7.0"))
            .withDatabaseName("zhitoubao");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PresetService presets;

    @Test
    @Transactional
    void withdrawnStrategiesArePrivateEvenWhenOfficial() {
        jdbc.update("insert into user (id,name,email,password) values (501,'Owner','plaza@example.com','test')");
        jdbc.update("insert into user (id,name,email,password) values (502,'Viewer','viewer@example.com','test')");
        jdbc.update("insert into exchange (id,name,exchange,user_id) values (501,'Spot','binance',501)");
        jdbc.update("insert into strategy (id,name,`condition`,user_id) values (501,'Private DCA','none',501)");
        jdbc.update("insert into plan (id,status,user_id,strategy_id,exchange_id) values (501,'active',501,501,501)");
        jdbc.update("""
                insert into fng_strategy (
                  id,user_id,name,exchange,symbol,upper_limit_value,upper_limit_interval,lower_limit_value,lower_limit_interval,
                  basic_amount,increase_ratio,leverage,bureau_period,direction,profit_target_percent,profit_drawdown_percent,
                  profit_target_type,max_orders,max_positions,valid_from,created_at,updated_at)
                values (501,501,'Private FNG','binance','BTCUSDT',80,1,20,1,10,1,1,1,'LONG',10,5,'FIXED',1,1,now(),now(),now())
                """);

        for (String type : List.of("DCA", "FNG")) {
            String key = type.toLowerCase(java.util.Locale.ROOT) + ":501";
            assertThat(plazaKeys()).doesNotContain(key);
            assertThat(presets.visibility(501, type, 501).visible()).isFalse();
            jdbc.update("insert into strategy_mark (strategy_type,strategy_id,official,updated_at) values (?,501,true,now())", type);
            assertThat(plazaKeys()).contains(key);
            assertThat(presets.visibility(501, type, 501).visible()).isTrue();
            presets.updateVisibility(501, type, 501, false);
            assertThat(plazaKeys()).doesNotContain(key);
            assertThatThrownBy(() -> presets.detail(502, key)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> presets.favorite(502, key)).isInstanceOf(BusinessException.class);
            presets.updateVisibility(501, type, 501, true);
            assertThat(plazaKeys()).contains(key);
            assertThat(presets.detail(502, key).key()).isEqualTo(key);
        }
    }

    private List<String> plazaKeys() {
        return presets.list(502, null, null, null, null, null, null, 0, 50)
                .items().stream().map(PresetDtos.Preset::key).toList();
    }

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
