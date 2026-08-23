# Bitpongo Bark 分级通知系统设计

## 背景

当前 `bitpongo-api` 的通知模块仍以钉钉命名，但实际只提供 `POST /api/users/ding` 测试发送和 `GET /api/users/notices` 字典查询。定投执行、订单对账、资产快照、Quartz 任务恢复和 Binance 行情重连都只写日志，没有真正发送通知。`bitpongo-front` 的通知页同样只能填写钉钉 Webhook 与签名密钥并发送测试，不能保存用户配置。

本设计以开源 Bark 替换钉钉，并将通知真正接入关键业务事件。范围包括：

- `/Volumes/ExternalDrive/Code/github/bitpongo-api`
- `/Volumes/ExternalDrive/Code/github/bitpongo-front`

不修改旧 `zhitoubao` 仓库。用户提供的 Bark 测试地址视为敏感信息，只用于一次真实联调，不写入源码、文档、日志、测试夹具或 Git 历史。

## 目标

- 完整删除钉钉客户端、接口、前端页面和文案，统一改为 Bark。
- 每个用户配置自己的 Bark，接收自己的交易和计划事件。
- 管理员使用环境变量配置独立 Bark，接收系统、调度和基础设施事件。
- 不同事件统一映射到 Bark 的通知级别、铃声、音量、分组和持续响铃策略。
- 使用 MySQL Outbox 持久化业务通知，支持去重、租约、失败重试和审计。
- Bark 发送失败不得回滚交易、改变订单结果或阻塞 Quartz 任务。
- Bark Device Key 加密保存，任何日志和 API 响应都不得泄露 Key。

## 非目标

- 不引入 RabbitMQ、Kafka 或第三方通知 SaaS。
- 不实现邮件、Telegram、短信或 Android 推送。
- 不使用 Bark MCP；后端直接调用 Bark HTTP API V2。
- 不承诺在数据库完全不可用或 JVM 已崩溃时仍能发送告警。
- 不调整交易决策、订单幂等、对账状态机或行情重连算法。

## 方案选择

### 通知接收人

采用“双通道”方案：

1. 用户 Bark 只接收属于该用户的交易、计划和资产事件。
2. 管理员 Bark 接收 Quartz、行情、任务恢复和人工对账等系统告警。
3. 用户未配置 Bark 时，严重事件发送到管理员，但正文只包含用户 ID、计划 ID、订单 ID 等脱敏标识。

不采用单一全局地址，因为多用户交易内容会混发；也不采用纯用户地址，因为没有用户上下文的系统故障将无人接收。

### 投递可靠性

采用 MySQL Outbox，而不是直接异步发送或增加消息队列：

- 直接异步发送实现简单，但进程重启或 Bark 短暂不可用时会丢通知。
- MySQL Outbox 可复用现有数据库，提供持久化、去重、租约和重试。
- 消息队列可靠但引入新的部署组件，当前没有必要。

测试通知是用户主动操作，需要立即返回真实发送结果，因此绕过 Outbox 同步调用 Bark；所有业务通知均进入 Outbox。

## 模块边界

### `BarkClient`

只负责 Bark API V2：接收规范化目标和 `BarkMessage`，向服务器的 `/push` 发送 JSON，并验证 HTTP 状态及 Bark 响应 `code`。客户端设置 5 秒连接超时和 10 秒请求超时，不记录请求 URL、Device Key 或请求正文。

### `BarkPushUrlParser`

接收用户从 Bark App 复制的完整测试地址，提取：

- `serverUrl`：协议、主机和允许的端口。
- `deviceKey`：路径第一段。

路径中 Device Key 后的示例标题、正文以及查询参数全部忽略，避免用户粘贴的 `call=1`、`sound` 等参数覆盖系统策略。运行时统一使用 `POST {serverUrl}/push`，Device Key 放入 JSON 的 `device_key` 字段。

### `BarkEventPolicy`

维护事件类型到 Bark 参数的唯一映射。业务代码只发布事件，不自行决定铃声、级别、分组或是否持续响铃。

### `NotificationOutboxService`

以业务事件创建 Outbox 记录，生成稳定去重键，并根据接收人类型记录 `USER` 或 `ADMIN`。Outbox 不保存 Bark 地址或 Device Key，发送时才解析当前有效配置。

### `NotificationOutboxDispatcher`

每 5 秒领取到期记录，使用带租约的状态变更避免多实例重复发送。投递成功标记 `SENT`；失败保存脱敏错误摘要并按策略重试。发送异常不会向业务调用方抛出。

## 数据模型

