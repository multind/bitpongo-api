# Bitpongo Bark 分级通知后端 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `bitpongo-api` 中以安全、可重试的 Bark 双通道通知系统完整替换钉钉，并将交易、定时任务、对账、快照和行情故障接入统一分级策略。

**Architecture:** 用户 Bark 目标加密存入 MySQL，管理员目标由环境变量提供；业务事件在状态提交后进入 MySQL Outbox，后台带租约发送并退避重试。`BarkEventPolicy` 是通知级别、铃声、分组和持续响铃的唯一来源，Bark 故障不得改变交易或调度状态。

**Tech Stack:** Java 26、Spring Boot 4.1.0、Spring MVC/Security/Data JPA/JDBC/Quartz、Flyway、MySQL、Java `HttpClient`、AES-256-GCM、JUnit 5、Mockito、AssertJ、MockWebServer、Testcontainers。

## Global Constraints

- 只修改 `/Volumes/ExternalDrive/Code/github/bitpongo-api`；旧 `zhitoubao` 仓库不在范围内。
- 所有 Maven 命令使用 `-Dmaven.repo.local=/Volumes/ExternalDrive/maven-repo/.m2/repository`。
- 删除 `POST /api/users/ding`、`GET /api/users/notices` 及所有钉钉实现，不保留并行兼容层。
- Bark Device Key 只允许存在于请求内存、AES-GCM 密文或管理员环境变量中；不得进入日志、响应、测试夹具、文档或 Git。
- 默认只允许 `api.day.app`；自建主机必须在 `BARK_ALLOWED_HOSTS` 中精确列出，私网目标还必须开启 `BARK_ALLOW_PRIVATE_HOSTS=true`。
- 业务通知必须先提交业务状态，再使用独立事务写 Outbox；入队或发送失败不得回滚交易、计划、快照或对账结果。
- 只有 `SCHEDULER_FATAL` 和 `ORDER_MANUAL_REVIEW` 使用 `level=critical`、`call=1`、`volume=10`、`sound=alarm`。
- 短暂 WebSocket 断开静默重连；连续不可用超过 `zhitoubao.market.health-max-silence` 才发送告警。
- 真实 Bark 地址只从进程环境读取；不在任何命令参数、报告或提交中展开。

---

### Task 1: 建立 Bark 与 Outbox 数据模型

**Files:**

- Create: `src/main/resources/db/migration/V6__bark_notification_outbox.sql`
- Create: `src/main/java/com/multind/bitpongo/notification/UserBarkSettingEntity.java`
- Create: `src/main/java/com/multind/bitpongo/notification/UserBarkSettingRepository.java`
- Create: `src/main/java/com/multind/bitpongo/notification/NotificationOutboxEntity.java`
- Create: `src/main/java/com/multind/bitpongo/notification/NotificationOutboxRepository.java`
- Create: `src/main/java/com/multind/bitpongo/notification/NotificationEventType.java`
- Create: `src/main/java/com/multind/bitpongo/notification/NotificationRecipientType.java`
- Create: `src/main/java/com/multind/bitpongo/notification/NotificationOutboxStatus.java`
- Test: `src/test/java/com/multind/bitpongo/notification/BarkPersistenceContractTest.java`

**Interfaces:**

- Produces: `UserBarkSettingRepository.findByUserId(long)`、`NotificationOutboxRepository`、三个稳定枚举，供后续设置服务和 Dispatcher 使用。
- Consumes: 现有 `user` 表及 Spring Data JPA。

- [ ] **Step 1: 编写失败的数据模型契约测试**

测试读取 Flyway SQL，并用反射验证实体字段及仓库签名：

```java
@Test
void migrationDefinesEncryptedUserTargetAndLeasedOutbox() throws Exception {
    String sql = Files.readString(Path.of("src/main/resources/db/migration/V6__bark_notification_outbox.sql"));
    assertThat(sql).contains("CREATE TABLE user_bark_setting", "device_key_ciphertext",
            "CREATE TABLE notification_outbox", "dedupe_key", "lease_until");
    assertThat(UserBarkSettingRepository.class.getMethod("findByUserId", long.class).getReturnType())
            .isEqualTo(Optional.class);
    assertThat(NotificationEventType.values()).extracting(Enum::name)
            .containsExactly("SCHEDULER_FATAL", "ORDER_MANUAL_REVIEW", "TRADE_FAILED",
                    "MARKET_OUTAGE", "PLAN_EXECUTION_SKIPPED", "TRADE_SUCCEEDED",
                    "ASSET_SNAPSHOT_FAILED", "SYSTEM_RECOVERED", "SERVICE_STARTED", "BARK_TEST");
}
```

- [ ] **Step 2: 运行测试并确认 RED**

Run:

```bash
./mvnw -Dmaven.repo.local=/Volumes/ExternalDrive/maven-repo/.m2/repository -Dtest=BarkPersistenceContractTest test
```

Expected: FAIL，提示 V6 文件及 Bark 实体/枚举不存在。

- [ ] **Step 3: 创建 Flyway 表与实体**

V6 必须包含：

