package com.multind.bitpongo.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multind.bitpongo.auth.AccountDeletionService;
import com.multind.bitpongo.auth.JwtTokenService;
import com.multind.bitpongo.auth.PasswordCompatibilityService;
import com.multind.bitpongo.auth.UserEntity;
import com.multind.bitpongo.auth.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = {
        "zhitoubao.jwt.secret-key=bark-persistence-contract-test-secret",
        "zhitoubao.notifications.bark.allowed-hosts=localhost",
        "zhitoubao.notifications.bark.allow-private-hosts=true",
        "zhitoubao.notifications.bark.credential-encryption-key="
                + "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        "zhitoubao.market.stream-enabled=false",
        "spring.quartz.auto-startup=false"
})
@AutoConfigureMockMvc
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

    @Autowired
    private UserBarkSettingService barkSettingService;

    @Autowired
    private UserRepository users;

    @Autowired
    private AccountDeletionService accountDeletion;

    @Autowired
    private PasswordCompatibilityService passwords;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private RecordingBarkClient recordingBarkClient;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JwtTokenService tokens;

    private final ObjectMapper json = new ObjectMapper();

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
        LocalDateTime now = LocalDateTime.of(2000, 1, 1, 0, 0);

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

    @Test
    @Transactional
    void createAndUpdateResponsesReturnDatabaseGeneratedUpdateTimeAndEncryptedTarget()
            throws Exception {
        long userId = createUser();

        MvcResult createdResult = mvc.perform(put("/api/users/notifications/bark")
                        .header("Authorization", bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"push_url":"https://localhost/obvious-fake-device-key",
                                 "enabled":true,"locale":"zh-CN","timezone":"Asia/Shanghai"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updated_at").isNotEmpty())
                .andExpect(jsonPath("$.data.masked_push_url").value("https://localhost/****-key"))
                .andReturn();
        LocalDateTime createdAt = responseUpdatedAt(createdResult);

        assertThat(createdAt).isNotNull();
        assertThat(createdResult.getResponse().getContentAsString())
                .doesNotContain("obvious-fake-device-key");
        entityManager.clear();
        UserBarkSettingEntity stored = barkSettings.findByUserId(userId).orElseThrow();
        assertThat(stored.getDeviceKeyCiphertext())
                .startsWith("v1:")
                .doesNotContain("obvious-fake-device-key");
        assertThat(stored.getUpdatedAt()).isEqualTo(createdAt);

        LocalDateTime oldUpdatedAt = LocalDateTime.of(2000, 1, 1, 0, 0);
        jdbc.update("update user_bark_setting set updated_at = ? where user_id = ?",
                oldUpdatedAt, userId);
        entityManager.clear();

        MvcResult updatedResult = mvc.perform(put("/api/users/notifications/bark")
                        .header("Authorization", bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":false,"locale":"en-US","timezone":"UTC"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updated_at").isNotEmpty())
                .andReturn();
        LocalDateTime updatedAt = responseUpdatedAt(updatedResult);

        assertThat(updatedAt).isAfter(oldUpdatedAt);
        assertThat(updatedAt).isEqualTo(jdbc.queryForObject(
                "select updated_at from user_bark_setting where user_id = ?",
                LocalDateTime.class,
                userId));
    }

    @Test
    void accountDeletionDeletesBarkSettingAndSkipsOnlyUnfinishedNotifications() {
        String originalPasswordHash = passwords.hash("secret");
        long userId = createUser("account-delete-bark@example.com", originalPasswordHash);
        LocalDateTime now = LocalDateTime.of(2000, 1, 1, 0, 0);
        UserBarkSettingEntity setting = new UserBarkSettingEntity();
        setting.setUserId(userId);
        setting.setServerUrl("https://api.day.app");
        setting.setDeviceKeyCiphertext("obvious-test-ciphertext");
        barkSettings.saveAndFlush(setting);

        NotificationOutboxEntity pending = message(userId, "delete-pending", now);
        NotificationOutboxEntity sending = message(userId, "delete-sending", now);
        sending.setStatus(NotificationOutboxStatus.SENDING);
        sending.setLeaseUntil(now.plusMinutes(1));
        NotificationOutboxEntity sent = message(userId, "delete-sent", now);
        sent.setStatus(NotificationOutboxStatus.SENT);
        outbox.saveAllAndFlush(java.util.List.of(pending, sending, sent));

        accountDeletion.delete(userId, "secret");

        UserEntity deletedUser = users.findById(userId).orElseThrow();
        assertThat(deletedUser.getStatus()).isEqualTo("deleted");
        assertThat(deletedUser.getName()).isEqualTo("已注销用户");
        assertThat(deletedUser.getEmail())
                .startsWith("deleted+" + userId + "+")
                .endsWith("@invalid.local");
        assertThat(deletedUser.getPassword()).isNotEqualTo(originalPasswordHash);
        assertThat(deletedUser.getDeletedAt()).isNotNull();
        assertThat(barkSettings.findByUserId(userId)).isEmpty();
        NotificationOutboxEntity skippedPending = outbox.findById(pending.getId()).orElseThrow();
        assertThat(skippedPending.getStatus()).isEqualTo(NotificationOutboxStatus.SKIPPED);
        assertThat(skippedPending.getUpdatedAt()).isAfter(now);
        NotificationOutboxEntity skippedSending = outbox.findById(sending.getId()).orElseThrow();
        assertThat(skippedSending.getStatus()).isEqualTo(NotificationOutboxStatus.SKIPPED);
        assertThat(skippedSending.getLeaseUntil()).isNull();
        assertThat(skippedSending.getUpdatedAt()).isAfter(now);
        assertThat(outbox.findById(sent.getId()).orElseThrow().getStatus())
                .isEqualTo(NotificationOutboxStatus.SENT);
        assertThat(outbox.findById(sent.getId()).orElseThrow().getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void testSendCallsExternalClientOutsideDatabaseTransaction() {
        long userId = createUser("bark-send-transaction@example.com", "unused-password");
        UserBarkSettingEntity setting = new UserBarkSettingEntity();
        setting.setUserId(userId);
        setting.setServerUrl("https://localhost");
        setting.setDeviceKeyCiphertext(new BarkCredentialCipher(
                "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=")
                .encrypt("fake-test-device-key"));
        setting.setEnabled(true);
        setting.setLocale("zh-CN");
        setting.setTimezone("Asia/Shanghai");
        barkSettings.saveAndFlush(setting);
        recordingBarkClient.reset();

        barkSettingService.sendTest(userId, null);

        assertThat(recordingBarkClient.wasCalled()).isTrue();
        assertThat(recordingBarkClient.transactionActiveDuringSend()).isFalse();
    }

    @Test
    void testSendSuspendsOuterTransactionForStoredAndTemporaryTargets() {
        long userId = createUser("bark-send-outer-transaction@example.com", "unused-password");
        UserBarkSettingEntity setting = new UserBarkSettingEntity();
        setting.setUserId(userId);
        setting.setServerUrl("https://localhost");
        setting.setDeviceKeyCiphertext(new BarkCredentialCipher(
                "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=")
                .encrypt("saved-fake-test-device-key"));
        setting.setEnabled(true);
        setting.setLocale("zh-CN");
        setting.setTimezone("Asia/Shanghai");
        barkSettings.saveAndFlush(setting);
        recordingBarkClient.reset();
        TransactionTemplate outerTransaction = new TransactionTemplate(transactionManager);

        outerTransaction.executeWithoutResult(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            barkSettingService.sendTest(userId, null);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            barkSettingService.sendTest(
                    userId, "https://localhost/temporary-fake-test-device-key");
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        });

        assertThat(recordingBarkClient.transactionStatesDuringSend())
                .containsExactly(false, false);
    }

    private long createUser() {
        return createUser("bark-test@example.com", "secret");
    }

    private long createUser(String email, String password) {
        jdbc.update("insert into user (name, email, password) values (?, ?, ?)",
                "Bark Test", email, password);
        return jdbc.queryForObject("select id from user where email = ?", Long.class, email);
    }

    private String bearer(long userId) {
        return "Bearer " + tokens.issue(userId);
    }

    private LocalDateTime responseUpdatedAt(MvcResult result) throws Exception {
        String value = json.readTree(result.getResponse().getContentAsByteArray())
                .at("/data/updated_at")
                .asText();
        return LocalDateTime.parse(value);
    }

    private static NotificationOutboxEntity message(
            long userId, String dedupeKey, LocalDateTime now) {
        NotificationOutboxEntity message = new NotificationOutboxEntity();
        message.setEventType(NotificationEventType.TRADE_FAILED);
        message.setRecipientType(NotificationRecipientType.USER);
        message.setUserId(userId);
        message.setTitleKey("notification.trade_failed.title");
        message.setBodyPayload(Map.of("symbol", "BTCUSDT"));
        message.setDedupeKey(dedupeKey);
        message.setPriority("HIGH");
        message.setStatus(NotificationOutboxStatus.PENDING);
        message.setAttempts(0);
        message.setNextAttemptAt(now);
        message.setCreatedAt(now);
        message.setUpdatedAt(now);
        return message;
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

    @TestConfiguration(proxyBeanMethods = false)
    static class RecordingBarkClientConfiguration {

        @Bean
        @Primary
        RecordingBarkClient recordingBarkClient() {
            return new RecordingBarkClient();
        }
    }

    static final class RecordingBarkClient implements BarkClient {
        private final List<Boolean> transactionStatesDuringSend = new ArrayList<>();

        @Override
        public void send(BarkTarget target, BarkMessage message) {
            transactionStatesDuringSend.add(
                    TransactionSynchronizationManager.isActualTransactionActive());
        }

        void reset() {
            transactionStatesDuringSend.clear();
        }

        boolean wasCalled() {
            return !transactionStatesDuringSend.isEmpty();
        }

        boolean transactionActiveDuringSend() {
            return transactionStatesDuringSend.getLast();
        }

        List<Boolean> transactionStatesDuringSend() {
            return List.copyOf(transactionStatesDuringSend);
        }
    }
}