### `user_bark_setting`

新增 Flyway 表：

- `user_id`：主键及用户外键。
- `server_url`：规范化后的 Bark 服务地址，不含 Device Key。
- `device_key_ciphertext`：AES-256-GCM 密文。
- `enabled`：是否启用。
- `locale`：`zh-CN`、`zh-TW` 或 `en-US`，默认 `zh-CN`。
- `timezone`：IANA 时区，默认 `Asia/Shanghai`。
- `created_at`、`updated_at`。

账号注销事务中删除该用户 Bark 配置；被注销用户不再接收待发送通知。

### `notification_outbox`

新增 Flyway 表：

- `id`：主键。
- `event_type`：稳定事件枚举名。
- `recipient_type`：`USER` 或 `ADMIN`。
- `user_id`：用户通知时必填，管理员通知为空。
- `title_key`、`body_payload`：本地化键和不含密钥的 JSON 数据。
- `dedupe_key`：唯一去重键。
- `priority`：`CRITICAL`、`TIME_SENSITIVE`、`ACTIVE` 或 `PASSIVE`。
- `status`：`PENDING`、`SENDING`、`SENT`、`DEAD` 或 `SKIPPED`。
- `attempts`、`next_attempt_at`、`lease_until`。
- `last_error`：最多 512 字符的脱敏摘要。
- `created_at`、`sent_at`、`updated_at`。

唯一索引约束 `dedupe_key`。调度器以原子更新领取记录；过期 `SENDING` 租约可由其他实例恢复。

## 凭据与网络安全

- `BARK_CREDENTIAL_ENCRYPTION_KEY` 必须是独立的 32 字节 Base64 密钥，不复用 JWT Secret。
- Device Key 使用随机 12 字节 nonce 的 AES-256-GCM，密文格式为版本化封装 `v1:<base64>`。
- API 只返回掩码地址，例如保留服务器和 Key 最后 4 位；不返回密文或完整 Key。
- 默认允许 `api.day.app`。`BARK_ALLOWED_HOSTS` 可增加精确的自建 Bark 主机名。
- 仅允许 HTTPS；拒绝用户信息、片段、空 Key、未列入白名单的主机和非默认端口。自建非 443 端口必须以 `host:port` 精确列入白名单。
- 不跟随跨主机重定向。默认拒绝 loopback、link-local 和私网地址；只有主机已精确列入白名单且管理员显式开启私网目标时才允许自建内网 Bark。
- 结构化日志继续禁止记录请求体、Token、Bark 地址和 Device Key。

## 后端接口

旧 `POST /api/users/ding`、`GET /api/users/notices` 和钉钉类型全部删除，替换为：

### `GET /api/users/notifications/bark`