```sql
CREATE TABLE user_bark_setting (
    user_id INT NOT NULL,
    server_url VARCHAR(255) NOT NULL,
    device_key_ciphertext VARCHAR(1024) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    locale VARCHAR(16) NOT NULL DEFAULT 'zh-CN',
    timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_user_bark_setting_user FOREIGN KEY (user_id) REFERENCES user(id)
) ENGINE=InnoDB;

CREATE TABLE notification_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_type VARCHAR(48) NOT NULL,
    recipient_type VARCHAR(16) NOT NULL,
    user_id INT NULL,
    title_key VARCHAR(96) NOT NULL,
    body_payload JSON NOT NULL,
    dedupe_key VARCHAR(191) NOT NULL,
    priority VARCHAR(24) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME NOT NULL,
    lease_until DATETIME NULL,
    last_error VARCHAR(512) NULL,
    created_at DATETIME NOT NULL,
    sent_at DATETIME NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_outbox_dedupe (dedupe_key),
    KEY ix_notification_outbox_dispatch (status, next_attempt_at, lease_until),
    KEY ix_notification_outbox_user (user_id, status),
    CONSTRAINT fk_notification_outbox_user FOREIGN KEY (user_id) REFERENCES user(id)
) ENGINE=InnoDB;
```

实体枚举字段使用 `EnumType.STRING`，`body_payload` 使用 `@JdbcTypeCode(SqlTypes.JSON)` 的 `Map<String, Object>`，时间统一使用 `LocalDateTime` UTC。

- [ ] **Step 4: 运行测试并确认 GREEN**

Run:

```bash
./mvnw -Dmaven.repo.local=/Volumes/ExternalDrive/maven-repo/.m2/repository -Dtest=BarkPersistenceContractTest test
```

Expected: PASS。

- [ ] **Step 5: 提交数据模型**

```bash
git add src/main/resources/db/migration/V6__bark_notification_outbox.sql src/main/java/com/multind/bitpongo/notification src/test/java/com/multind/bitpongo/notification/BarkPersistenceContractTest.java
git commit -m "feat: add Bark notification persistence"
```

---

### Task 2: 实现 Bark 地址安全解析与凭据加密

**Files:**

- Create: `src/main/java/com/multind/bitpongo/notification/BarkProperties.java`
- Create: `src/main/java/com/multind/bitpongo/notification/BarkTarget.java`
- Create: `src/main/java/com/multind/bitpongo/notification/BarkPushUrlParser.java`
- Create: `src/main/java/com/multind/bitpongo/notification/BarkCredentialCipher.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/resources/application-test.yml`
- Test: `src/test/java/com/multind/bitpongo/notification/BarkPushUrlParserTest.java`
- Test: `src/test/java/com/multind/bitpongo/notification/BarkCredentialCipherTest.java`

**Interfaces:**

- Produces: `BarkTarget(URI serverUrl, String deviceKey)`；`BarkPushUrlParser.parse(String)`；`BarkCredentialCipher.encrypt/decrypt`。
- Consumes: `zhitoubao.notifications.bark.*` 配置。

- [ ] **Step 1: 编写失败的解析与加密测试**

核心断言：

```java
@Test
void extractsOnlyServerAndFirstPathSegmentFromCopiedTestUrl() {
    BarkTarget target = parser(Set.of("api.day.app"), false)
            .parse("https://api.day.app/deviceKey/sample-title?call=1&sound=alarm");
    assertThat(target.serverUrl()).isEqualTo(URI.create("https://api.day.app"));
    assertThat(target.deviceKey()).isEqualTo("deviceKey");
}

@Test
void rejectsUntrustedAndPrivateTargets() {
    assertThatThrownBy(() -> parser(Set.of("api.day.app"), false)
            .parse("https://127.0.0.1/key"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("不受信任");
}

@Test
void aesGcmUsesRandomNonceAndDetectsTampering() {
    String first = cipher.encrypt("device-key");
    String second = cipher.encrypt("device-key");
    assertThat(first).startsWith("v1:").isNotEqualTo(second);
    assertThat(cipher.decrypt(first)).isEqualTo("device-key");
    assertThatThrownBy(() -> cipher.decrypt(first.substring(0, first.length() - 1) + "A"))
            .isInstanceOf(BusinessException.class);
}
```

- [ ] **Step 2: 运行测试并确认 RED**

```bash
./mvnw -Dmaven.repo.local=/Volumes/ExternalDrive/maven-repo/.m2/repository -Dtest=BarkPushUrlParserTest,BarkCredentialCipherTest test
```

Expected: FAIL，安全组件不存在。

- [ ] **Step 3: 实现配置、解析与加密**

`BarkProperties` 使用 `@ConfigurationProperties("zhitoubao.notifications.bark")`，字段为：

```java
public record BarkProperties(
        boolean userNotificationsEnabled,
        String adminPushUrl,
        Set<String> allowedHosts,
        boolean allowPrivateHosts,
        String credentialEncryptionKey,
        boolean notifyOnStartup,
        String appPublicUrl) {}
```

