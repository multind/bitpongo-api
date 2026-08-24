# 智投宝 Java API

智投宝后端已从 FastAPI 完整迁移到 Java 26 与 Spring Boot 4.1.0。当前版本只启用 Binance，但业务层通过 `ExchangeGateway` 保留多交易所抽象；REST、WebSocket、MySQL 表结构和前端 snake_case 数据契约保持兼容。

## 技术与安全基线

- Java 26、Spring Boot 4.1.0、Maven Wrapper。
- MySQL + Flyway；Quartz 使用同一数据库持久化任务并支持多实例核对。
- Binance Spot Connector 10.1.1 官方 REST/Streams；项目不依赖 CCXT。
- 价格、金额、数量、手续费和收益使用 `BigDecimal`。
- 默认连接 Binance Testnet。只有 `BINANCE_LIVE_TRADING=true` 且生产 REST 主机为 `api.binance.com` 时才允许真实交易。
- 下单使用确定性 `clientOrderId`、数据库唯一约束和结果不明确对账，不对下单请求做盲目重试。
- Binance 5xx、超时和连接中断都按“结果不明确”处理；对账会恢复陈旧的 `READY`/`SUBMITTING`/`RECONCILING` 意图，未成交订单不会计入持仓。
- 五段 Cron 会完整转换日期/星期字段，Quartz 默认按 `Asia/Shanghai` 执行；可通过 `SCHEDULING_ZONE` 调整。

## 本地构建

安装 Java 26 后执行：

```bash
export JAVA_HOME=/path/to/jdk-26
./mvnw clean verify
```

如需复用指定 Maven 仓库：

```bash
./mvnw -Dmaven.repo.local=/Volumes/ExternalDrive/maven-repo/.m2/repository clean verify
```

启动应用前准备 MySQL，复制环境示例但不要提交真实值：

```bash
cp .env.example .env
set -a
source .env
set +a
./mvnw spring-boot:run
```

默认端口为 `8000`。检查：

```bash
curl http://localhost:8000/health
curl http://localhost:8000/actuator/health
```

## Docker Compose

先复制 `.env.example`，并把数据库密码、Root 密码和 JWT Secret 的 `replace-with-*` 占位值全部替换为真实随机值；生产守卫会拒绝示例值。

```bash
cp .env.example .env
docker compose up -d --build
docker compose ps
./scripts/smoke-test.sh http://localhost:8000
```

Compose 默认关闭真实交易和行情 Stream，便于离线检查 REST/数据库/Quartz。需要实时行情时设置 `MARKET_STREAM_ENABLED=true`。

`api` 服务除内部网络外还加入名为 `bitpongo-net` 的共享网络，供 `bitpongofront` 的 Nginx 容器以服务名 `api:8000` 反向代理（前后端分开发布）。对外仅暴露 `8000` 作为 API 调试端口；如不希望暴露，把 `ports` 改为 `127.0.0.1:${SERVER_PORT:-8000}:8000`。启动顺序：先起后端 Compose（创建共享网络），再起前端 Compose。

镜像推送到 Docker Hub（`docker.io/corbettzhang/bitpongoapi:latest`）：

```bash
docker login
docker compose build
docker compose push
```

## 复用现有 MySQL 数据

1. 停止 Python 服务的写入，使用 `mysqldump --single-transaction` 完整备份业务库。
2. 核对字符集、时区和数据库账号权限。
3. Java 服务首次连接现有库时，Flyway 以版本 1 建立基线，不删除或重建 `user`、`order` 等业务表；V2 增加 `client_order_id`、`order_intent` 和 Quartz 表，V3 增加按计划/触发时间唯一的 `plan_fire_execution` 审计表，V4 增加账号生命周期和外部身份注销记录。
4. 先使用 Testnet 密钥启动，检查 `/actuator/health`、用户登录、交易所列表、策略和计划详情。
5. 检查 `QRTZ_` 表中活动计划任务恢复正常，再切换入口流量。

空库会按 V1、V2、V3、V4 顺序创建兼容表。不要手工修改 `flyway_schema_history`。升级前始终备份。

## Binance 配置

必要变量：