返回当前用户设置：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "configured": true,
    "enabled": true,
    "masked_push_url": "https://api.day.app/****abcd",
    "locale": "zh-CN",
    "timezone": "Asia/Shanghai",
    "updated_at": "2026-08-23T12:00:00"
  }
}
```

未配置时返回 `configured=false`，不使用 404。

### `PUT /api/users/notifications/bark`

请求体：

```json
{
  "push_url": "Bark App 复制出的完整地址",
  "enabled": true,
  "locale": "zh-CN",
  "timezone": "Asia/Shanghai"
}
```

解析、校验并加密保存。`push_url` 仅在创建或替换目标时必填；只修改启用状态、语言或时区时可以省略。

### `DELETE /api/users/notifications/bark`

删除当前用户的 Bark 配置，并将尚未发送的该用户通知标记为 `SKIPPED`。

### `POST /api/users/notifications/bark/test`

请求可包含临时 `push_url`；未传时使用已保存配置。临时地址只用于本次发送，不落库。成功返回 `sent=true`；目标无效返回 400；Bark 拒绝或网络失败返回 502。

## 管理员配置

管理员通知使用环境变量，不进入数据库：

- `BARK_ADMIN_PUSH_URL`：管理员 Bark 完整地址，可为空以关闭管理员推送。
- `BARK_ALLOWED_HOSTS`：逗号分隔的精确主机或 `host:port` 白名单，默认 `api.day.app`。
- `BARK_ALLOW_PRIVATE_HOSTS`：是否允许已列入白名单的私网自建服务，默认 `false`。
- `BARK_USER_NOTIFICATIONS_ENABLED`：是否启用用户 Bark 配置，默认 `true`。
- `BARK_CREDENTIAL_ENCRYPTION_KEY`：用户 Device Key 加密密钥。
- `APP_PUBLIC_URL`：用户通知点击后的前端地址。
- `BARK_NOTIFY_ON_STARTUP`：是否发送静默启动通知，默认 `false`。

应用启动时只报告管理员 Bark “已启用/未启用”，不得输出地址。管理员地址无效时启动失败，避免部署后静默失去系统告警。用户通知功能启用但缺少加密密钥时同样启动失败。

## 通知策略矩阵

| 事件 | 接收人 | Bark 参数 | 去重与说明 |
| --- | --- | --- | --- |
| `SCHEDULER_FATAL` | 管理员；涉及计划时同时用户 | `level=critical`、`call=1`、`volume=10`、`sound=alarm`、分组“Bitpongo·紧急” | 定时任务未捕获异常、任务恢复或注册失败；同一任务 10 分钟去重 |
| `ORDER_MANUAL_REVIEW` | 用户和管理员 | `critical`、`call=1`、`volume=10`、`sound=alarm`、分组“Bitpongo·紧急” | 订单进入 `MANUAL_REVIEW` 或凭据失效导致无法对账；同一订单只通知一次 |
| `TRADE_FAILED` | 用户；未配置时管理员脱敏兜底 | `timeSensitive`、`sound=alarm`、分组“Bitpongo·交易异常” | 下单明确失败或被拒绝；同一计划触发和币种只通知一次 |
| `MARKET_OUTAGE` | 用户和管理员 | `timeSensitive`、`sound=alarm`、分组“Bitpongo·行情” | 单次断开不通知；连续不可用超过现有 120 秒阈值后发送，每次故障周期一次 |
| `PLAN_EXECUTION_SKIPPED` | 用户 | `timeSensitive`、普通提示音、分组“Bitpongo·计划” | 行情不可用等原因导致本次买入跳过；按计划触发聚合 |
| `TRADE_SUCCEEDED` | 用户 | `active`、`sound=minuet`、分组“Bitpongo·交易” | 每次计划触发聚合为一条，不按币种连续推送 |
| `ASSET_SNAPSHOT_FAILED` | 用户 | `active`、普通提示音、分组“Bitpongo·资产” | 同一计划 30 分钟去重 |
| `SYSTEM_RECOVERED` | 原告警接收人 | `passive`、无铃声、对应原分组 | 行情或任务恢复，每次故障周期一次 |
| `SERVICE_STARTED` | 管理员 | `passive`、无铃声、分组“Bitpongo·系统” | 可通过配置关闭 |
| `BARK_TEST` | 发起用户 | `active`、`sound=minuet`、分组“Bitpongo·测试” | 不进入 Outbox，不持续响铃 |

只有必须立即人工介入的调度或资金状态使用 `call=1`。任何业务代码不得绕过 `BarkEventPolicy` 自行添加持续响铃。

## 业务事件接入

- `PlanPurchaseJob`：捕获任务边界未处理异常，写入 `SCHEDULER_FATAL` 后继续让 Quartz 记录失败状态。
- `ScheduleReconciler`：计划恢复和资产快照任务注册失败发布 `SCHEDULER_FATAL`。
- `ScheduledPurchaseService`：按一次计划触发聚合成功、明确失败及因行情缺失跳过的币种，分别发布交易或计划事件。
- `OrderReconciliationService`：只有状态首次转入 `MANUAL_REVIEW` 时发布 `ORDER_MANUAL_REVIEW`，普通重试不推送。
- `AssetSnapshotService`：单计划失败发布 `ASSET_SNAPSHOT_FAILED`。
- `BinanceMarketStreamLifecycle`：短暂断开继续静默自动重连；持续超过健康阈值后向管理员及所有拥有活动计划的用户发布 `MARKET_OUTAGE`，恢复后向同一故障周期的接收人发布 `SYSTEM_RECOVERED`。

Outbox 事件必须在对应业务状态成功提交后，以独立事务创建。入队失败只写脱敏错误日志，不能回滚或覆盖已经持久化的交易、计划、快照及对账结果；发送动作永远在业务事务外进行。该选择保留一次极小的“业务提交后、Outbox 入队前进程崩溃”窗口，优先保证通知系统不能破坏交易状态。

## 重试、去重和失败处理

- Dispatcher 每 5 秒扫描到期记录，单批最多 50 条，租约 30 秒。
- 失败退避为 30 秒、2 分钟、10 分钟、30 分钟，之后每 30 分钟重试，最多 10 次。
- 超过 10 次标记 `DEAD` 并写脱敏错误日志；Bark 本身不可用时不递归创建“通知失败”通知。
- `dedupe_key` 使用事件类型和稳定业务标识生成：计划触发时间、订单 Intent ID 或故障周期 ID。
- 用户删除或禁用 Bark 后，未发送的用户记录标记 `SKIPPED`；重新启用不补发历史记录。
- 管理员未配置时，管理员事件不创建无法投递的记录，只输出一次受限频率的警告日志。

## 消息内容与本地化

- 用户消息按设置中的 `locale` 渲染简体中文、繁体中文或英文。
- 时间以设置中的 IANA `timezone` 显示，Outbox 内部时间仍使用 UTC。
- 管理员消息使用简体中文和服务器调度时区。
- 消息只允许包含事件时间、用户 ID、计划 ID、订单 Intent ID、币种、结果状态和最多 300 字符的脱敏错误摘要。
- 禁止包含 AccessKey、SecretKey、交易所密码、JWT、Bark Device Key、完整邮箱或 HTTP 请求体。
- 用户事件在配置 `APP_PUBLIC_URL` 时附带对应计划或 API 详情跳转；管理员告警不附带敏感查询参数。

## 前端设计

通知页删除钉钉、Telegram 和 Email 的占位弹窗，只保留一个 Bark 设置卡片：

- Bark 推送地址输入框，密码形式显示，可切换显隐。
- 已配置/未配置及启用/停用状态。
- “保存并启用”“发送测试”“停用并删除”三个操作。
- 简短说明：在 Bark App 中复制测试地址并粘贴；通知声音由系统按事件严重程度决定。

页面使用现有全局暖白背景、统一字体和普通表单样式。保存时同时提交当前前端语言及 App 提供的时区。前端不得把完整地址写入日志、LocalStorage 或错误上报。

## 测试与验证

### 后端自动化测试

- URL 解析：官方地址、完整示例路径、URL 编码、自建白名单、非法协议、用户信息、端口、私网和重定向。
- AES-GCM：随机 nonce、往返解密、错误密钥、篡改密文、响应掩码。
- Bark 客户端：JSON 字段、事件参数、超时、非 2xx、非零 Bark `code`，并断言日志不含 Key。
- 策略映射：每个事件的 `level`、`call`、`volume`、`sound`、`group` 和跳转 URL。
- Outbox：唯一去重、领取租约、多实例竞争、退避时间、重试上限、禁用用户跳过和故障恢复。
- 业务接入：任务异常、交易聚合、首次人工处理、快照失败、行情持续中断和恢复。
- API：查询、保存、更新、删除、测试、认证隔离和账号注销清理。

### 前端自动化测试

- Bark 表单和三语文案。
- 已配置状态不回显 Key。
- 保存、测试、启停及错误重试。
- 不再引用 `/users/ding` 或钉钉文案。

### 真实联调

实现和自动化验证完成后，使用用户在对话中提供的地址发送一次标题明确为“Bitpongo Bark 接入测试”的真实通知。只验证成功响应和设备收到通知，不把地址复制到命令日志、报告或 Git。持续响铃等生产策略通过客户端请求测试验证；除用户已经明确授权的这一次测试外，不主动发送其他真实通知。

## 文档与部署

- 更新 `README.md`、`.env.example`、`compose.yml` 和中文部署说明，删除钉钉内容并新增 Bark 变量。
- 更新 Python/Java 契约矩阵，明确旧钉钉接口已被 Bark 接口替代。
- 数据库迁移只新增 Bark 与 Outbox 表，不修改现有交易历史。
- 部署前生成独立 32 字节加密密钥，并以秘密管理或环境变量注入，不进入镜像。

## 参考资料

- [Bark API V2](https://github.com/Finb/bark-server/blob/master/docs/API_V2.md)
- [Bark 官方使用文档](https://github.com/Finb/Bark/blob/master/docs/en-us/tutorial.md)

## 验收标准

- 前后端不再存在可执行的钉钉代码、接口或用户文案。
- 用户可以保存、测试、查看状态及删除自己的 Bark 配置，且 API 永不回显 Device Key。
- 管理员和用户事件按矩阵使用正确的 Bark 参数，只有紧急事件持续响铃。
- 交易成功按计划触发聚合，短暂行情断开不产生通知风暴。
- Outbox 能在 Bark 短暂失败后重试，多实例不会重复领取，同一业务事件不会重复推送。
- Bark 不可用不改变交易、计划、快照和对账结果。
- 账号注销清除 Bark 配置，日志和 Git 中不存在用户提供的测试地址。
- 后端和前端分别提交到各自 `main`，工作区干净；推送远端必须单独获得或沿用用户明确授权。