解析规则：HTTPS、无 userInfo/fragment；host 或 `host:port` 精确命中白名单；Key 为 URL 解码后的第一段非空路径；禁止跨主机重定向。加密使用 `AES/GCM/NoPadding`、随机 12-byte nonce、128-bit tag、独立 32-byte Base64 key，封装为 `v1:<base64(nonce+ciphertext+tag)>`。

`application.yml` 增加：

```yaml
zhitoubao:
  notifications:
    bark:
      user-notifications-enabled: ${BARK_USER_NOTIFICATIONS_ENABLED:true}
      admin-push-url: ${BARK_ADMIN_PUSH_URL:}
      allowed-hosts: ${BARK_ALLOWED_HOSTS:api.day.app}
      allow-private-hosts: ${BARK_ALLOW_PRIVATE_HOSTS:false}
      credential-encryption-key: ${BARK_CREDENTIAL_ENCRYPTION_KEY:}
      notify-on-startup: ${BARK_NOTIFY_ON_STARTUP:false}
      app-public-url: ${APP_PUBLIC_URL:}
```

测试 profile 使用固定的 32-byte Base64 测试密钥，不使用真实 Bark Key。

- [ ] **Step 4: 运行测试并确认 GREEN**

```bash
./mvnw -Dmaven.repo.local=/Volumes/ExternalDrive/maven-repo/.m2/repository -Dtest=BarkPushUrlParserTest,BarkCredentialCipherTest test
```

Expected: PASS。

- [ ] **Step 5: 提交安全基础设施**

```bash
git add src/main/java/com/multind/bitpongo/notification/BarkProperties.java src/main/java/com/multind/bitpongo/notification/BarkTarget.java src/main/java/com/multind/bitpongo/notification/BarkPushUrlParser.java src/main/java/com/multind/bitpongo/notification/BarkCredentialCipher.java src/main/resources/application.yml src/test/resources/application-test.yml src/test/java/com/multind/bitpongo/notification
git commit -m "feat: secure Bark notification targets"
```

---

### Task 3: 实现 Bark API 客户端和统一事件策略

**Files:**

- Delete: `src/main/java/com/multind/bitpongo/notification/DingTalkClient.java`
- Delete: `src/main/java/com/multind/bitpongo/notification/HttpDingTalkClient.java`
- Delete: `src/test/java/com/multind/bitpongo/notification/HttpDingTalkClientTest.java`
- Create: `src/main/java/com/multind/bitpongo/notification/BarkClient.java`
- Create: `src/main/java/com/multind/bitpongo/notification/HttpBarkClient.java`
- Create: `src/main/java/com/multind/bitpongo/notification/BarkMessage.java`
- Create: `src/main/java/com/multind/bitpongo/notification/BarkEventPolicy.java`
- Create: `src/main/java/com/multind/bitpongo/notification/NotificationEvent.java`
- Create: `src/main/java/com/multind/bitpongo/notification/NotificationMessageRenderer.java`
- Test: `src/test/java/com/multind/bitpongo/notification/HttpBarkClientTest.java`
- Test: `src/test/java/com/multind/bitpongo/notification/BarkEventPolicyTest.java`

**Interfaces:**

- Produces: `BarkClient.send(BarkTarget, BarkMessage)`；`BarkEventPolicy.policy(NotificationEventType)`；`NotificationEvent` 稳定事件载体。
- Consumes: Task 2 的 `BarkTarget`。

- [ ] **Step 1: 编写失败的客户端与策略测试**

MockWebServer 断言 `/push` JSON：

```java
@Test
void postsV2JsonWithoutPuttingDeviceKeyInUrl() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(200)
            .setBody("{\"code\":200,\"message\":\"success\"}"));
    client.send(new BarkTarget(server.url("/").uri(), "device-key"),
            new BarkMessage("Bitpongo", "测试", "active", null, false,
                    "minuet", "Bitpongo·测试", null));
    RecordedRequest request = server.takeRequest();
    assertThat(request.getPath()).isEqualTo("/push");
    assertThat(request.getBody().readUtf8()).contains("\"device_key\":\"device-key\"",
            "\"level\":\"active\"", "\"sound\":\"minuet\"");
}

@Test
void onlyCriticalPoliciesUseContinuousRinging() {
    assertThat(policy.policy(NotificationEventType.SCHEDULER_FATAL).call()).isTrue();
    assertThat(policy.policy(NotificationEventType.ORDER_MANUAL_REVIEW).call()).isTrue();
    assertThat(Arrays.stream(NotificationEventType.values())
            .filter(type -> !Set.of(NotificationEventType.SCHEDULER_FATAL,
                    NotificationEventType.ORDER_MANUAL_REVIEW).contains(type))
            .map(type -> policy.policy(type).call())).containsOnly(false);
}
```

- [ ] **Step 2: 运行测试并确认 RED**

```bash
./mvnw -Dmaven.repo.local=/Volumes/ExternalDrive/maven-repo/.m2/repository -Dtest=HttpBarkClientTest,BarkEventPolicyTest test
```

Expected: FAIL，新 Bark 类型不存在。

- [ ] **Step 3: 实现客户端、事件载体和策略**

使用记录类型：

