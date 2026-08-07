# 智投宝 API Java 迁移设计

## 1. 目标

将 `/Volumes/ExternalDrive/Code/Zhitoubao/zhitoubaoapi` 下的 Python FastAPI 服务转换为当前仓库中的 Java 服务。新服务一次性交付完整功能，并兼容现有前端契约和 MySQL 数据。

系统采用模块化单体架构，保留多交易所抽象，首版仅实现 Binance 现货交易，并使用 Binance 官方 Java Connector 替代 CCXT。除非显式开启生产交易开关，否则禁止真实下单。

## 2. 已确认范围

Java 服务包含源项目当前工作区中的全部行为：

- 本地登录、WordPress 登录、注册、个人资料、通知配置和钉钉测试通知；
- 交易所配置增删改查、密钥校验、余额查询和最小下单金额计算；
- 策略创建，以及关联计划和币种分配的创建；
- 活动计划列表、计划详情、状态变更、当前估值和收益计算；
- 持久化定投任务和每小时资产快照；
- Binance 行情接入和面向前端的价格 WebSocket；
- 数据库迁移、健康检查、日志、Docker 打包和部署文档。

Python 源项目当前未提交的“创建策略后返回 `strategy`、`plan` 和 `coins`”行为属于兼容契约。源项目中硬编码的本地代理不直接迁移，改为外部配置。

本次交付不实现 OKX。交易所边界必须保证以后增加 OKX 实现时，无需修改策略、计划和调度模块。

## 3. 平台与依赖策略

以下版本是截至 2026-08-07 经核实的最新稳定正式版：

- Java 26，当前最新 Java GA 版本；
- Spring Boot 4.1.0；
- Maven，并将 Maven Wrapper 提交到仓库；
- `io.github.binance:binance-spot:10.1.1`，Maven Central 当前最新稳定版；
- Spring Framework、Spring Security、Spring Data JPA、Hibernate、Jackson、Quartz、Micrometer、MySQL Driver、Flyway、JUnit 以及与 Testcontainers 兼容的传递依赖，由 Spring Boot 依赖管理统一控制；
- Spring Boot BOM 未管理的直接依赖使用最新稳定版本。

不使用 Milestone、RC、Snapshot 或 Early Access 依赖。构建必须记录全部解析版本，并通过 Maven Enforcer 的依赖收敛检查。由于后续可能发布新版本，实施计划要求在搭建项目骨架前重新核对所有直接依赖的最新稳定版。

官方参考资料：

- <https://www.oracle.com/java/technologies/downloads/>
- <https://spring.io/projects/spring-boot/>
- <https://github.com/binance/binance-connector-java>
- <https://github.com/binance/binance-spot-api-docs/blob/master/web-socket-streams.md>

## 4. 架构

仓库中只包含一个 Spring Boot 应用、一个可执行 JAR 和一个 Docker 镜像。代码按业务能力组织，不采用全局式 Controller、Service、Repository 分层目录。

### 4.1 模块

- `auth`：本地 JWT、WordPress 登录和校验、本地用户同步、请求认证及密码兼容；
- `exchange`：交易所密钥存储、增删改查、脱敏、密钥校验、账户余额、市场规则和订单；
- `market`：Binance 公共行情流生命周期、实时价格缓存、交易对标准化和前端 WebSocket；
- `strategy`：策略创建、币种分配和计划初始化，三者位于同一事务；
- `plan`：活动计划、计划详情、状态变更、当前估值和收益计算；
- `scheduler`：Quartz 任务、持久化任务恢复、定投任务、每小时资产快照和订单对账；
- `notification`：钉钉通知发送和通知配置查询；
- `common`：API 响应包装、异常、JSON 约定、时钟、标识符和共享配置；
- `infrastructure`：JPA、Flyway、Binance Connector、WordPress HTTP 客户端、数据库和外部客户端配置。

每个模块对外提供职责单一的应用服务。Controller 不直接访问 Repository 或外部 SDK。领域层和应用层依赖接口，基础设施层提供接口实现。

### 4.2 交易所端口

初始边界等价于：

```java
public interface ExchangeGateway {
    AccountBalance verifyCredentials(ExchangeCredentials credentials);
    MarketRules getMarketRules(String symbol);
    OrderResult marketBuy(
            ExchangeCredentials credentials,
            String symbol,
            BigDecimal quantity,
            String clientOrderId);
    Optional<OrderResult> findOrder(
            ExchangeCredentials credentials,
            String symbol,
            String clientOrderId);
}
```

`BinanceExchangeGateway` 使用 Binance 官方现货 REST 客户端实现该接口。`ExchangeGatewayRegistry` 根据交易所代码选择实现；除 `binance` 之外的代码均返回明确的“不支持该交易所”错误。

## 5. 对外契约兼容

Java 服务保留现有 HTTP 方法和路径：

