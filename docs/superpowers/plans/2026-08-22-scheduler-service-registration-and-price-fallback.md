# Scheduler Service Registration and Price Fallback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 恢复定时交易核心服务的 Spring Bean 注册和必需依赖快速失败，并在 WebSocket 行情不可用时使用 Binance REST ticker 安全补价。

**Architecture:** 四个核心服务使用 `@Service` 和普通构造器注入，生产依赖缺失时由 Spring 在启动阶段报告错误。行情仍优先读取 `PriceCache`，只有缓存为空或过期时才通过 `ExchangeGateway.latestPrice(String)` 获取 REST 行情；补价失败不会创建订单意图或提交订单。

**Tech Stack:** Java 26、Spring Boot 4.1.0、Spring Data JPA、Quartz、JUnit 5、Mockito、Binance Spot Connector 11.0.1、Maven。

## Global Constraints

- 保留 `ExchangeGateway` 多交易所抽象，调度模块不得直接依赖 Binance SDK。
- WebSocket 是主要行情来源；REST ticker 只作为缓存失效时的降级路径。
- 必需数据库依赖不得通过 `ObjectProvider` 静默降级。
- 保持确定性 `clientOrderId`、数据库唯一约束和远端订单查询机制不变。
- REST 补价失败时不得创建订单意图、不得提交订单。
- `pom.xml` 品牌元数据与运行时修复分开提交。

---

### Task 1: 用容器测试锁定核心服务注册

**Files:**
- Modify: `src/test/java/com/multind/bitpongo/BitpongoApplicationTest.java`
- Modify: `src/main/java/com/multind/bitpongo/plan/AssetSnapshotService.java`
- Modify: `src/main/java/com/multind/bitpongo/scheduler/OrderPersistenceService.java`
- Modify: `src/main/java/com/multind/bitpongo/scheduler/OrderReconciliationService.java`
- Modify: `src/main/java/com/multind/bitpongo/scheduler/ScheduledPurchaseService.java`
- Modify: `src/test/java/com/multind/bitpongo/plan/AssetSnapshotServiceTest.java`
- Modify: `src/test/java/com/multind/bitpongo/scheduler/OrderPersistenceServiceTest.java`
- Modify: `src/test/java/com/multind/bitpongo/scheduler/OrderReconciliationServiceTest.java`
- Modify: `src/test/java/com/multind/bitpongo/scheduler/ScheduledPurchaseServiceTest.java`
- Delete: `src/test/java/com/multind/bitpongo/scheduler/TestProviders.java`

**Interfaces:**
- Consumes: Spring 组件扫描、现有 Repository 接口、`ScheduledPurchaseUseCase`、`AssetSnapshotUseCase`。
- Produces: 唯一的 `ScheduledPurchaseService`、`AssetSnapshotService`、`OrderPersistenceService` 和 `OrderReconciliationService` Bean。

- [ ] **Step 1: 写失败的 Spring Bean 注册测试**

在 `BitpongoApplicationTest` 注入 `ApplicationContext`，为测试配置中排除的数据层增加 `@MockitoBean`，并断言：

```java
@Autowired private ApplicationContext context;
@MockitoBean private PlanRepository plans;
@MockitoBean private SnapshotRepository snapshots;
@MockitoBean private StrategyRepository strategies;
@MockitoBean private CoinRepository coins;
@MockitoBean private OrderRepository orders;
@MockitoBean private OrderIntentRepository intents;
@MockitoBean private JdbcTemplate jdbc;

@Test
void schedulerServicesAreRegistered() {
    assertThat(context.getBean(ScheduledPurchaseUseCase.class))
            .isInstanceOf(ScheduledPurchaseService.class);
    assertThat(context.getBean(AssetSnapshotUseCase.class))
            .isInstanceOf(AssetSnapshotService.class);
    assertThat(context.getBean(OrderPersistenceService.class)).isNotNull();
    assertThat(context.getBean(OrderReconciliationService.class)).isNotNull();
}
```

- [ ] **Step 2: 运行测试并确认按预期失败**

Run:

```bash
mvn -Dmaven.repo.local=/Volumes/ExternalDrive/maven-repo/.m2/repository \
  -Dtest=BitpongoApplicationTest#schedulerServicesAreRegistered test
```

Expected: FAIL，提示没有 `ScheduledPurchaseUseCase` 或对应服务 Bean。