```java
public record NotificationEvent(
        NotificationEventType type,
        Long userId,
        Long planId,
        Long intentId,
        Instant occurredAt,
        String dedupeKey,
        Map<String, Object> attributes) {}

public record BarkMessage(
        String title, String body, String level, Integer volume,
        boolean call, String sound, String group, String url) {}
```

`HttpBarkClient` 使用 5 秒 connect timeout、10 秒 request timeout、不自动跟随重定向；HTTP 非 2xx 或 JSON `code` 非 200 转为 `BusinessException(502, "Bark 通知发送失败")`。Renderer 根据 `zh-CN/zh-TW/en-US` 和 IANA timezone 输出标题正文，错误摘要先删除 URI、Bearer、access/secret/token/key 形态并截断为 300 字符。

- [ ] **Step 4: 运行测试并确认 GREEN**

```bash
./mvnw -Dmaven.repo.local=/Volumes/ExternalDrive/maven-repo/.m2/repository -Dtest=HttpBarkClientTest,BarkEventPolicyTest test
```

Expected: PASS，且策略矩阵逐项与设计一致。

- [ ] **Step 5: 提交客户端和策略**

```bash
git add src/main/java/com/multind/bitpongo/notification src/test/java/com/multind/bitpongo/notification
git commit -m "feat: replace DingTalk client with Bark"
```

---

### Task 4: 实现用户 Bark 设置 API 与注销清理

**Files:**

- Replace: `src/main/java/com/multind/bitpongo/notification/NotificationApplicationService.java`
- Replace: `src/main/java/com/multind/bitpongo/notification/NotificationController.java`
- Create: `src/main/java/com/multind/bitpongo/notification/UserBarkSettingService.java`
- Delete: `src/main/java/com/multind/bitpongo/notification/DictEntity.java`
- Delete: `src/main/java/com/multind/bitpongo/notification/DictRepository.java`
- Modify: `src/main/java/com/multind/bitpongo/auth/AccountDeletionService.java`
- Replace: `src/test/java/com/multind/bitpongo/notification/NotificationControllerContractTest.java`
- Create: `src/test/java/com/multind/bitpongo/notification/UserBarkSettingServiceTest.java`
- Modify: `src/test/java/com/multind/bitpongo/auth/AccountDeletionServiceTest.java`

**Interfaces:**

- Produces: GET/PUT/DELETE `/api/users/notifications/bark` 和 POST `/api/users/notifications/bark/test`。
- Consumes: Task 1 repository、Task 2 parser/cipher、Task 3 `BarkClient`。

- [ ] **Step 1: 编写失败的 API 与设置服务测试**

Controller 契约使用已认证用户 ID 7：

```java
mvc.perform(put("/api/users/notifications/bark")
        .header("Authorization", bearer())
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
                {"push_url":"https://api.day.app/device-key/test?call=1",
                 "enabled":true,"locale":"zh-CN","timezone":"Asia/Shanghai"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.configured").value(true))
        .andExpect(jsonPath("$.data.masked_push_url").value("https://api.day.app/****-key"));
```

服务测试捕获保存实体，断言 `deviceKeyCiphertext` 不含明文；GET 不返回明文；DELETE 调用 `deleteByUserId` 并跳过待发送记录；测试接口使用临时地址但不保存。

注销测试增加：

```java
verify(barkSettings).deleteForUser(userId);
```

- [ ] **Step 2: 运行测试并确认 RED**

```bash
./mvnw -Dmaven.repo.local=/Volumes/ExternalDrive/maven-repo/.m2/repository -Dtest=NotificationControllerContractTest,UserBarkSettingServiceTest,AccountDeletionServiceTest test
```

Expected: FAIL，旧接口仍是 `/ding`，Bark 设置服务不存在。

- [ ] **Step 3: 实现设置 API 并删除钉钉/字典路径**

Controller DTO：

```java
public record BarkSettingRequest(
        String pushUrl, Boolean enabled, String locale, String timezone) {}
public record BarkTestRequest(String pushUrl) {}
public record BarkSettingResponse(
        boolean configured, boolean enabled, String maskedPushUrl,
        String locale, String timezone, LocalDateTime updatedAt) {}
public record BarkTestResponse(boolean sent) {}
```

所有方法接收 `@AuthenticationPrincipal AuthenticatedUser user`，不可接受请求体 userId。创建时 `pushUrl` 必填；更新现有设置时可只改状态/语言/时区。locale 只允许三种值，timezone 使用 `ZoneId.of` 校验。测试发送固定使用 `BARK_TEST` 策略，不继承粘贴 URL 的查询参数。

`AccountDeletionService` 注入 `UserBarkSettingService`，在注销事务内删除配置并把用户的 PENDING/SENDING 通知改为 SKIPPED。

- [ ] **Step 4: 运行测试并确认 GREEN**

```bash
./mvnw -Dmaven.repo.local=/Volumes/ExternalDrive/maven-repo/.m2/repository -Dtest=NotificationControllerContractTest,UserBarkSettingServiceTest,AccountDeletionServiceTest test
```

Expected: PASS；源代码检索不再出现 `/users/ding`、`DingTalkClient` 或钉钉错误文案。

