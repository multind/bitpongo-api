# 定时任务服务注册与行情补价设计

## 背景

当前定时买入、订单持久化、订单对账和资产快照服务曾使用
`@ConditionalOnBean` 控制注册。条件在组件扫描阶段可能无法按预期匹配，后续修改虽然移除了条件，
但没有恢复 Spring 组件注解，导致 Quartz Job 无法获得用例实现，订单对账的 `@Scheduled`
方法也不会注册。

Binance WebSocket 断开或缓存过期时，定时买入目前没有可用价格，会直接跳过该币种。
需要保留 WebSocket 作为主要行情来源，并通过 Binance REST ticker 提供一次性补价。

## 目标

- 确保四个核心服务始终由 Spring 容器注册。
- 必需的数据库和业务依赖缺失时在启动阶段失败，不在交易触发时静默跳过。
- WebSocket 行情缓存新鲜时继续使用缓存，不增加 REST 请求。
- 缓存为空或过期时通过交易所抽象获取最新价格，并写回缓存。
- REST 补价失败时不创建订单意图、不提交订单，并记录可定位的日志。
- 保持确定性 `clientOrderId`、订单幂等和远端对账机制不变。

## 方案比较

### 方案 A：恢复组件注册并使用必需依赖构造器注入（采用）

为 `AssetSnapshotService`、`OrderPersistenceService`、
`OrderReconciliationService` 和 `ScheduledPurchaseService` 恢复 `@Service`，删除
`@ConditionalOnBean`。Repository、`JdbcTemplate` 和服务间依赖使用普通构造器注入；仅已有的、
确实允许缺失的事务管理器保持可选。

优点是启动阶段即可暴露错误，符合交易任务的安全要求，测试也不需要模拟 `ObjectProvider`。

### 方案 B：保留全部 `ObjectProvider`

服务可以在依赖不完整时创建，但错误会推迟到定时任务执行时，部分路径还会静默返回。
这会把部署配置问题变成漏单风险，因此不采用。

### 方案 C：继续使用条件化注册并调整条件

可以为数据库关闭场景保留部分上下文，但 Quartz Job 和业务用例之间仍可能出现注册不一致。
当前应用的生产功能明确依赖数据库，因此没有必要增加这种复杂度。

## 组件与数据流

1. Quartz 创建 `PlanPurchaseJob`，Spring 注入唯一的 `ScheduledPurchaseUseCase` 实现。
2. `ScheduledPurchaseService` 读取计划、策略、交易所和币种。
3. 优先读取 `PriceCache` 中的新鲜 WebSocket 行情。
4. 缓存不可用时调用 `ExchangeGateway.latestPrice(symbol)`。
5. Binance 实现通过官方 Connector 的 ticker price REST API 获取价格并写回缓存。
6. 获得有效价格后沿用现有订单定量、订单意图、下单和对账流程。
7. REST 获取失败时仅跳过当前币种，不创建订单意图，也不盲目重试订单。

`latestPrice` 位于 `ExchangeGateway`，Binance SDK 细节不会泄漏到调度模块，未来交易所可提供各自实现。

## 错误处理

- Spring 必需依赖缺失：应用启动失败，直接暴露配置问题。
- WebSocket 行情缺失：尝试一次 REST 补价。
- REST 补价失败：记录计划 ID、币种和异常，跳过当前币种。
- 下单结果不明确：保持 `PENDING_RECONCILIATION` 和远端订单查询流程，不进行盲目重试。

## 测试设计

- Spring 容器测试断言四个服务 Bean 和两个用例接口都存在。
- 单元测试断言缓存新鲜时不调用 REST ticker。
- 单元测试断言缓存为空时调用 REST ticker、写回缓存并继续下单。
- 单元测试断言 REST ticker 失败时不创建订单意图、不提交订单。
- 保留并运行现有订单幂等、异常结果和对账测试。
- 完整运行 Maven 测试、打包和 `git diff --check`。

## 提交边界

功能修复提交只包含服务注册、依赖注入、REST 补价及其测试。`pom.xml` 中项目名称和描述的
品牌统一属于独立的 `chore` 提交，避免与运行时修复混在一起。