- [ ] **Step 3: 最小化恢复组件注册和必需依赖注入**

四个类增加 `@Service`，Repository、`JdbcTemplate` 和服务依赖恢复普通构造器字段：

```java
@Service
public class ScheduledPurchaseService implements ScheduledPurchaseUseCase {
    private final PlanRepository plans;
    private final OrderPersistenceService persistence;

    public ScheduledPurchaseService(
            PlanRepository plans,
            StrategyRepository strategies,
            CoinRepository coins,
            OrderRepository orders,
            ExchangeRepository exchanges,
            OrderIntentRepository intents,
            ExchangeGatewayRegistry gateways,
            OrderSizingService sizing,
            PriceCache prices,
            OrderIdFactory orderIds,
            OrderPersistenceService persistence) {
        this.plans = plans;
        this.persistence = persistence;
    }
}
```

删除 `getIfAvailable()`、`require(ObjectProvider<T>)` 和依赖缺失时静默 `return` 的分支。`AssetSnapshotService` 中 `PlatformTransactionManager` 保持现有可选语义。

- [ ] **Step 4: 恢复测试的直接构造器调用**

各单元测试直接传入 Mockito mock，例如：

```java
new OrderPersistenceService(intents, orders, coins, plans, jdbc, clock);
```

删除只为本次 `ObjectProvider` 改造引入的 `TestProviders.java`。

- [ ] **Step 5: 运行 Bean 注册和相关单元测试**

Run:

```bash
mvn -Dmaven.repo.local=/Volumes/ExternalDrive/maven-repo/.m2/repository \
  -Dtest=BitpongoApplicationTest,AssetSnapshotServiceTest,OrderPersistenceServiceTest,OrderReconciliationServiceTest,ScheduledPurchaseServiceTest test
```

Expected: PASS，且四个服务都由 Spring 容器提供。

- [ ] **Step 6: 提交服务注册修复**

```bash
git add src/main/java/com/multind/bitpongo/plan/AssetSnapshotService.java \
  src/main/java/com/multind/bitpongo/scheduler/OrderPersistenceService.java \
  src/main/java/com/multind/bitpongo/scheduler/OrderReconciliationService.java \
  src/main/java/com/multind/bitpongo/scheduler/ScheduledPurchaseService.java \
  src/test/java/com/multind/bitpongo/BitpongoApplicationTest.java \
  src/test/java/com/multind/bitpongo/plan/AssetSnapshotServiceTest.java \
  src/test/java/com/multind/bitpongo/scheduler/OrderPersistenceServiceTest.java \
  src/test/java/com/multind/bitpongo/scheduler/OrderReconciliationServiceTest.java \
  src/test/java/com/multind/bitpongo/scheduler/ScheduledPurchaseServiceTest.java
git commit -m "fix: restore scheduler service registration"
```

---

### Task 2: 验证 WebSocket 缓存失效后的 REST 补价

**Files:**
- Modify: `src/main/java/com/multind/bitpongo/exchange/ExchangeGateway.java`
- Modify: `src/main/java/com/multind/bitpongo/exchange/BinanceSpotClient.java`
- Modify: `src/main/java/com/multind/bitpongo/exchange/BinanceExchangeGateway.java`
- Modify: `src/main/java/com/multind/bitpongo/exchange/OfficialBinanceSpotClient.java`
- Modify: `src/main/java/com/multind/bitpongo/scheduler/ScheduledPurchaseService.java`
- Modify: `src/test/java/com/multind/bitpongo/scheduler/ScheduledPurchaseServiceTest.java`

**Interfaces:**
- Consumes: `PriceCache.getFresh(String, String, Instant)` 和 Binance REST `tickerPrice`。
- Produces: `ExchangeGateway.latestPrice(String): BigDecimal`，供任意交易所实现。

- [ ] **Step 1: 增加缓存命中测试**

```java
@Test
void freshWebsocketPriceDoesNotCallRestTicker() {
    service.execute(42L, fire);

    verify(gateway, never()).latestPrice(anyString());
    verify(gateway).marketBuy(any(), eq("BTCUSDT"), any(), any());
}
```

- [ ] **Step 2: 增加缓存缺失时 REST 补价测试**