- [ ] **Step 5: 提交设置 API**

```bash
git add src/main/java/com/multind/bitpongo/notification src/main/java/com/multind/bitpongo/auth/AccountDeletionService.java src/test/java/com/multind/bitpongo/notification src/test/java/com/multind/bitpongo/auth/AccountDeletionServiceTest.java
git commit -m "feat: add per-user Bark settings"
```

---

### Task 5: 实现 Outbox 入队、租约、重试与投递

**Files:**

- Create: `src/main/java/com/multind/bitpongo/notification/NotificationPublisher.java`
- Create: `src/main/java/com/multind/bitpongo/notification/OutboxNotificationPublisher.java`
- Create: `src/main/java/com/multind/bitpongo/notification/NotificationAudienceResolver.java`
- Create: `src/main/java/com/multind/bitpongo/notification/NotificationOutboxLeaseService.java`
- Create: `src/main/java/com/multind/bitpongo/notification/NotificationOutboxDispatcher.java`
- Create: `src/main/java/com/multind/bitpongo/notification/NotificationRetryPolicy.java`
- Test: `src/test/java/com/multind/bitpongo/notification/OutboxNotificationPublisherTest.java`
- Test: `src/test/java/com/multind/bitpongo/notification/NotificationOutboxDispatcherTest.java`
- Test: `src/test/java/com/multind/bitpongo/notification/NotificationOutboxLeaseIntegrationTest.java`

**Interfaces:**

- Produces: `NotificationPublisher.publish(NotificationEvent)`，调用方只使用这个接口。
- Consumes: Task 1 Outbox、Task 3 policy/renderer/client、Task 4 用户设置。

- [ ] **Step 1: 编写失败的 Outbox 测试**

覆盖：

```java
@Test
void duplicateDedupeKeyCreatesOnlyOneOutboxRecord() {
    publisher.publish(event("trade:plan-7:fire-20260823:BTC"));
    publisher.publish(event("trade:plan-7:fire-20260823:BTC"));
    verify(outbox, times(1)).save(any());
}

@Test
void failedDeliveryUsesRequiredBackoffAndNeverThrowsToCaller() {
    doThrow(new BusinessException(502, "failed")).when(client).send(any(), any());
    assertThatCode(() -> dispatcher.dispatchDue()).doesNotThrowAnyException();
    verify(lease).markRetry(id, 1, now.plusSeconds(30), "Bark 通知发送失败");
}
```

Testcontainers 测试启动两个并发事务，断言 `FOR UPDATE SKIP LOCKED` 不会领取同一 ID，30 秒过期租约可恢复。

- [ ] **Step 2: 运行测试并确认 RED**

```bash
./mvnw -Dmaven.repo.local=/Volumes/ExternalDrive/maven-repo/.m2/repository -Dtest=OutboxNotificationPublisherTest,NotificationOutboxDispatcherTest test
```

Expected: FAIL，Publisher/Dispatcher 不存在。Docker 可用时再运行 lease integration；Docker 不可用必须报告为集成测试阻塞，不能伪报通过。

- [ ] **Step 3: 实现独立事务入队和租约投递**

接口固定为：

```java
public interface NotificationPublisher {
    void publish(NotificationEvent event);
}
```

`OutboxNotificationPublisher.publish` 捕获所有入队异常并记录脱敏日志；内部 `enqueueInNewTransaction` 使用 `REQUIRES_NEW`。唯一键冲突视为已入队。AudienceResolver 根据策略产生 USER/ADMIN 记录；MARKET_OUTAGE 解析所有活动计划的 distinct userId。

LeaseService 使用 MySQL 原生查询：

```sql
SELECT id FROM notification_outbox
WHERE ((status = 'PENDING' AND next_attempt_at <= ?)
    OR (status = 'SENDING' AND lease_until < ?))
ORDER BY priority, created_at
LIMIT 50 FOR UPDATE SKIP LOCKED;
```

同一事务将选中行更新为 SENDING、`lease_until=now+30s`。失败退避固定为 30 秒、2 分钟、10 分钟、30 分钟，之后每 30 分钟，10 次后 DEAD。禁用/删除用户目标标记 SKIPPED。管理员未配置时不创建 ADMIN 记录。

- [ ] **Step 4: 运行测试并确认 GREEN**

```bash
./mvnw -Dmaven.repo.local=/Volumes/ExternalDrive/maven-repo/.m2/repository -Dtest=OutboxNotificationPublisherTest,NotificationOutboxDispatcherTest test
```

Expected: PASS。Docker 可用时：

```bash
./mvnw -Dmaven.repo.local=/Volumes/ExternalDrive/maven-repo/.m2/repository -Dtest=NotificationOutboxLeaseIntegrationTest test
```

- [ ] **Step 5: 提交 Outbox**

```bash
git add src/main/java/com/multind/bitpongo/notification src/test/java/com/multind/bitpongo/notification
git commit -m "feat: deliver Bark notifications through outbox"
```

---

### Task 6: 接入定投成功、失败和跳过通知

**Files:**

- Modify: `src/main/java/com/multind/bitpongo/scheduler/ScheduledPurchaseService.java`
- Modify: `src/test/java/com/multind/bitpongo/scheduler/ScheduledPurchaseServiceTest.java`

