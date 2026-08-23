package com.multind.bitpongo.notification;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(properties = {
        "zhitoubao.jwt.secret-key=bark-persistence-contract-test-secret",
        "zhitoubao.market.stream-enabled=false",
        "spring.quartz.auto-startup=false"
})
class BarkPersistenceContractTest {

    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer(
            System.getProperty("test.mysql.image", "mysql:9.7.0"))
            .withDatabaseName("zhitoubao");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private UserBarkSettingRepository barkSettings;

    @Autowired
    private NotificationOutboxRepository outbox;

    @Test
    void migratesBarkSettingAndLeasedOutboxSchemaWithDispatchIndexes() {
        assertThat(columnsFor("user_bark_setting")).containsExactlyInAnyOrder(
                "user_id", "server_url", "device_key_ciphertext", "enabled", "locale", "timezone",
                "created_at", "updated_at");
        assertThat(columnsFor("notification_outbox")).containsExactlyInAnyOrder(
                "id", "event_type", "recipient_type", "user_id", "title_key", "body_payload",
                "dedupe_key", "priority", "status", "attempts", "next_attempt_at", "lease_until",
                "last_error", "created_at", "sent_at", "updated_at");
        assertThat(columnType("notification_outbox", "body_payload")).isEqualTo("json");
        assertThat(foreignKeyCount("user_bark_setting", "user_id")).isEqualTo(1);
        assertThat(foreignKeyCount("notification_outbox", "user_id")).isEqualTo(1);
        assertThat(indexColumns("notification_outbox", "uk_notification_outbox_dedupe"))
                .containsExactly("dedupe_key");
        assertThat(indexIsUnique("notification_outbox", "uk_notification_outbox_dedupe")).isTrue();
        assertThat(indexColumns("notification_outbox", "ix_notification_outbox_dispatch"))
                .containsExactly("status", "next_attempt_at", "lease_until");
        assertThat(indexColumns("notification_outbox", "ix_notification_outbox_user"))
                .containsExactly("user_id", "status");
    }

    @Test
    @Transactional
    void persistsEncryptedBarkTargetAndJsonOutboxPayloadThroughRepositories() {
        long userId = createUser();
        LocalDateTime now = LocalDateTime.of(2026, 8, 23, 12, 0);

        UserBarkSettingEntity setting = new UserBarkSettingEntity();
        setting.setUserId(userId);
        setting.setServerUrl("https://api.day.app");
        setting.setDeviceKeyCiphertext("encrypted-device-key");
        setting.setEnabled(true);
        setting.setLocale("zh-CN");
        setting.setTimezone("Asia/Shanghai");
        barkSettings.saveAndFlush(setting);

        NotificationOutboxEntity message = new NotificationOutboxEntity();
        message.setEventType(NotificationEventType.TRADE_FAILED);
        message.setRecipientType(NotificationRecipientType.USER);
        message.setUserId(userId);
        message.setTitleKey("notification.trade_failed.title");
        message.setBodyPayload(Map.of("symbol", "BTCUSDT", "reason", "insufficient_balance"));
        message.setDedupeKey("trade-failed-20260823-1");
        message.setPriority("HIGH");
        message.setStatus(NotificationOutboxStatus.PENDING);
        message.setAttempts(0);
        message.setNextAttemptAt(now);
        message.setCreatedAt(now);
        message.setUpdatedAt(now);
        NotificationOutboxEntity saved = outbox.saveAndFlush(message);

        entityManager.clear();

        UserBarkSettingEntity restoredSetting = barkSettings.findByUserId(userId).orElseThrow();
        NotificationOutboxEntity restoredMessage = outbox.findById(saved.getId()).orElseThrow();

        assertThat(restoredSetting.getDeviceKeyCiphertext()).isEqualTo("encrypted-device-key");
        assertThat(restoredSetting.getTimezone()).isEqualTo("Asia/Shanghai");
        assertThat(restoredMessage.getEventType()).isEqualTo(NotificationEventType.TRADE_FAILED);
        assertThat(restoredMessage.getRecipientType()).isEqualTo(NotificationRecipientType.USER);
        assertThat(restoredMessage.getStatus()).isEqualTo(NotificationOutboxStatus.PENDING);
        assertThat(restoredMessage.getBodyPayload()).containsEntry("symbol", "BTCUSDT");
        assertThat(outbox.findByDedupeKey("trade-failed-20260823-1").map(NotificationOutboxEntity::getId))
                .contains(saved.getId());
        assertThat(outbox.existsByDedupeKey("trade-failed-20260823-1")).isTrue();
        assertThat(jdbc.queryForObject(
                "select json_unquote(json_extract(body_payload, '$.reason')) from notification_outbox where id = ?",
                String.class,
                saved.getId())).isEqualTo("insufficient_balance");
    }

    @Test
    void exposesStableNotificationEventTypesForStoredOutboxRows() {
        assertThat(NotificationEventType.values()).extracting(eventType -> eventType.name()).containsExactly(
                "SCHEDULER_FATAL", "ORDER_MANUAL_REVIEW", "TRADE_FAILED", "MARKET_OUTAGE",
                "PLAN_EXECUTION_SKIPPED", "TRADE_SUCCEEDED", "ASSET_SNAPSHOT_FAILED",
                "SYSTEM_RECOVERED", "SERVICE_STARTED", "BARK_TEST");
    }

    private long createUser() {
        jdbc.update("insert into user (name, email, password) values (?, ?, ?)",
                "Bark Test", "bark-test@example.com", "secret");
        return jdbc.queryForObject("select id from user where email = ?", Long.class, "bark-test@example.com");
    }

    private java.util.List<String> columnsFor(String table) {
        return jdbc.queryForList(
                "select column_name from information_schema.columns "
                        + "where table_schema = database() and table_name = ? order by ordinal_position",
                String.class,
                table);
    }

    private String columnType(String table, String column) {
        return jdbc.queryForObject(
                "select data_type from information_schema.columns "
                        + "where table_schema = database() and table_name = ? and column_name = ?",
                String.class,
                table,
                column);
    }

    private Integer foreignKeyCount(String table, String column) {
        return jdbc.queryForObject(
                "select count(*) from information_schema.key_column_usage "
                        + "where table_schema = database() and table_name = ? and column_name = ? "
                        + "and referenced_table_name = 'user' and referenced_column_name = 'id'",
                Integer.class,
                table,
                column);
    }

    private java.util.List<String> indexColumns(String table, String index) {
        return jdbc.queryForList(
                "select column_name from information_schema.statistics "
                        + "where table_schema = database() and table_name = ? and index_name = ? "
                        + "order by seq_in_index",
                String.class,
                table,
                index);
    }

    private boolean indexIsUnique(String table, String index) {
        Integer nonUnique = jdbc.queryForObject(
                "select max(non_unique) from information_schema.statistics "
                        + "where table_schema = database() and table_name = ? and index_name = ?",
                Integer.class,
                table,
                index);
        return nonUnique != null && nonUnique == 0;
    }
}