- `GET /` 和 `GET /health`；
- `/api/users/login`、`/register`、`/profile`、`/ding`、`/notices` 和 `/v1/login`；
- `/api/exchanges/list`、`/{exchange_id}`、`/create`、`/check` 和 `/minimumAmount`；
- `/api/strategies/create` 和 `/list/active`；
- `/api/plans/list/active`、`/{plan_id}/{plan_status}` 和 `/{plan_id}`；
- WebSocket `/api/ws/price`。

请求和响应 JSON 与现有前端保持兼容。Java 内部使用清晰命名，Jackson 将其映射为现有 snake_case 字段。成功响应和业务错误响应继续使用：

```json
{
  "code": 200,
  "message": "ok",
  "data": null
}
```

WebSocket 继续接受现有订阅消息：

```json
{
  "action": "subscribe",
  "symbols": ["BTC", "ETH"],
  "exchange": "binance"
}
```

对于每个存在可用价格的交易对，服务发送一条包含 `symbol`、`price` 和 `exchange` 的兼容消息。非法 JSON 和无可用价格时继续使用现有错误格式。

兼容不等于复制缺陷。认证控制流错误、数据库会话过早关闭、空指针、敏感信息日志和不一致的异常包装会被修复，同时保持路径、JSON 字段、状态语义和用户可见文案不变。

## 6. 数据库兼容与迁移

服务直接读取现有 MySQL 表结构和数据。JPA 映射保留 `user`、`exchange`、`strategy`、`plan`、`coin`、`order`、`snapshot` 和 `dict` 表及其字段名和关联关系。`user`、`order` 等保留关键字将显式转义。

金额、价格、数量、手续费和收益率在 Java 中统一使用 `BigDecimal`。现有浮点字段可继续读取；只有集成测试证明存在必要性时，Flyway 才通过已审核的非破坏性迁移增加高精度字段或调整兼容的数值类型。不得删除或重建业务表。

Flyway 对现有已使用数据库建立基线，并能从迁移脚本创建全新环境。增量迁移仅增加：

- Quartz JDBC JobStore 表；
- 用户数据隔离查询和订单幂等所需的索引或约束；
- 集成测试证明必要的兼容字段调整。

WordPress 返回的用户 ID 继续作为本地用户 ID。原 PBKDF2 格式保持可读：`32 位十六进制盐值 + 64 位十六进制 SHA-256 哈希值`，迭代 100,000 次，保证现有本地密码仍可使用。

事务由应用服务控制。策略、计划和币种配置必须在同一事务内提交。长连接 WebSocket 生命周期内不得持有数据库事务。

## 7. 认证与安全

公共路径与 Python 服务的放行列表一致。所有交易所、策略和计划接口都要求 Bearer Token，并在每条查询中校验 `user_id` 数据归属。

应用支持：

- 使用现有 `id` Claim 和可配置 HS256 密钥创建及校验本地 JWT；
- 通过 `/wp-json/jwt-auth/v1/token` 完成 WordPress JWT 登录；
- 通过 `/wp-json/wp/v2/users/me` 校验 WordPress Token 和用户；
- 将 WordPress 用户同步到本地数据库。

数据库凭据、JWT 密钥、WordPress 地址、Binance 地址、代理和交易开关全部来自环境变量。提交到仓库的默认配置不得包含生产密码或密钥。日志不得包含明文密码、Bearer Token、Secret Key 或完整 Access Key。

交易所列表和详情响应保持现有脱敏行为，Secret Key 绝不以明文返回。新凭据继续兼容当前数据库结构；本次不静默引入静态加密，因为这会破坏现有读取方和字段长度兼容性。

## 8. Binance 行情与交易

### 8.1 行情

应用级唯一的 Binance `SpotWebSocketStreams` 客户端负责提供市场价格。浏览器 WebSocket 会话只读取共享缓存，不为每个客户端单独建立 Binance 连接。

客户端必须：

- 订阅足以覆盖所有所需 USDT 交易对的 Binance 现货行情流；
- 将 Binance 的 `BTCUSDT` 标准化为内部 `BTC/USDT` 格式；
- 在线程安全缓存中记录价格和更新时间；
- 正确响应 ping/pong；
- 使用有上限并带随机抖动的指数退避策略重连；
- 在 Binance 24 小时连接期限前主动轮换连接；
- 通过健康指标暴露连接状态和最后消息时间。

### 8.2 市场规则

通过 Binance `exchangeInfo` 获取交易对状态、`LOT_SIZE`、`MARKET_LOT_SIZE`、`PRICE_FILTER` 和 `MIN_NOTIONAL`/`NOTIONAL`。规则按有限时长缓存；遇到与规则相关的 Binance 错误时立即刷新。

根据计划每期金额和币种比例计算分配金额。保留现有语义：当分配金额低于最低成交额时，将目标金额提升到最低成交额。基础币数量按 Binance `stepSize` 向下取整，检查最小和最大数量，并在提交前再次校验成交额限制。

### 8.3 安全交易与幂等

`BINANCE_LIVE_TRADING` 默认为 `false`。默认情况下，签名账户和订单操作使用 Binance Spot Testnet；只有显式开启该开关后才使用生产 REST 地址。两种模式都可以使用生产公共行情流。