**Interfaces:**

- Consumes: `NotificationPublisher.publish(NotificationEvent)`。
- Produces: 每次计划触发至多一条成功汇总、一条失败汇总和一条跳过汇总。

- [ ] **Step 1: 编写失败的聚合通知测试**

增加三个测试：

```java
@Test
void aggregatesFilledCoinsIntoOneSuccessNotification() {
    service.execute(7L, fireTime);
    ArgumentCaptor<NotificationEvent> event = ArgumentCaptor.forClass(NotificationEvent.class);
    verify(notifications).publish(event.capture());
    assertThat(event.getValue().type()).isEqualTo(NotificationEventType.TRADE_SUCCEEDED);
    assertThat(event.getValue().attributes().get("symbols")).isEqualTo(List.of("BTCUSDT", "ETHUSDT"));
}

@Test
void reportsDefiniteFailuresWithoutChangingFailedIntentStatus() {
    when(gateway.marketBuy(any(), anyString(), any(), anyString()))
            .thenThrow(new RuntimeException("rejected"));
    service.execute(7L, fireTime);
    verify(persistence).mark(any(OrderIntentEntity.class), eq("FAILED"));
    verify(notifications).publish(argThat(event ->
            event.type() == NotificationEventType.TRADE_FAILED));
}

@Test
void reportsPriceFailuresAsOnePlanExecutionSkippedEvent() {
    when(prices.getFresh(anyString(), anyString(), any())).thenReturn(Optional.empty());
    when(gateway.latestPrice(anyString())).thenThrow(new RuntimeException("price unavailable"));
    service.execute(7L, fireTime);
    verify(gateway, never()).marketBuy(any(), anyString(), any(), anyString());
    verify(notifications).publish(argThat(event ->
            event.type() == NotificationEventType.PLAN_EXECUTION_SKIPPED));
}
```

第二、三个测试使用现有 mock 场景，明确断言 `persistence.mark(..., "FAILED")` 和跳过下单行为保持不变。

- [ ] **Step 2: 运行测试并确认 RED**

```bash
./mvnw -Dmaven.repo.local=/Volumes/ExternalDrive/maven-repo/.m2/repository -Dtest=ScheduledPurchaseServiceTest test
```

Expected: FAIL，没有发布通知。

- [ ] **Step 3: 实现最小聚合**

在一次 `execute(planId, scheduledFireTime)` 内维护成功、失败、跳过列表；循环结束后按非空列表发布事件。去重键分别为：

```text
trade-success:{planId}:{scheduledFireTime}
trade-failed:{planId}:{scheduledFireTime}
plan-skipped:{planId}:{scheduledFireTime}
```

AmbiguousOrderException 仍只进入 `PENDING_RECONCILIATION`，不发送明确失败通知。通知 publish 用安全接口，不能改变原有 catch/status 分支。

- [ ] **Step 4: 运行测试并确认 GREEN**

```bash
./mvnw -Dmaven.repo.local=/Volumes/ExternalDrive/maven-repo/.m2/repository -Dtest=ScheduledPurchaseServiceTest test
```

Expected: PASS。

- [ ] **Step 5: 提交交易通知**

```bash
git add src/main/java/com/multind/bitpongo/scheduler/ScheduledPurchaseService.java src/test/java/com/multind/bitpongo/scheduler/ScheduledPurchaseServiceTest.java
git commit -m "feat: notify Bark about plan executions"
```

---

### Task 7: 接入 Quartz、对账和资产快照告警

**Files:**

- Modify: `src/main/java/com/multind/bitpongo/scheduler/PlanPurchaseJob.java`
- Modify: `src/main/java/com/multind/bitpongo/scheduler/ScheduleReconciler.java`
- Modify: `src/main/java/com/multind/bitpongo/scheduler/OrderReconciliationService.java`
- Modify: `src/main/java/com/multind/bitpongo/plan/AssetSnapshotService.java`
- Modify: `src/test/java/com/multind/bitpongo/scheduler/PlanPurchaseJobTest.java`
- Modify: `src/test/java/com/multind/bitpongo/scheduler/ScheduleReconcilerIntegrationTest.java`
- Modify: `src/test/java/com/multind/bitpongo/scheduler/OrderReconciliationServiceTest.java`
- Modify: `src/test/java/com/multind/bitpongo/plan/AssetSnapshotServiceTest.java`

**Interfaces:**

- Consumes: `NotificationPublisher`。
- Produces: `SCHEDULER_FATAL`、`ORDER_MANUAL_REVIEW`、`ASSET_SNAPSHOT_FAILED`。

- [ ] **Step 1: 编写失败的业务接入测试**

核心断言：

```java
assertThatThrownBy(() -> job.execute(context))
        .isInstanceOf(JobExecutionException.class)
        .hasCause(failure);
verify(notifications).publish(argThat(event -> event.type() == NotificationEventType.SCHEDULER_FATAL));

verify(notifications, times(1)).publish(argThat(event ->
        event.type() == NotificationEventType.ORDER_MANUAL_REVIEW
                && event.intentId().equals(intent.getId())));

verify(notifications).publish(argThat(event ->
        event.type() == NotificationEventType.ASSET_SNAPSHOT_FAILED
                && event.planId().equals(plan.getId())));
```

