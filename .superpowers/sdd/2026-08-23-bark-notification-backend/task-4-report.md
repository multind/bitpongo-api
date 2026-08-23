# Task 4 实施报告：用户 Bark 设置 API 与注销清理

## 交付状态

已在基线 `fe4210e592de32016310892c3d608608eed58e68` 上完成 Task 4。实现只使用认证主体用户 ID；提供 Bark 设置 GET/PUT/DELETE 与 test；Device Key 加密且永不响应；测试发送不持久化并固定使用 BARK_TEST 策略；注销删除设置并把 PENDING/SENDING 改为 SKIPPED。

## 恢复审计

- 主工作树初始只有三组未完成 RED 测试：`AccountDeletionServiceTest`、`NotificationControllerContractTest`、新增 `UserBarkSettingServiceTest`。
- `/private/tmp/bitpongo-task4-red.patch` 与这些测试修改一致。
- `/private/tmp/bitpongo-task4-patchrepo` 位于同一基线并保留原代理未迁回的候选生产实现。恢复时仅迁回 Task 4 六个生产文件并显式删除旧实现，未覆盖其他路径。
- 临时目录旧 Surefire 报告仅作为恢复线索，未替代本工作树的新 RED/GREEN 与全量验证。

## 实现

- 新增 `GET/PUT/DELETE /api/users/notifications/bark` 与 `POST /api/users/notifications/bark/test`。
- Controller 只读取 `@AuthenticationPrincipal AuthenticatedUser`；`user_id` 和其他未知请求字段返回 400。
- 新建要求 Push URL，已有配置可单独修改 enabled/locale/timezone；locale 白名单为 `zh-CN`、`zh-TW`、`en-US`，timezone 经 `ZoneId.of` 校验。
- AES-GCM 加密保存 Device Key；GET/PUT 只返回掩码 URL，不返回 Key 或密文。
- 临时测试 URL 不保存，其 query 参数不覆盖固定 BARK_TEST message policy。
- 禁用、删除或注销时只把该用户 PENDING/SENDING outbox 改为 SKIPPED，清 lease 并刷新 `updated_at`；SENT 历史保持不变。
- 删除 `DingTalkClient`、`HttpDingTalkClient`、旧测试、`DictEntity`、`DictRepository`，移除旧 `/ding`、`/notices` 生产路由。

## TDD 证据

所有 Maven 命令均指定 `-Dmaven.repo.local=/Volumes/ExternalDrive/maven-repo/.m2/repository`。

### 初始 RED

```bash
./mvnw -Dmaven.repo.local=/Volumes/ExternalDrive/maven-repo/.m2/repository \
  -Dtest=NotificationControllerContractTest,UserBarkSettingServiceTest,AccountDeletionServiceTest test
```

迁回生产实现前编译失败，共 20 个错误；缺失项包括 `UserBarkSettingService`、Bark application service 方法、`deleteByUserId` 与 outbox skip 方法。

### 注销持久化 RED/GREEN

首次全量为 147 tests、1 failure：真实 MySQL 用例期望注销后用户状态为 `deleted`，实际为 `active`。根因是 outbox bulk update 的 `clearAutomatically=true` 清空 persistence context，使已加载用户被分离。移除 clear、保留 `flushAutomatically=true` 后，同一回归 1/1 通过。

### updated_at RED/GREEN

真实 MySQL/JPA 先证明：

- 新建后立即返回的 `updatedAt` 为 `null`。
- skip 后 `updatedAt` 仍为预置的 `2000-01-01T00:00`。

修复为：实体 `updatedAt` 标记 Hibernate INSERT/UPDATE 数据库生成；设置使用 `saveAndFlush`；skip JPQL 设置 `updatedAt=CURRENT_TIMESTAMP`。

```bash
./mvnw -Dmaven.repo.local=/Volumes/ExternalDrive/maven-repo/.m2/repository \
  -Dtest=BarkPersistenceContractTest test
```

最终结果 5 tests、0 failures/errors。该测试用 Testcontainers `mysql:9.7.0`、真实 Flyway v1-v6、JPA、认证 JWT 与 MockMvc，经实际 Controller/Application Service/Setting Service 发起两次 PUT，验证创建响应时间非空、更新时间前进且均等于数据库值；同时验证 Key 密文、注销事务、skip/lease/审计时间及 SENT 保留。

### 行为测试 GREEN

```bash
./mvnw -Dmaven.repo.local=/Volumes/ExternalDrive/maven-repo/.m2/repository \
  -Dtest=NotificationControllerContractTest,UserBarkSettingServiceTest,AccountDeletionServiceTest test
```

15 tests、0 failures/errors：MockMvc Controller 5/5、记录型 fake/服务行为 7/7、注销服务 3/3。

### 全量 GREEN

```bash
./mvnw -Dmaven.repo.local=/Volumes/ExternalDrive/maven-repo/.m2/repository test
```

```text
Tests run: 148, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 02:43 min
```

## 最终检查与关注点

- `git diff --check`、`git diff --cached --check` 通过。
- `src/main` 检索 DingTalk/Dict 类、旧 routes 与钉钉文案无匹配。
- 只读复审提出的响应 `updatedAt` 和 skip 审计时间问题均已修复；复审确认无剩余阻塞。
- 非阻塞：Flyway 提示 MySQL 9.7 高于已验证的 9.4，但六个迁移均成功；全量中既有调度器用例会记录预期连接重试日志，测试仍 GREEN。