每次计划执行根据计划、交易对和计划触发时间生成确定性的 `clientOrderId`。查询操作可在临时网络错误或限流时重试；下单请求绝不盲目重试。结果不明确时，先按 `clientOrderId` 查询订单，再决定是否提交或持久化。

如果 Binance 已接受订单但数据库事务失败，对账任务会根据 `clientOrderId` 查询订单并保证只持久化一次。数据库唯一约束防止本地订单重复。

## 9. 调度与计算

Quartz 使用 JDBC JobStore。应用启动时对活动计划与 Quartz 任务进行核对，使任务能在重启后恢复，并补充缺失任务且不重复创建。

- 创建策略时创建活动计划，并按保存的 Cron 表达式调度 `job_plan_<planId>`；
- 计划状态离开 `active` 时取消对应定投任务；
- 计划重新进入 `active` 时校验并恢复任务；
- 资产快照任务每小时运行，为每个活动计划记录用户的 USDT 可用余额。

定投任务依次执行：加载计划及其用户所属交易所、策略和币种；应用 `last_average` 或 `total_average` 条件；校验价格时效和市场规则；提交允许执行的订单；持久化订单事实；更新币种数量和持仓均价；重新计算计划累计投入、收益、收益率、触发次数和下次执行时间。

所有计算使用不可变 `BigDecimal`，并显式指定精度和舍入方式。价格缺失或过期时，不以零价格继续计算；阻止该交易对下单并记录可定位的错误。

## 10. 错误处理与可观测性

全局异常处理器将参数校验、认证、授权、资源不存在、数据库、WordPress、Binance 认证、Binance 限流和未知异常转换为兼容的响应包装和中文提示。

Actuator 提供运维健康检查。自定义健康指标报告数据库连通性、Quartz 调度状态和 Binance 行情新鲜度。结构化日志包含请求关联 ID、已认证用户 ID、计划 ID 和安全的 Binance 错误码，但不包含任何凭据。

现有 `/health` 为兼容前端继续返回 `{"status":"ok"}`；完整运维健康状态通过 Actuator 提供。

## 11. 打包与部署

仓库交付内容包括：

- 完整 Java 源码和测试；
- Maven Wrapper 和可复现的 `pom.xml`；
- 同时支持新数据库和现有数据库的 Flyway 迁移；
- 不含密钥的 `.env.example`；
- 生产 Dockerfile；
- 用于应用和 MySQL 的 Docker Compose；
- README，包含本地开发、迁移、Testnet 验证、生产交易启用、代理、回滚和运维说明。

Spring Boot 启动时执行 Flyway 校验和已批准迁移。迁移失败时应用必须停止启动。Docker 镜像使用非 root 用户运行并提供健康检查。

## 12. 验证与验收

### 12.1 自动化测试

- 单元测试覆盖 PBKDF2 和 JWT 兼容、密钥脱敏、资金分配、最低成交额、步长取整、逢低买入条件、估值、收益和确定性订单 ID；
- MVC 契约测试覆盖每个现有 REST 路径、请求字段、响应字段、状态和中文错误文案；
- WebSocket 测试覆盖订阅解析、兼容价格消息、非法 JSON 和价格缺失；
- MySQL Testcontainers 测试加载现有结构样本，执行 Flyway 基线和迁移，校验 JPA 映射、关联关系和事务回滚；
- WordPress 模拟测试覆盖成功、凭据错误、Token 无效、服务不可用和用户同步；
- Binance 模拟测试覆盖凭据、余额、市场规则、下单成功、认证失败、限流、结果不明确和订单对账；
- 行情流测试覆盖交易对标准化、缓存新鲜度、断线、退避、重新订阅和定时 24 小时轮换；
- Quartz 测试覆盖创建、暂停、恢复、重启恢复和重复触发保护。

自动化测试绝不使用真实 API Key 或生产订单。只有提供 Testnet 凭据时，才通过可选 Testnet Profile 执行凭据、余额、市场规则和最小金额订单冒烟测试。

### 12.2 完成门槛

只有满足以下全部条件才视为交付完成：

1. `./mvnw verify` 无测试失败；
2. Docker 镜像构建成功；
3. Docker Compose 可从全新环境启动应用和 MySQL；
4. Flyway 可升级现有表结构样本，且不删除业务数据；
5. `/health`、代表性 REST 接口和 `/api/ws/price` 通过冒烟测试；
6. 默认配置可证明生产订单提交被禁用；
7. 契约检查表明确映射每个 Python 接口和调度行为到 Java 实现；
8. 仓库中不存在已提交的密钥或生成的构建产物。

## 13. 替换边界

当前仓库将成为 Java 实现。占位用的 `main.py` 在实施阶段删除。Python 源仓库只作为只读迁移输入，不做任何修改。源仓库未提交的策略响应行为会纳入迁移，硬编码代理变更则通过外部配置表达。