```dotenv
BINANCE_LIVE_TRADING=false
BINANCE_TESTNET_REST_BASE_URL=https://testnet.binance.vision
BINANCE_PRODUCTION_REST_BASE_URL=https://api.binance.com
BINANCE_MARKET_STREAM_URL=wss://stream.binance.com:9443
BINANCE_MARKET_STREAM_MAX_MESSAGE_SIZE=1048576
```
`BINANCE_MARKET_STREAM_MAX_MESSAGE_SIZE` 控制行情 WebSocket 单条文本消息上限，默认 1 MiB，以容纳 Binance 全市场 mini ticker 批次。

交易所密钥仍由现有 `/api/exchanges/**` 接口按用户保存。Testnet 与生产密钥不可混用。启用真实交易前必须同时完成：

1. 在隔离环境验证 Testnet 下单、幂等和对账；
2. 确认 `BINANCE_PRODUCTION_REST_BASE_URL` 未被代理或重写；
3. 显式设置 `BINANCE_LIVE_TRADING=true`；
4. 观察启动时的真实交易警告并由两人复核环境变量。

网络代理应通过运行环境或受控出口配置，不在源码中硬编码。

为兼容现有 Python 数据库，当前交易所 API Key/Secret 仍沿用原表明文列；生产部署应限制数据库账号与备份访问，并尽快迁移到 KMS/信封加密和密钥轮换。REST 响应和日志不会输出完整密钥。

## Bark 通知部署

Bark 分为用户通道和管理员通道。用户保存自己的 Bark URL，接收所属交易、计划和资产事件；管理员地址由 `BARK_ADMIN_PUSH_URL` 注入，接收系统、调度、行情和人工复核告警。Bark URL 与其中的 Device Key 都是 Secret，不得写入日志、工单、镜像、测试夹具或 Git。

为用户 Device Key 生成独立的 32 字节 AES 密钥，不要复用 JWT Secret：

```bash
openssl rand -base64 32
```

将输出安全地保存到秘密管理系统，再注入 `BARK_CREDENTIAL_ENCRYPTION_KEY`；`.env.example` 中该值有意留空。部署变量如下：

```dotenv
BARK_USER_NOTIFICATIONS_ENABLED=true
BARK_ADMIN_PUSH_URL=
BARK_ALLOWED_HOSTS=api.day.app
BARK_ALLOW_PRIVATE_HOSTS=false
BARK_CREDENTIAL_ENCRYPTION_KEY=
BARK_NOTIFY_ON_STARTUP=false
BARK_DISPATCH_ENABLED=true
APP_PUBLIC_URL=
```

默认只允许 `api.day.app`，并通过 `BARK_ALLOW_PRIVATE_HOSTS=false` 阻止 loopback、link-local 和私网目标。使用自建服务时，必须在 `BARK_ALLOWED_HOSTS` 中精确列出主机；非 443 端口使用精确的 `host:port`。确需内网自建服务时，还要经过安全复核后显式设置 `BARK_ALLOW_PRIVATE_HOSTS=true`。地址只接受 HTTPS，客户端不跟随重定向。

### 用户设置 API

四个路由均要求当前用户的 Bearer Token，且从认证身份取用户 ID：

- `GET /api/users/notifications/bark`：查询配置状态，只返回掩码地址。
- `PUT /api/users/notifications/bark`：保存或更新地址、启用状态、语言和时区。
- `DELETE /api/users/notifications/bark`：删除配置，并跳过该用户尚未发送的通知。
- `POST /api/users/notifications/bark/test`：同步发送普通测试通知；临时地址不落库。

接口永不返回完整 URL、Device Key 或密文。用户主动测试同步返回结果；业务事件在业务状态提交后写入 MySQL Outbox。Dispatcher 使用租约领取记录，失败按 30 秒、2 分钟、10 分钟、30 分钟退避重试，最多 10 次；稳定 `dedupe_key` 和数据库唯一约束负责去重。通知入队或发送失败不会回滚交易、计划、快照或对账结果。

### 事件响铃摘要