对账测试必须证明普通 `PENDING_RECONCILIATION` 重试不通知，只有 `markAfterReconciliation(..., "MANUAL_REVIEW", ...)` 返回 true 时首次通知。

- [ ] **Step 2: 运行测试并确认 RED**

```bash
./mvnw -Dmaven.repo.local=/Volumes/ExternalDrive/maven-repo/.m2/repository -Dtest=PlanPurchaseJobTest,ScheduleReconcilerIntegrationTest,OrderReconciliationServiceTest,AssetSnapshotServiceTest test
```

Expected: FAIL，没有 Publisher 交互。

- [ ] **Step 3: 接入事件且保持原错误语义**

`PlanPurchaseJob` 捕获 RuntimeException，先发布紧急事件，再抛出 `JobExecutionException(failure, false)`，让 Quartz 正确记录失败；不得吞掉异常。ScheduleReconciler 的每个 catch 发布调度事件。对账只在状态更新成功且新状态为 MANUAL_REVIEW 时发布。快照每个计划异常发布一次，原循环继续处理其他计划。

- [ ] **Step 4: 运行测试并确认 GREEN**

```bash
./mvnw -Dmaven.repo.local=/Volumes/ExternalDrive/maven-repo/.m2/repository -Dtest=PlanPurchaseJobTest,ScheduleReconcilerIntegrationTest,OrderReconciliationServiceTest,AssetSnapshotServiceTest test
```

Expected: PASS，原状态/继续执行行为断言仍通过。

- [ ] **Step 5: 提交任务告警**

```bash
git add src/main/java/com/multind/bitpongo/scheduler src/main/java/com/multind/bitpongo/plan/AssetSnapshotService.java src/test/java/com/multind/bitpongo/scheduler src/test/java/com/multind/bitpongo/plan/AssetSnapshotServiceTest.java
git commit -m "feat: alert Bark on scheduler and reconciliation failures"
```

---

### Task 8: 接入持续行情中断和恢复通知

**Files:**

- Modify: `src/main/java/com/multind/bitpongo/market/BinanceMarketStreamLifecycle.java`
- Modify: `src/test/java/com/multind/bitpongo/market/BinanceMarketStreamLifecycleTest.java`

**Interfaces:**

- Consumes: `NotificationPublisher`、`zhitoubao.market.health-max-silence`。
- Produces: 每个故障周期一条 `MARKET_OUTAGE`，恢复后一条 `SYSTEM_RECOVERED`。

- [ ] **Step 1: 编写失败的虚拟时钟测试**

使用现有 fake `MarketTaskScheduler`：

```java
@Test
void transientDisconnectReconnectsWithoutNotification() {
    lifecycle.start();
    client.fail(new IOException("closed"));
    scheduler.advance(Duration.ofSeconds(30));
    verifyNoInteractions(notifications);
}

@Test
void sustainedOutageNotifiesOnceAndRecoveryIsPassive() {
    lifecycle.start();
    client.fail(new IOException("closed"));
    scheduler.advance(Duration.ofSeconds(120));
    verify(notifications).publish(argThat(e -> e.type() == NotificationEventType.MARKET_OUTAGE));
    client.emit(ticker());
    verify(notifications).publish(argThat(e -> e.type() == NotificationEventType.SYSTEM_RECOVERED));
}
```

- [ ] **Step 2: 运行测试并确认 RED**

```bash
./mvnw -Dmaven.repo.local=/Volumes/ExternalDrive/maven-repo/.m2/repository -Dtest=BinanceMarketStreamLifecycleTest test
```

Expected: FAIL，没有持续故障状态和通知。

- [ ] **Step 3: 实现故障周期状态机**

将 rotation、reconnect、outage timer 拆成独立 `Cancellable`。首次 failure/close 设置 `outageStartedAt` 和随机故障周期 ID；重连成功但未收到 ticker 时不视为恢复。超过 health-max-silence 发布一次 outage；首条有效 ticker 取消 timer、发布 recovery 并清理周期。正常 23h50m rotation 不创建 outage 周期。

- [ ] **Step 4: 运行测试并确认 GREEN**

```bash
./mvnw -Dmaven.repo.local=/Volumes/ExternalDrive/maven-repo/.m2/repository -Dtest=BinanceMarketStreamLifecycleTest test
```

Expected: PASS，无通知风暴。

- [ ] **Step 5: 提交行情告警**

```bash
git add src/main/java/com/multind/bitpongo/market/BinanceMarketStreamLifecycle.java src/test/java/com/multind/bitpongo/market/BinanceMarketStreamLifecycleTest.java
git commit -m "feat: notify Bark about sustained market outages"
```

---

### Task 9: 更新部署契约、清理钉钉并完成后端验证

**Files:**