用过期价格覆盖测试缓存，令 `latestPrice("BTCUSDT")` 返回 `62000`，执行后断言 REST 被调用、订单继续提交，并且缓存获得该价格：

```java
prices.put("binance", "BTC/USDT", new BigDecimal("61000"),
        fire.minus(Duration.ofMinutes(2)));
when(gateway.latestPrice("BTCUSDT")).thenReturn(new BigDecimal("62000"));

service.execute(42L, fire);

verify(gateway).latestPrice("BTCUSDT");
verify(gateway).marketBuy(any(), eq("BTCUSDT"), any(), any());
assertThat(prices.getFresh("binance", "BTC/USDT", fire))
        .contains(new BigDecimal("62000"));
```

- [ ] **Step 3: 增加 REST 补价失败安全测试**

```java
prices.put("binance", "BTC/USDT", new BigDecimal("61000"),
        fire.minus(Duration.ofMinutes(2)));
when(gateway.latestPrice("BTCUSDT")).thenThrow(new RuntimeException("ticker unavailable"));

service.execute(42L, fire);

verify(intents, never()).saveAndFlush(any());
verify(gateway, never()).marketBuy(any(), anyString(), any(), any());
```

- [ ] **Step 4: 运行测试并验证其敏感性**

先运行新测试。由于 REST 补价代码已存在于审查前的未提交工作区，新测试可能直接通过；若通过，临时删除 `ScheduledPurchaseService` 的 REST fallback 分支并重跑，必须观察到补价成功测试失败，然后恢复分支并再次运行。不得把临时删除提交。

Run:

```bash
mvn -Dmaven.repo.local=/Volumes/ExternalDrive/maven-repo/.m2/repository \
  -Dtest=ScheduledPurchaseServiceTest test
```

Expected: 最终所有测试 PASS；临时移除 fallback 时，缓存缺失测试 FAIL。

- [ ] **Step 5: 保留最小生产实现**

确认实现仅包含以下降级流程，不修改订单幂等状态机：

```java
BigDecimal price = prices.getFresh(exchangeName, internalSymbol, clock.instant()).orElse(null);
if (price == null) {
    try {
        price = gateway.latestPrice(marketSymbol);
        prices.put(exchangeName, internalSymbol, price, clock.instant());
    } catch (RuntimeException failure) {
        log.warn("无新鲜行情且 REST 取价失败，跳过买入 planId={} coin={}",
                planId, coin.getSymbol(), failure);
        continue;
    }
}
```

- [ ] **Step 6: 提交 REST 补价修复**

```bash
git add src/main/java/com/multind/bitpongo/exchange/ExchangeGateway.java \
  src/main/java/com/multind/bitpongo/exchange/BinanceSpotClient.java \
  src/main/java/com/multind/bitpongo/exchange/BinanceExchangeGateway.java \
  src/main/java/com/multind/bitpongo/exchange/OfficialBinanceSpotClient.java \
  src/main/java/com/multind/bitpongo/scheduler/ScheduledPurchaseService.java \
  src/test/java/com/multind/bitpongo/scheduler/ScheduledPurchaseServiceTest.java
git commit -m "fix: fallback to Binance REST price"
```

---

### Task 3: 单独提交品牌元数据并完成验证

**Files:**
- Modify: `pom.xml`

**Interfaces:**
- Consumes: Maven 项目元数据。
- Produces: `bitpongo` 项目名称和 `Bitpongo API` 描述；不影响运行时行为。

- [ ] **Step 1: 检查品牌变更边界**

Run:

```bash
git diff -- pom.xml
```

Expected: 只有 `<name>zhitoubao</name>` 到 `<name>bitpongo</name>`、中文描述到 `Bitpongo API` 两处变化。

- [ ] **Step 2: 提交品牌元数据**

```bash
git add pom.xml
git commit -m "chore: align Maven project branding"
```

- [ ] **Step 3: 运行完整测试和打包**

Run:

```bash
mvn -Dmaven.repo.local=/Volumes/ExternalDrive/maven-repo/.m2/repository clean verify
```

Expected: BUILD SUCCESS，所有测试 0 failures、0 errors。

- [ ] **Step 4: 检查差异和提交状态**

Run:

```bash
git diff --check
git status --short
git log --oneline -5
```

Expected: `git diff --check` 无输出；工作区干净；日志包含设计、计划和三个边界清晰的提交。