| 事件 | 级别与声音 |
|---|---|
| `SCHEDULER_FATAL`、`ORDER_MANUAL_REVIEW` | `critical`、`call=1`、`volume=10`、`sound=alarm` |
| `TRADE_FAILED`、`MARKET_OUTAGE` | `timeSensitive`、不持续响铃；使用 `alarm` |
| `PLAN_EXECUTION_SKIPPED` | `timeSensitive`、普通提示音、不持续响铃 |
| `TRADE_SUCCEEDED`、`ASSET_SNAPSHOT_FAILED`、`BARK_TEST` | `active`；测试和交易成功使用 `minuet` |
| `SYSTEM_RECOVERED`、`SERVICE_STARTED` | `passive`、无铃声 |

只有需要人工立即处理的 `SCHEDULER_FATAL` 和 `ORDER_MANUAL_REVIEW` 使用 `critical` 与 `call=1`；业务代码不得自行覆盖策略。

### 显式真实联调

真实联调只在前端完成后执行一次。测试类默认跳过，只有隐藏环境变量 `BITPONGO_BARK_SMOKE_URL` 非空时才发送标题为“Bitpongo Bark 接入测试”的普通 `BARK_TEST`。交互输入使用静默模式，命令、Surefire 输出和报告都不得回显地址：

```bash
read -r -s "BITPONGO_BARK_SMOKE_URL?Bark URL: "
export BITPONGO_BARK_SMOKE_URL
./mvnw -Dmaven.repo.local=/Volumes/ExternalDrive/maven-repo/.m2/repository -Dtest=BarkLiveSmokeTest test
unset BITPONGO_BARK_SMOKE_URL
```

后端自动验证阶段不得设置该变量，也不得运行真实发送。

## 兼容接口

完整映射见 [Python—Java 兼容契约矩阵](docs/python-java-contract-matrix.md)。价格 WebSocket 地址仍为 `/api/ws/price`，订阅示例：

```json
{"action":"subscribe","symbols":["BTC","ETH"],"exchange":"binance"}
```

返回每个缓存中存在且未过期的价格：

```json
{"symbol":"BTC","price":62000,"exchange":"binance"}
```

### 注销账号

已登录用户可通过 `DELETE /api/users/account` 永久注销账号，请求体只包含当前密码：

```bash
curl -X DELETE http://localhost:8000/api/users/account \
  -H "Authorization: Bearer ${ACCESS_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"password":"example-password"}'
```

注销不可撤销。成功后，所有交易计划立即停止，交易所访问密钥、Secret 和口令被清除，用户资料被匿名化，已有 Token 在下一次请求时失效。策略、计划、订单和快照仅作为匿名历史保留；原邮箱之后可以注册为一个全新的身份。WordPress 身份注销后不能通过同一外部用户 ID 恢复旧账号。

## 回滚

1. 停止 Java 实例，防止 Quartz 与旧调度器并行下单。
2. 保留 `flyway_schema_history`、`order_intent`、`plan_fire_execution`、`client_order_id` 和 `QRTZ_` 表，不执行删除或降级 DDL。
3. 确认没有 `SUBMITTING` 或 `PENDING_RECONCILIATION` 意图；若存在，先按 `client_order_id` 到 Binance 核对。
4. 使用迁移前相同的数据库连接和备份配置恢复 Python 服务。
5. 验证登录、计划查询和只读行情后再恢复 Python 调度器。

这些新增表和列对旧 Python 代码是向后兼容的；直接删除它们会破坏审计和幂等证据。

## 运维说明

- `/actuator/health` 汇总数据库、Quartz 和行情连接健康状态。
- 日志包含请求关联 ID 和已认证用户 ID，但不记录请求体、密码、Token、Webhook 或交易所 Secret。
- 生产 Profile 要求非空数据库凭据和至少 32 字符的 JWT Secret。
- Compose 不提供生产密码默认值；示例占位值也会被生产启动守卫拒绝。
- 定投任务使用稳定键 `plans.job_plan_<id>`；启动核对只补齐未来任务，不补跑旧触发。
- `plan_fire_execution` 以计划 ID 和 Quartz 计划触发时间去重，`triggered_count` 每个火次只增加一次；任务执行后同步下一次触发时间。
- Binance 返回多币种手续费时，base/USDT 手续费会分别进入净持仓与投入成本核算；为兼容旧 `order` 表，其单值 `fee` 列仅在手续费资产唯一时保存金额，BNB 等第三资产的完整手续费明细尚未单独持久化。