- Modify: `.env.example`
- Modify: `compose.yml`
- Modify: `README.md`
- Modify: `docs/python-java-contract-matrix.md`
- Modify: `src/test/java/com/multind/bitpongo/contract/PythonApiContractTest.java`
- Create: `src/test/java/com/multind/bitpongo/notification/BarkLiveSmokeTest.java`
- Create: `src/test/java/com/multind/bitpongo/contract/BarkDocumentationContractTest.java`

**Interfaces:**

- Produces: 可部署 Bark 环境变量、更新后的 API 契约和显式启用的真实联调入口。
- Consumes: Tasks 1–8 的完整后端实现。

- [ ] **Step 1: 编写失败的契约与秘密扫描测试**

将 Python API 契约改为四个 Bark 路由，并新增源码扫描：

```java
assertThat(routes).contains(
        "GET /api/users/notifications/bark",
        "PUT /api/users/notifications/bark",
        "DELETE /api/users/notifications/bark",
        "POST /api/users/notifications/bark/test");
assertThat(routes).doesNotContain("POST /api/users/ding", "GET /api/users/notices");
```

`BarkDocumentationContractTest` 直接读取部署文件，保证变量齐全且旧钉钉配置消失：

```java
@Test
void deploymentFilesDocumentOnlyBarkNotificationSettings() throws IOException {
    String env = Files.readString(Path.of(".env.example"));
    String compose = Files.readString(Path.of("compose.yml"));
    String readme = Files.readString(Path.of("README.md"));
    assertThat(env).contains("BARK_ADMIN_PUSH_URL=", "BARK_ALLOWED_HOSTS=api.day.app",
            "BARK_CREDENTIAL_ENCRYPTION_KEY=");
    assertThat(compose).contains("BARK_ADMIN_PUSH_URL", "BARK_CREDENTIAL_ENCRYPTION_KEY");
    assertThat(env + compose + readme).doesNotContainIgnoringCase("dingtalk").doesNotContain("钉钉");
}
```

`BarkLiveSmokeTest` 使用 `@EnabledIfEnvironmentVariable(named="BITPONGO_BARK_SMOKE_URL", matches=".+")`，从环境读取地址，通过 parser/client 发送固定 `BARK_TEST` 消息；测试源码不得包含任何真实 Key。

- [ ] **Step 2: 运行契约测试并确认 RED**

```bash
./mvnw -Dmaven.repo.local=/Volumes/ExternalDrive/maven-repo/.m2/repository -Dtest=PythonApiContractTest,BarkDocumentationContractTest,BarkLiveSmokeTest test
```

Expected: `BarkDocumentationContractTest` FAIL，因为 README 和部署配置仍含钉钉且缺少 Bark 变量；API 契约通过；live smoke 因环境变量缺失显示 SKIPPED。

- [ ] **Step 3: 更新配置和中文文档**

`.env.example` 只写空值或安全示例：

```dotenv
BARK_USER_NOTIFICATIONS_ENABLED=true
BARK_ADMIN_PUSH_URL=
BARK_ALLOWED_HOSTS=api.day.app
BARK_ALLOW_PRIVATE_HOSTS=false
BARK_CREDENTIAL_ENCRYPTION_KEY=
BARK_NOTIFY_ON_STARTUP=false
APP_PUBLIC_URL=
```

`compose.yml` 映射同名变量。README 说明使用 `openssl rand -base64 32` 生成加密密钥、Bark URL 视为 Secret、持续响铃策略和自建主机白名单。删除所有钉钉说明并更新契约矩阵。

- [ ] **Step 4: 运行后端完整验证**

```bash
./mvnw -Dmaven.repo.local=/Volumes/ExternalDrive/maven-repo/.m2/repository test
./mvnw -Dmaven.repo.local=/Volumes/ExternalDrive/maven-repo/.m2/repository -DskipTests package
git diff --check
rg -n -i "dingtalk|钉钉|/users/ding" src README.md docs/python-java-contract-matrix.md
```

Expected: 单元测试和打包退出 0；最后一条 `rg` 无输出。Testcontainers 若因 Docker 不可用失败，分别报告非 Docker 测试、打包和集成测试状态。

- [ ] **Step 5: 提交后端文档和契约**

```bash
git add .env.example compose.yml README.md docs/python-java-contract-matrix.md src/test/java/com/multind/bitpongo/contract/PythonApiContractTest.java src/test/java/com/multind/bitpongo/notification/BarkLiveSmokeTest.java src/test/java/com/multind/bitpongo/contract/BarkDocumentationContractTest.java
git commit -m "docs: document Bark notification deployment"
```

- [ ] **Step 6: 在前端完成后执行一次真实 Bark 联调**

在不会回显输入的交互 shell 中运行：

```bash
read -r -s "BITPONGO_BARK_SMOKE_URL?Bark URL: "
export BITPONGO_BARK_SMOKE_URL
./mvnw -Dmaven.repo.local=/Volumes/ExternalDrive/maven-repo/.m2/repository -Dtest=BarkLiveSmokeTest test
unset BITPONGO_BARK_SMOKE_URL
```

Expected: `BarkLiveSmokeTest` PASS，设备收到一条标题为“Bitpongo Bark 接入测试”的普通测试通知；命令输出、报告和 Git 中不出现地址。
