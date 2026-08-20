# 智投宝 API Java 迁移实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标：** 在当前仓库中交付一个完整的 Java 26、Spring Boot 4.1.0 智投宝后端，兼容现有 Python API、WebSocket、MySQL 数据和前端行为，并使用 Binance 官方 Java Connector 实现现货行情与交易。

**架构：** 使用按业务能力划分的模块化单体。认证、交易所、行情、策略、计划和调度模块通过明确接口协作，交易所业务只依赖 `ExchangeGateway`，首版由 `BinanceExchangeGateway` 实现。应用以单个 JAR 和 Docker 镜像交付，Quartz 与 MySQL 持久化任务状态。

**技术栈：** Java 26、Spring Boot 4.1.0、Maven、Spring MVC、Spring WebSocket、Spring Security、Spring Data JPA、Flyway、Quartz、MySQL、Binance Spot Connector 10.1.1、JUnit 5、AssertJ、Mockito、MockWebServer、Testcontainers。

## 全局约束

- 使用截至实施日最新稳定 GA 组件，不使用 Milestone、RC、Snapshot 或 Early Access；搭建骨架前再次核对直接依赖版本。
- Java 包根为 `com.multind.bitpongo`，Maven 坐标为 `com.multind:zhitoubao:1.0.0-SNAPSHOT`。
- 保持现有 REST 路径、HTTP 方法、snake_case 字段、`{code,message,data}` 包装、中文提示和 `/api/ws/price` 消息兼容。
- 兼容现有 MySQL 表和数据，不删除或重建业务表；`user` 与 `order` 必须显式转义。
- 金额、价格、数量、手续费和收益率统一使用 `BigDecimal`，并显式指定舍入方式。
- 保留 `ExchangeGateway` 多交易所抽象，本次只实现 `binance`；其他代码返回明确的不支持错误。
- Binance 行情、账户、规则和现货交易使用官方 `io.github.binance:binance-spot`，不引入 CCXT。
- `BINANCE_LIVE_TRADING` 默认 `false`；自动化测试禁止访问生产下单接口或使用真实密钥。
- Python 源仓库只读；纳入当前未提交的策略创建响应，不迁移硬编码代理。
- 所有新行为严格执行测试先行：先看见正确原因的失败，再写最小实现并运行完整相关测试。
- 每个任务完成后创建独立提交；只有新鲜验证结果通过后才能声明任务完成。

## 文件结构

```text
src/main/java/com/multind/bitpongo-api/
├── ZhitoubaoApplication.java
├── common/          # 响应、异常、JSON、请求关联 ID
├── auth/            # JWT、PBKDF2、WordPress、用户与安全过滤器
├── exchange/        # ExchangeGateway、Binance 适配器、密钥与交易规则
├── market/          # Binance 行情连接、价格缓存、前端 WebSocket
├── strategy/        # 策略与币种配置
├── plan/            # 计划、订单、快照与估值
├── scheduler/       # Quartz、定投执行和订单对账
├── notification/    # 通知配置与钉钉
└── infrastructure/  # 外部客户端和持久化配置
src/main/resources/
├── application.yml
└── db/migration/
src/test/java/com/multind/bitpongo-api/
src/test/resources/
└── application-test.yml
```

---

### 任务 1：建立可复现的 Spring Boot 骨架

**文件：**
- 创建：`pom.xml`
- 创建：`.mvn/wrapper/maven-wrapper.properties`
- 创建：`mvnw`
- 创建：`mvnw.cmd`
- 创建：`src/main/java/com/multind/bitpongo-api/ZhitoubaoApplication.java`
- 创建：`src/main/resources/application.yml`
- 创建：`src/test/resources/application-test.yml`
- 创建：`src/test/java/com/multind/bitpongo-api/ZhitoubaoApplicationTest.java`
- 修改：`.gitignore`
- 删除：`main.py`

**接口：**
- 消费：无。
- 产出：可启动的 `BitpongoApplication`、统一配置前缀 `zhitoubao.*`、后续任务可用的 Maven 测试环境。

- [ ] **步骤 1：核对实施日稳定版本**

运行：

```bash
curl -fsSL https://www.oracle.com/java/technologies/downloads/
curl -fsSL https://spring.io/projects/spring-boot/
curl -fsSL https://repo1.maven.org/maven2/io/github/binance/binance-spot/maven-metadata.xml
```

预期：确认 Java、Spring Boot 与 Binance Spot 的最新稳定版本；若高于设计基线，只更新到新的稳定 GA，并在提交说明中记录。

- [ ] **步骤 2：先写无法编译的上下文测试**

```java
package com.multind.bitpongo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ZhitoubaoApplicationTest {
    @Test
    void applicationContextStarts() {
    }
}
```

- [ ] **步骤 3：运行测试确认红灯**

运行：`mvn -Dtest=ZhitoubaoApplicationTest test`

预期：因缺少 `pom.xml` 或 `BitpongoApplication` 无法构建。

- [ ] **步骤 4：创建最小项目和受控依赖**

`pom.xml` 至少声明：

```xml
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>4.1.0</version>
</parent>
<groupId>com.multind</groupId>
<artifactId>bitpongo-api</artifactId>
<version>1.0.0-SNAPSHOT</version>
<properties>
  <java.version>26</java.version>
  <binance-spot.version>10.1.1</binance-spot.version>
</properties>
```

加入 Web MVC、WebSocket、Security、Data JPA、Validation、Actuator、Quartz、Flyway、MySQL、Binance Spot、Test、Testcontainers MySQL、MockWebServer 和 Maven Enforcer。由 Spring Boot 管理的依赖不得重复写版本。

`application.yml` 同时设置 `server.port: ${SERVER_PORT:8000}`，保持现有部署端口。

应用入口：

```java
package com.multind.bitpongo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ZhitoubaoApplication {
    public static void main(String[] args) {
        SpringApplication.run(ZhitoubaoApplication.class, args);
    }
}
```

- [ ] **步骤 5：生成 Wrapper、清理占位文件并补充忽略规则**

运行：

```bash
mvn wrapper:wrapper
```

`.gitignore` 必须包含：

```gitignore
.venv/
__pycache__/
target/
.env
*.log
.idea/workspace.xml
```

删除仅用于 PyCharm 示例的 `main.py`，保留已有受版本控制的 IDE 配置不动。

- [ ] **步骤 6：运行绿灯和依赖收敛验证**

运行：`./mvnw -Dtest=ZhitoubaoApplicationTest verify`

预期：1 个上下文测试通过，Maven Enforcer 无依赖收敛错误。

- [ ] **步骤 7：提交**

```bash
git add pom.xml .mvn mvnw mvnw.cmd .gitignore src main.py
git commit -m "build: initialize Java 26 Spring Boot service"
```

### 任务 2：实现兼容响应、异常和基础端点

**文件：**
- 创建：`src/main/java/com/multind/bitpongo-api/common/api/ApiResponse.java`
- 创建：`src/main/java/com/multind/bitpongo-api/common/api/GlobalExceptionHandler.java`
- 创建：`src/main/java/com/multind/bitpongo-api/common/api/BusinessException.java`
- 创建：`src/main/java/com/multind/bitpongo-api/common/web/RequestCorrelationFilter.java`
- 创建：`src/main/java/com/multind/bitpongo-api/common/web/RootController.java`
- 创建：`src/test/java/com/multind/bitpongo-api/common/web/RootControllerTest.java`
- 创建：`src/test/java/com/multind/bitpongo-api/common/api/GlobalExceptionHandlerTest.java`

**接口：**
- 消费：任务 1 的 Spring MVC 测试环境。
- 产出：`ApiResponse<T>(int code, String message, T data)`、`BusinessException(int code, String message)`、兼容根端点和全局异常映射。

- [ ] **步骤 1：写兼容契约失败测试**

```java
@WebMvcTest(RootController.class)
class RootControllerTest {
    @Autowired MockMvc mvc;

    @Test
    void healthKeepsPythonResponse() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"ok\"}"));
    }

    @Test
    void rootKeepsPythonResponse() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Welcome to the API"));
    }
}
```

另写控制器夹具抛出 `new BusinessException(404, "交易计划不存在")`，断言响应为 `code=404`、`message=交易计划不存在`、`data=null`。

- [ ] **步骤 2：确认测试因类型和端点缺失而失败**

运行：`./mvnw -Dtest=RootControllerTest,GlobalExceptionHandlerTest test`

预期：编译失败，缺少 `RootController`、`BusinessException` 和处理器。

- [ ] **步骤 3：实现最小兼容层**

```java
public record ApiResponse<T>(int code, String message, T data) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(200, "ok", data);
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
```

`RootController` 精确返回 `Map.of("message", "Welcome to the API")` 和 `Map.of("status", "ok")`。`GlobalExceptionHandler` 分别处理业务异常、参数校验、认证失败和未知异常；未知异常对外返回 500，不暴露堆栈或密钥。

- [ ] **步骤 4：验证兼容层绿灯**

运行：`./mvnw -Dtest=RootControllerTest,GlobalExceptionHandlerTest test`

预期：全部通过，响应 JSON 只包含约定字段。

- [ ] **步骤 5：提交**

```bash
git add src/main/java/com/multind/bitpongo-api/common src/test/java/com/multind/bitpongo-api/common
git commit -m "feat: add compatible API response layer"
```

### 任务 3：建立现有 MySQL 结构、实体和 Repository

**文件：**
- 创建：`src/main/resources/db/migration/V1__legacy_business_schema.sql`
- 创建：`src/main/resources/db/migration/V2__quartz_and_order_idempotency.sql`
- 创建：`src/main/java/com/multind/bitpongo-api/auth/UserEntity.java`
- 创建：`src/main/java/com/multind/bitpongo-api/exchange/ExchangeEntity.java`
- 创建：`src/main/java/com/multind/bitpongo-api/strategy/StrategyEntity.java`
- 创建：`src/main/java/com/multind/bitpongo-api/strategy/CoinEntity.java`
- 创建：`src/main/java/com/multind/bitpongo-api/plan/PlanEntity.java`
- 创建：`src/main/java/com/multind/bitpongo-api/plan/OrderEntity.java`
- 创建：`src/main/java/com/multind/bitpongo-api/plan/SnapshotEntity.java`
- 创建：`src/main/java/com/multind/bitpongo-api/notification/DictEntity.java`
- 创建：`src/main/java/com/multind/bitpongo-api/scheduler/OrderIntentEntity.java`
- 创建：`src/main/java/com/multind/bitpongo-api/scheduler/OrderIntentRepository.java`
- 创建：各模块同目录下的 `*Repository.java`
- 创建：`src/test/java/com/multind/bitpongo-api/infrastructure/persistence/LegacySchemaCompatibilityTest.java`
- 创建：`src/test/resources/db/legacy-existing-schema.sql`
- 修改：`src/main/resources/application.yml`

**接口：**
- 消费：任务 1 的 JPA、Flyway 和 Testcontainers 依赖。
- 产出：8 个兼容业务实体及 Repository、订单对账意图实体；`OrderEntity.clientOrderId`；可对空库建表、可对现有库基线升级的 Flyway 配置。

- [ ] **步骤 1：写现有库兼容失败测试**

```java
@Testcontainers
@SpringBootTest
class LegacySchemaCompatibilityTest {
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:9.7.0")
            .withDatabaseName("zhitoubao")
            .withInitScript("db/legacy-existing-schema.sql");

    @Autowired PlanRepository plans;

    @Test
    void readsExistingPlanWithoutRecreatingBusinessTables() {
        PlanEntity plan = plans.findById(100L).orElseThrow();
        assertThat(plan.getStatus()).isEqualTo("active");
        assertThat(plan.getTotalFunds()).isEqualByComparingTo("100.50");
    }
}
```

夹具按 Python 当前模型建立全部 8 张表并插入一组用户、交易所、策略、计划和币种数据。

- [ ] **步骤 2：运行测试确认实体或表映射缺失**

运行：`./mvnw -Dtest=LegacySchemaCompatibilityTest test`

预期：因 `PlanRepository` 或实体不存在而失败。

- [ ] **步骤 3：实现精确实体映射**

字段映射必须覆盖：

| 实体 | 字段 |
|---|---|
| User | id, name, email, password, created_at, last_login |
| Exchange | id, name, exchange, access_key, secret_key, password, status, user_id, created_at |
| Strategy | id, name, instalment, exchange_id, frequency, cron, condition, user_id, created_at |
| Plan | id, total_funds, total_revenue, total_ratio, next_time, status, user_id, triggered_count, created_at, strategy_id, exchange_id |
| Coin | id, proportion, icon, min, max, average_down, symbol, average, total_amount, income, user_id, created_at, plan_id |
| Order | id, symbol, order_no, client_order_id, total_amount, average_price, total_cost, fee, user_id, created_at, plan_id |
| Snapshot | id, value, type, user_id, created_at, plan_id |
| Dict | id, type, sub_type, code, value, description, parent_code, enabled, sequence, created_at, updated_at |
| OrderIntent | id, client_order_id, plan_id, coin_id, user_id, symbol, quantity, scheduled_fire_time, status, attempts, created_at, updated_at |

所有财务字段使用 `BigDecimal`。关联默认 `LAZY`；集合不使用 Lombok 自动生成 `equals/hashCode/toString`。Repository 必须提供带 `userId` 的查询，例如：

```java
Optional<PlanEntity> findByIdAndUserId(Long id, Long userId);
List<PlanEntity> findByUserIdAndStatusNot(Long userId, String status);
Optional<ExchangeEntity> findByIdAndUserId(Long id, Long userId);
Optional<OrderEntity> findByClientOrderId(String clientOrderId);
```

- [ ] **步骤 4：实现 Flyway 基线和非破坏性增量**

`application.yml` 设置：

```yaml
spring:
  flyway:
    baseline-on-migrate: true
    baseline-version: 1
  jpa:
    hibernate:
      ddl-auto: validate
```

`V1` 创建与当前 Python 模型一致的新环境业务表，其中包含当前模型已经使用的各表 `user_id`；已有非空库会基线跳过。`V2` 只增加全新的 Quartz 表、`order.client_order_id`、`order_intent` 对账表和唯一索引，不重复增加现有业务列，也不删除已有列或数据。`order_intent.client_order_id` 与 `order.client_order_id` 分别唯一，用于记录外部调用前的意图和已确认订单。

- [ ] **步骤 5：验证空库与现有库两条路径**

运行：`./mvnw -Dtest=LegacySchemaCompatibilityTest test`

再增加并运行一个空库测试，断言 8 张业务表、Quartz 表和唯一索引存在。

预期：两种数据库路径均通过，Hibernate `validate` 成功。

- [ ] **步骤 6：提交**

```bash
git add src/main/resources src/main/java/com/multind/bitpongo-api/{auth,exchange,strategy,plan,notification,scheduler} src/test
git commit -m "feat: map legacy MySQL schema"
```

### 任务 4：实现 PBKDF2、JWT 和安全请求上下文

**文件：**
- 创建：`src/main/java/com/multind/bitpongo-api/auth/PasswordCompatibilityService.java`
- 创建：`src/main/java/com/multind/bitpongo-api/auth/JwtTokenService.java`
- 创建：`src/main/java/com/multind/bitpongo-api/auth/AuthenticatedUser.java`
- 创建：`src/main/java/com/multind/bitpongo-api/auth/AuthenticatedUserResolver.java`
- 创建：`src/main/java/com/multind/bitpongo-api/auth/WordPressAuthClient.java`
- 创建：`src/main/java/com/multind/bitpongo-api/auth/WordPressSession.java`
- 创建：`src/main/java/com/multind/bitpongo-api/auth/BearerAuthenticationFilter.java`
- 创建：`src/main/java/com/multind/bitpongo-api/auth/SecurityConfiguration.java`
- 创建：`src/test/java/com/multind/bitpongo-api/auth/PasswordCompatibilityServiceTest.java`
- 创建：`src/test/java/com/multind/bitpongo-api/auth/JwtTokenServiceTest.java`
- 创建：`src/test/java/com/multind/bitpongo-api/auth/SecurityConfigurationTest.java`

**接口：**
- 消费：任务 2 的兼容异常；任务 3 的 `UserRepository`。
- 产出：`PasswordCompatibilityService.matches/hash`、`JwtTokenService.issue/decode`、请求属性 `AuthenticatedUser(long id, String email, String name)`、外部认证端口 `WordPressAuthClient.login/resolveUser`。

- [ ] **步骤 1：写 Python 密码格式兼容失败测试**

```java
@Test
void verifiesHashProducedByPython() {
    String stored = "0123456789abcdef0123456789abcdef"
            + "9963c21abea5fad794d0b8ae206339a6c48de6ef46c9c9e5213c49679b50c5f6";
    assertThat(service.matches("correct-horse", stored)).isTrue();
    assertThat(service.matches("wrong", stored)).isFalse();
}
```

JWT 测试断言签发 Token 包含数值 `id` 和到期时间，错误签名及过期 Token 均拒绝。

- [ ] **步骤 2：运行测试确认红灯**

运行：`./mvnw -Dtest=PasswordCompatibilityServiceTest,JwtTokenServiceTest test`

预期：缺少服务类而编译失败。

- [ ] **步骤 3：实现兼容密码和 JWT**

PBKDF2 使用 `PBKDF2WithHmacSHA256`、100,000 次迭代、256 位输出，盐为存储串前 32 个 ASCII 字符，比较使用常量时间。新哈希继续写入相同 96 字符格式。

```java
public interface JwtTokenService {
    String issue(long userId);
    long decodeUserId(String token);
}
```

密钥和有效期分别来自 `JWT_SECRET_KEY` 与 `JWT_ACCESS_TOKEN_EXPIRE_MINUTES`，配置缺失时生产 Profile 启动失败。

- [ ] **步骤 4：先写安全路径测试，再实现过滤器**

测试断言 `/`、`/health`、`/api/users/login`、`/api/users/v1/login`、`/api/users/register` 可匿名访问；`/api/plans/list/active` 无 Token 返回 401；有效 Token 将用户 ID 写入请求上下文。

实现 `BearerAuthenticationFilter`，只读取 `Authorization: Bearer <token>`，不得记录 Token。`AuthenticatedUserResolver` 先尝试本地 JWT，失败后委托任务 5 的 WordPress 校验端口。

外部认证端口在本任务固定，避免安全过滤器依赖后续 HTTP 实现：

```java
public interface WordPressAuthClient {
    WordPressSession login(String username, String password);
    AuthenticatedUser resolveUser(String token);
}

public record WordPressSession(String token, long userId,
                               String email, String displayName) {}
```

同时配置与 Python 服务一致的 CORS：允许来源来自 `BACKEND_CORS_ORIGINS`，默认 `*`；允许全部方法和请求头；使用 `*` 时不得同时启用凭据 Cookie。增加预检请求测试，断言前端跨域调用所需响应头存在。

- [ ] **步骤 5：运行认证测试绿灯**

运行：`./mvnw -Dtest=PasswordCompatibilityServiceTest,JwtTokenServiceTest,SecurityConfigurationTest test`

预期：全部通过，日志捕获中不存在密码或 Token。

- [ ] **步骤 6：提交**

```bash
git add src/main/java/com/multind/bitpongo-api/auth src/test/java/com/multind/bitpongo-api/auth src/main/resources/application.yml
git commit -m "feat: add compatible authentication primitives"
```

### 任务 5：迁移用户和 WordPress 认证接口

**文件：**
- 创建：`src/main/java/com/multind/bitpongo-api/auth/HttpWordPressAuthClient.java`
- 创建：`src/main/java/com/multind/bitpongo-api/auth/UserApplicationService.java`
- 创建：`src/main/java/com/multind/bitpongo-api/auth/UserController.java`
- 创建：`src/main/java/com/multind/bitpongo-api/auth/UserDtos.java`
- 创建：`src/test/java/com/multind/bitpongo-api/auth/UserControllerContractTest.java`
- 创建：`src/test/java/com/multind/bitpongo-api/auth/HttpWordPressAuthClientTest.java`

**接口：**
- 消费：`UserRepository`、`PasswordCompatibilityService`、`JwtTokenService`、`ApiResponse`。
- 产出：`WordPressAuthClient` 的 HTTP 实现；用户登录、注册、个人资料与 WordPress 登录端点。

- [ ] **步骤 1：写全部用户端点契约测试**

```java
@Test
void wordpressLoginKeepsExistingPayload() throws Exception {
    mvc.perform(post("/api/users/v1/login")
            .contentType(APPLICATION_JSON)
            .content("{\"username\":\"u@example.com\",\"password\":\"secret\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.token").value("wp-token"))
        .andExpect(jsonPath("$.data.info.id").value(4))
        .andExpect(jsonPath("$.data.info.email").value("u@example.com"));
}
```

同一测试类覆盖本地 `/login`、`/register`、`/profile`，以及用户名密码错误、用户重复、WordPress 不可用和 Token 无效。

- [ ] **步骤 2：确认控制器缺失导致红灯**

运行：`./mvnw -Dtest=UserControllerContractTest test`

预期：请求返回 404 或测试上下文缺少 `UserController`。

- [ ] **步骤 3：实现 DTO 和应用服务**

```java
record UserLoginRequest(String username, String password) {}
record UserCreateRequest(String name, String email, String password) {}
record UserInfo(long id, String name, String email) {}
record LoginData(String token, UserInfo info) {}
```

本地登录按邮箱查询并验证兼容密码后签发本地 JWT。WordPress 登录调用 Token 端点、解析 `data.user.id`，并按邮箱创建或更新本地用户。用户同步和登录时间更新位于单个事务。

- [ ] **步骤 4：实现并测试 WordPress HTTP 适配器**

使用 MockWebServer 精确验证：

```text
POST /wp-json/jwt-auth/v1/token
GET  /wp-json/wp/v2/users/me
Authorization: Bearer <token>
```

配置连接和读取超时；401 映射“登录失败，请检查用户名和密码”，连接失败映射“无法连接到服务器”。

- [ ] **步骤 5：运行用户与回归测试**

运行：`./mvnw -Dtest=UserControllerContractTest,HttpWordPressAuthClientTest,SecurityConfigurationTest test`

预期：用户契约与认证放行规则全部通过。

- [ ] **步骤 6：提交**

```bash
git add src/main/java/com/multind/bitpongo-api/auth src/test/java/com/multind/bitpongo-api/auth
git commit -m "feat: migrate user and WordPress authentication APIs"
```

### 任务 6：建立交易所领域端口和 Binance 官方适配器

**文件：**
- 创建：`src/main/java/com/multind/bitpongo-api/exchange/ExchangeGateway.java`
- 创建：`src/main/java/com/multind/bitpongo-api/exchange/ExchangeGatewayRegistry.java`
- 创建：`src/main/java/com/multind/bitpongo-api/exchange/ExchangeCredentials.java`
- 创建：`src/main/java/com/multind/bitpongo-api/exchange/AccountBalance.java`
- 创建：`src/main/java/com/multind/bitpongo-api/exchange/MarketRules.java`
- 创建：`src/main/java/com/multind/bitpongo-api/exchange/OrderResult.java`
- 创建：`src/main/java/com/multind/bitpongo-api/exchange/OrderSizingService.java`
- 创建：`src/main/java/com/multind/bitpongo-api/exchange/BinanceSpotClient.java`
- 创建：`src/main/java/com/multind/bitpongo-api/exchange/BinanceExchangeGateway.java`
- 创建：`src/test/java/com/multind/bitpongo-api/exchange/OrderSizingServiceTest.java`
- 创建：`src/test/java/com/multind/bitpongo-api/exchange/BinanceExchangeGatewayTest.java`
- 创建：`src/test/java/com/multind/bitpongo-api/exchange/ExchangeGatewayRegistryTest.java`

**接口：**
- 消费：Binance Spot Connector、`BusinessException`。
- 产出：设计文档定义的 `ExchangeGateway`；`OrderSizingService.calculate(BigDecimal instalment, BigDecimal proportion, BigDecimal price, MarketRules rules)`。

- [ ] **步骤 1：先写下单数量边界测试**

```java
@Test
void raisesToMinimumNotionalAndRoundsDownToStepSize() {
    MarketRules rules = new MarketRules(
            new BigDecimal("10"), new BigDecimal("0.0001"),
            new BigDecimal("0.0001"), new BigDecimal("1000"));
    BigDecimal quantity = service.calculate(
            new BigDecimal("5"), new BigDecimal("100"),
            new BigDecimal("62000"), rules);
    assertThat(quantity).isEqualByComparingTo("0.0001");
}
```

增加正常比例、最大数量、零价格、无效比例和取整后仍低于最低成交额的测试。

- [ ] **步骤 2：运行数量测试确认红灯**

运行：`./mvnw -Dtest=OrderSizingServiceTest test`

预期：缺少领域类型和服务。

- [ ] **步骤 3：实现纯领域计算和 Registry**

```java
public interface ExchangeGateway {
    AccountBalance verifyCredentials(ExchangeCredentials credentials);
    MarketRules getMarketRules(String symbol);
    OrderResult marketBuy(ExchangeCredentials credentials, String symbol,
                          BigDecimal quantity, String clientOrderId);
    Optional<OrderResult> findOrder(ExchangeCredentials credentials,
                                    String symbol, String clientOrderId);
}
```

`OrderSizingService` 只做 `BigDecimal` 计算，使用 `RoundingMode.DOWN` 对齐 step size。Registry 对 `binance` 返回实现，对其他代码抛出 `BusinessException(400, "不支持的交易所: " + code)`。

- [ ] **步骤 4：先写 Binance 适配器失败测试**

模拟 `BinanceSpotClient` 返回账户、`exchangeInfo`、新订单和按 `clientOrderId` 查询结果，断言领域映射；认证码 `-2015` 映射 401；429 映射可重试查询异常；下单超时映射“结果不明确”而不自动再次调用。

- [ ] **步骤 5：实现官方 SDK 包装和环境选择**

`BinanceSpotClient` 封装官方生成 API 的 `getAccount`、`exchangeInfo`、`newOrder` 和 `getOrder` 调用，使领域适配器不暴露生成 DTO。正式/测试地址由以下配置决定：

```yaml
zhitoubao:
  binance:
    live-trading: ${BINANCE_LIVE_TRADING:false}
    testnet-rest-base-url: ${BINANCE_TESTNET_REST_BASE_URL:https://testnet.binance.vision}
    production-rest-base-url: ${BINANCE_PRODUCTION_REST_BASE_URL:https://api.binance.com}
    market-stream-url: ${BINANCE_MARKET_STREAM_URL:wss://stream.binance.com:9443}
```

`effectiveRestBaseUrl()` 在 `liveTrading=false` 时只返回 Testnet 地址，在 `liveTrading=true` 时只返回生产地址，调用方不能自行传入地址绕过开关。

- [ ] **步骤 6：验证领域与适配器绿灯**

运行：`./mvnw -Dtest=OrderSizingServiceTest,BinanceExchangeGatewayTest,ExchangeGatewayRegistryTest test`

预期：全部通过，模拟下单超时只调用一次 `newOrder`。

- [ ] **步骤 7：提交**

```bash
git add src/main/java/com/multind/bitpongo-api/exchange src/test/java/com/multind/bitpongo-api/exchange src/main/resources/application.yml
git commit -m "feat: add Binance exchange gateway"
```

### 任务 7：迁移交易所 REST API

**文件：**
- 创建：`src/main/java/com/multind/bitpongo-api/exchange/ExchangeApplicationService.java`
- 创建：`src/main/java/com/multind/bitpongo-api/exchange/ExchangeController.java`
- 创建：`src/main/java/com/multind/bitpongo-api/exchange/ExchangeDtos.java`
- 创建：`src/main/java/com/multind/bitpongo-api/exchange/CredentialMasker.java`
- 创建：`src/test/java/com/multind/bitpongo-api/exchange/ExchangeControllerContractTest.java`
- 创建：`src/test/java/com/multind/bitpongo-api/exchange/CredentialMaskerTest.java`

**接口：**
- 消费：`ExchangeRepository`、`ExchangeGatewayRegistry`、`AuthenticatedUser`。
- 产出：现有 `/api/exchanges/**` 全部接口；`CredentialMasker.maskAccessKey/maskSecretKey`。

- [ ] **步骤 1：写列表、详情和脱敏失败测试**

```java
@Test
void listOnlyReturnsCurrentUsersMaskedExchanges() throws Exception {
    mvc.perform(get("/api/exchanges/list").header("Authorization", "Bearer valid"))
       .andExpect(status().isOk())
       .andExpect(jsonPath("$.data[0].access_key").value("abc*****"))
       .andExpect(jsonPath("$.data[0].secret_key").doesNotExist());
}
```

覆盖详情前后四位脱敏、创建写入 `user_id`、更新、删除、越权 404、密钥校验和 `minimumAmount` 求和。

- [ ] **步骤 2：运行测试确认端点缺失**

运行：`./mvnw -Dtest=ExchangeControllerContractTest,CredentialMaskerTest test`

预期：404 或缺少应用服务。

- [ ] **步骤 3：实现 DTO、脱敏器和事务服务**

```java
record ExchangeUpsertRequest(String name, String exchange,
        String accessKey, String secretKey, String password, String status) {}
record ExchangeCheckRequest(Long id, String exchange,
        String accessKey, String secretKey) {}
record MinimumAmountRequest(long exchangeId, List<String> coins) {}
```

所有 Repository 操作必须包含当前 `userId`。更新请求中的脱敏占位值不得覆盖数据库真实密钥。`minimumAmount` 将币种标准化为 `BTCUSDT` 后读取每个市场规则的最低成交额并求和。

- [ ] **步骤 4：运行交易所 API 绿灯和安全日志断言**

运行：`./mvnw -Dtest=ExchangeControllerContractTest,CredentialMaskerTest test`

预期：全部通过，捕获日志中没有完整 Access Key 或 Secret Key。

- [ ] **步骤 5：提交**

```bash
git add src/main/java/com/multind/bitpongo-api/exchange src/test/java/com/multind/bitpongo-api/exchange
git commit -m "feat: migrate exchange REST APIs"
```

### 任务 8：实现 Binance 行情缓存和连接生命周期

**文件：**
- 创建：`src/main/java/com/multind/bitpongo-api/market/PriceCache.java`
- 创建：`src/main/java/com/multind/bitpongo-api/market/MarketPrice.java`
- 创建：`src/main/java/com/multind/bitpongo-api/market/TickerEvent.java`
- 创建：`src/main/java/com/multind/bitpongo-api/market/SymbolNormalizer.java`
- 创建：`src/main/java/com/multind/bitpongo-api/market/BinanceMarketStreamClient.java`
- 创建：`src/main/java/com/multind/bitpongo-api/market/BinanceMarketStreamLifecycle.java`
- 创建：`src/main/java/com/multind/bitpongo-api/market/MarketStreamHealthIndicator.java`
- 创建：`src/test/java/com/multind/bitpongo-api/market/PriceCacheTest.java`
- 创建：`src/test/java/com/multind/bitpongo-api/market/SymbolNormalizerTest.java`
- 创建：`src/test/java/com/multind/bitpongo-api/market/BinanceMarketStreamLifecycleTest.java`

**接口：**
- 消费：官方 `SpotWebSocketStreams`，应用 `Clock` 和调度执行器。
- 产出：`PriceCache.put/getFresh`、`SymbolNormalizer.toBinance/toInternal`、可启动停止的行情生命周期和健康指标。

- [ ] **步骤 1：写缓存和符号转换失败测试**

```java
@Test
void returnsOnlyFreshPrice() {
    cache.put("binance", "BTC/USDT", new BigDecimal("62000"), instant);
    assertThat(cache.getFresh("binance", "BTC/USDT", instant.plusSeconds(20))).contains(new BigDecimal("62000"));
    assertThat(cache.getFresh("binance", "BTC/USDT", instant.plusSeconds(61))).isEmpty();
}
```

断言 `BTCUSDT -> BTC/USDT`、`btc/usdt -> BTCUSDT`，不接受非 USDT 或空币种。

- [ ] **步骤 2：确认缓存测试红灯**

运行：`./mvnw -Dtest=PriceCacheTest,SymbolNormalizerTest test`

预期：缺少缓存和标准化类型。

- [ ] **步骤 3：实现线程安全缓存和标准化**

使用 `ConcurrentHashMap<PriceKey, MarketPrice>`，`MarketPrice` 包含 `BigDecimal price` 和 `Instant updatedAt`。过期阈值来自 `MARKET_PRICE_MAX_AGE_SECONDS`，默认 60 秒。

- [ ] **步骤 4：先写连接生命周期测试**

用可控假客户端和时钟断言：启动时只建立一个连接；收到 ticker 更新缓存；异常后按 1、2、4 秒退避且上限 60 秒；成功连接后重置退避；23 小时 50 分主动轮换；关闭应用时取消连接和任务。

- [ ] **步骤 5：实现官方行情流适配器和健康指标**

`BinanceMarketStreamClient` 只暴露：

```java
interface BinanceMarketStreamClient {
    StreamHandle connect(Consumer<TickerEvent> onTicker,
                         Consumer<Throwable> onFailure,
                         Runnable onClosed);
}
```

生产实现使用官方 `SpotWebSocketStreams` 的全市场 mini ticker 或 ticker Stream。遵守 Binance ping/pong、每连接最多 1024 Streams 和 24 小时连接限制。健康指标在连接断开或最后消息超时后报告 `DOWN`。

- [ ] **步骤 6：运行行情测试**

运行：`./mvnw -Dtest=PriceCacheTest,SymbolNormalizerTest,BinanceMarketStreamLifecycleTest test`

预期：全部通过，无真实网络连接。

- [ ] **步骤 7：提交**

```bash
git add src/main/java/com/multind/bitpongo-api/market src/test/java/com/multind/bitpongo-api/market
git commit -m "feat: add resilient Binance market stream"
```

### 任务 9：迁移前端价格 WebSocket

**文件：**
- 创建：`src/main/java/com/multind/bitpongo-api/market/PriceWebSocketConfiguration.java`
- 创建：`src/main/java/com/multind/bitpongo-api/market/PriceWebSocketHandler.java`
- 创建：`src/main/java/com/multind/bitpongo-api/market/PriceSubscription.java`
- 创建：`src/test/java/com/multind/bitpongo-api/market/PriceWebSocketContractTest.java`

**接口：**
- 消费：`PriceCache`、`SymbolNormalizer`。
- 产出：兼容 `/api/ws/price` WebSocket。

- [ ] **步骤 1：写 WebSocket 兼容失败测试**

```java
@Test
void subscriptionReturnsOneMessagePerAvailableSymbol() {
    handler.handleTextMessage(session,
        new TextMessage("{\"action\":\"subscribe\",\"symbols\":[\"BTC\",\"ETH\"],\"exchange\":\"binance\"}"));
    assertThat(sentJson).containsExactly(
        "{\"symbol\":\"BTC\",\"price\":62000,\"exchange\":\"binance\"}",
        "{\"symbol\":\"ETH\",\"price\":3200,\"exchange\":\"binance\"}");
}
```

增加非法 JSON 返回 `Invalid JSON`、缓存未初始化、价格缺失和非 Binance 交易所测试。

- [ ] **步骤 2：确认处理器缺失导致红灯**

运行：`./mvnw -Dtest=PriceWebSocketContractTest test`

预期：缺少 Handler 或路径未注册。

- [ ] **步骤 3：实现最小兼容处理器**

只接受 `action=subscribe`。逐个币种读取新鲜缓存并立即发送现有字段，不创建额外 Binance 连接，不把一个浏览器错误传播给其他会话。

- [ ] **步骤 4：运行 WebSocket 测试绿灯**

运行：`./mvnw -Dtest=PriceWebSocketContractTest test`

预期：全部兼容场景通过。

- [ ] **步骤 5：提交**

```bash
git add src/main/java/com/multind/bitpongo-api/market src/test/java/com/multind/bitpongo-api/market
git commit -m "feat: migrate price WebSocket contract"
```

### 任务 10：迁移策略创建与活动策略接口

**文件：**
- 创建：`src/main/java/com/multind/bitpongo-api/strategy/StrategyApplicationService.java`
- 创建：`src/main/java/com/multind/bitpongo-api/strategy/StrategyController.java`
- 创建：`src/main/java/com/multind/bitpongo-api/strategy/StrategyDtos.java`
- 创建：`src/main/java/com/multind/bitpongo-api/scheduler/PlanScheduleService.java`
- 创建：`src/test/java/com/multind/bitpongo-api/strategy/StrategyApplicationServiceTest.java`
- 创建：`src/test/java/com/multind/bitpongo-api/strategy/StrategyControllerContractTest.java`

**接口：**
- 消费：`StrategyRepository`、`PlanRepository`、`CoinRepository`、`AuthenticatedUser`；调度通过 `PlanScheduleService.schedule(planId, cron)`。
- 产出：`POST /api/strategies/create`、`GET /api/strategies/list/active`、`StrategyCreatedData(strategy, plan, coins)`、调度端口 `PlanScheduleService.schedule/pause/resume/remove`。

- [ ] **步骤 1：写事务和当前响应行为失败测试**

```java
@Test
void createsStrategyPlanAndCoinsForAuthenticatedUser() {
    StrategyCreatedData result = service.create(7L, request);
    assertThat(result.strategy().getUserId()).isEqualTo(7L);
    assertThat(result.plan().getStatus()).isEqualTo("active");
    assertThat(result.coins()).hasSize(2).allMatch(c -> c.getPlanId().equals(result.plan().getId()));
    verify(scheduleService).schedule(result.plan().getId(), "0 0 8 * * ?");
}
```

增加币种写入失败导致策略与计划全部回滚、无效 Cron、比例合计不为 100、越权交易所测试。

- [ ] **步骤 2：运行服务测试确认红灯**

运行：`./mvnw -Dtest=StrategyApplicationServiceTest test`

预期：缺少应用服务和 DTO。

- [ ] **步骤 3：实现事务服务和 DTO**

```java
record CoinRequest(BigDecimal proportion, String icon, BigDecimal min,
        BigDecimal max, boolean averageDown, String symbol, boolean checked) {}
record StrategyCreateRequest(String name, BigDecimal instalment, long exchangeId,
        String frequency, String cron, String condition, List<CoinRequest> coins) {}
```

策略、活动计划和币种在一个事务中创建。计划初值为零，`next_time` 由 Cron 计算。成功提交后才注册 Quartz 任务；调度失败时记录并由启动核对流程补偿。

在本任务创建稳定调度端口，供策略和计划模块先行编译：

```java
public interface PlanScheduleService {
    void schedule(long planId, String cron);
    void pause(long planId);
    void resume(long planId, String cron);
    void remove(long planId);
}
```

- [ ] **步骤 4：实现并验证 Controller 契约**

断言创建响应精确包含 `data.strategy`、`data.plan`、`data.coins`，活动列表仅返回当前用户数据。

运行：`./mvnw -Dtest=StrategyApplicationServiceTest,StrategyControllerContractTest test`

预期：事务、用户隔离和当前未提交 Python 响应行为全部通过。

- [ ] **步骤 5：提交**

```bash
git add src/main/java/com/multind/bitpongo-api/{strategy,scheduler} src/test/java/com/multind/bitpongo-api/strategy
git commit -m "feat: migrate strategy APIs"
```

### 任务 11：迁移计划查询、状态和估值

**文件：**
- 创建：`src/main/java/com/multind/bitpongo-api/plan/PlanApplicationService.java`
- 创建：`src/main/java/com/multind/bitpongo-api/plan/PlanController.java`
- 创建：`src/main/java/com/multind/bitpongo-api/plan/PlanDtos.java`
- 创建：`src/main/java/com/multind/bitpongo-api/plan/PortfolioCalculator.java`
- 创建：`src/test/java/com/multind/bitpongo-api/plan/PortfolioCalculatorTest.java`
- 创建：`src/test/java/com/multind/bitpongo-api/plan/PlanControllerContractTest.java`

**接口：**
- 消费：计划、订单、币种、快照 Repository；`PriceCache`；`PlanScheduleService`。
- 产出：现有 `/api/plans/**` 接口；`PortfolioCalculator.value/revenue/ratio`。

- [ ] **步骤 1：写精确财务计算失败测试**

```java
@Test
void calculatesPortfolioWithBigDecimal() {
    BigDecimal value = calculator.value(List.of(
        new Position(new BigDecimal("0.0100"), new BigDecimal("62000")),
        new Position(new BigDecimal("0.5000"), new BigDecimal("3200"))));
    assertThat(value).isEqualByComparingTo("2220.0000");
    assertThat(calculator.ratio(value, new BigDecimal("2000")))
        .isEqualByComparingTo("11.0000");
}
```

覆盖零投入、缺失价格、过期价格和小数精度。

- [ ] **步骤 2：确认计算器缺失导致红灯**

运行：`./mvnw -Dtest=PortfolioCalculatorTest test`

预期：缺少计算器和 Position。

- [ ] **步骤 3：实现计划服务和用户隔离**

活动列表查询 `status != close AND user_id = 当前用户`，详情加载策略、币种、订单和快照，并用新鲜行情填充 `total_value`。不存在或越权统一返回“交易计划不存在”。

状态只允许 `active`、`stop`、`close`；离开活动状态取消任务，进入活动状态恢复任务。

- [ ] **步骤 4：实现并运行全部计划契约测试**

测试 `/list/active`、`/{plan_id}`、`/{plan_id}/{plan_status}` 的 URL、响应嵌套结构、用户隔离、404 和调度调用。

运行：`./mvnw -Dtest=PortfolioCalculatorTest,PlanControllerContractTest test`

预期：现有计划契约全部通过。

- [ ] **步骤 5：提交**

```bash
git add src/main/java/com/multind/bitpongo-api/plan src/test/java/com/multind/bitpongo-api/plan
git commit -m "feat: migrate plan APIs and valuation"
```

### 任务 12：实现 Quartz 持久化与任务恢复

**文件：**
- 修改：`src/main/java/com/multind/bitpongo-api/scheduler/PlanScheduleService.java`
- 创建：`src/main/java/com/multind/bitpongo-api/scheduler/QuartzPlanScheduleService.java`
- 创建：`src/main/java/com/multind/bitpongo-api/scheduler/ScheduledPurchaseUseCase.java`
- 创建：`src/main/java/com/multind/bitpongo-api/scheduler/AssetSnapshotUseCase.java`
- 创建：`src/main/java/com/multind/bitpongo-api/scheduler/PlanPurchaseJob.java`
- 创建：`src/main/java/com/multind/bitpongo-api/scheduler/AssetSnapshotJob.java`
- 创建：`src/main/java/com/multind/bitpongo-api/scheduler/ScheduleReconciler.java`
- 创建：`src/main/java/com/multind/bitpongo-api/scheduler/QuartzHealthIndicator.java`
- 创建：`src/test/java/com/multind/bitpongo-api/scheduler/QuartzPlanScheduleServiceTest.java`
- 创建：`src/test/java/com/multind/bitpongo-api/scheduler/ScheduleReconcilerIntegrationTest.java`
- 修改：`src/main/resources/application.yml`

**接口：**
- 消费：任务 10 的 `PlanScheduleService`、`PlanRepository`。
- 产出：`PlanScheduleService` 的 Quartz 实现、`ScheduledPurchaseUseCase.execute(planId, scheduledFireTime)`、`AssetSnapshotUseCase.captureAll()`、启动核对和 Quartz 健康状态。Quartz Job 只依赖这两个用例接口，因此本任务可在具体交易和快照实现之前独立编译测试。

- [ ] **步骤 1：写稳定 JobKey 和恢复失败测试**

```java
@Test
void usesStablePlanJobIdentity() {
    service.schedule(42L, "0 0 8 * * ?");
    assertThat(scheduler.checkExists(JobKey.jobKey("job_plan_42", "plans"))).isTrue();
}
```

集成测试先建立两个活动计划、一个停止计划和一个缺失任务，重启应用上下文后断言只存在两个活动任务且没有重复 Trigger。

- [ ] **步骤 2：运行测试确认红灯**

运行：`./mvnw -Dtest=QuartzPlanScheduleServiceTest,ScheduleReconcilerIntegrationTest test`

预期：缺少调度服务或 Quartz 配置。

- [ ] **步骤 3：实现 JDBC JobStore 和任务服务**

```yaml
spring:
  quartz:
    job-store-type: jdbc
    jdbc:
      initialize-schema: never
    properties:
      org.quartz.jobStore.isClustered: true
```

JobDataMap 只保存 `planId`，不保存实体或密钥。任务使用稳定 JobKey 和 TriggerKey。每小时资产快照使用 `job_asset_snapshot`，重复创建必须幂等。

- [ ] **步骤 4：实现启动核对和健康指标**

`ScheduleReconciler` 在应用就绪后比较活动计划与 Quartz：补缺失、修正 Cron、删除非活动计划残留任务，不触发补跑旧任务。健康指标验证 Scheduler 已启动且不处于 standby/shutdown。

两个 Job 用例端口固定为：

```java
public interface ScheduledPurchaseUseCase {
    void execute(long planId, Instant scheduledFireTime);
}

public interface AssetSnapshotUseCase {
    void captureAll();
}
```

- [ ] **步骤 5：运行 Quartz 测试绿灯**

运行：`./mvnw -Dtest=QuartzPlanScheduleServiceTest,ScheduleReconcilerIntegrationTest test`

预期：创建、暂停、恢复、删除、重启恢复和防重复全部通过。

- [ ] **步骤 6：提交**

```bash
git add src/main/java/com/multind/bitpongo-api/scheduler src/test/java/com/multind/bitpongo-api/scheduler src/main/resources/application.yml
git commit -m "feat: add persistent Quartz scheduling"
```

### 任务 13：实现定投执行、幂等下单和对账

**文件：**
- 创建：`src/main/java/com/multind/bitpongo-api/scheduler/ScheduledPurchaseService.java`
- 创建：`src/main/java/com/multind/bitpongo-api/scheduler/OrderIdFactory.java`
- 创建：`src/main/java/com/multind/bitpongo-api/scheduler/OrderPersistenceService.java`
- 创建：`src/main/java/com/multind/bitpongo-api/scheduler/OrderReconciliationService.java`
- 创建：`src/test/java/com/multind/bitpongo-api/scheduler/OrderIdFactoryTest.java`
- 创建：`src/test/java/com/multind/bitpongo-api/scheduler/ScheduledPurchaseServiceTest.java`
- 创建：`src/test/java/com/multind/bitpongo-api/scheduler/OrderReconciliationServiceTest.java`

**接口：**
- 消费：`ExchangeGateway`、`OrderSizingService`、`PriceCache`、计划/交易所/订单及 `OrderIntentRepository`、`PortfolioCalculator`。
- 产出：实现 `ScheduledPurchaseUseCase.execute(long planId, Instant scheduledFireTime)`，并提供 `reconcilePending()`。

- [ ] **步骤 1：写确定性订单 ID 和逢低条件失败测试**

```java
@Test
void orderIdIsStableForSameScheduledFire() {
    String first = factory.create(42L, "BTCUSDT", Instant.parse("2026-08-08T00:00:00Z"));
    String second = factory.create(42L, "BTCUSDT", Instant.parse("2026-08-08T00:00:00Z"));
    assertThat(first).isEqualTo(second).hasSizeLessThanOrEqualTo(36);
}
```

服务测试覆盖：非活动计划跳过；`last_average` 与 `total_average`；价格过期跳过；比例分配；订单成功后更新币种和计划；同一触发时间重复执行不再次下单。

- [ ] **步骤 2：运行定投测试确认红灯**

运行：`./mvnw -Dtest=OrderIdFactoryTest,ScheduledPurchaseServiceTest test`

预期：缺少执行服务和订单 ID 工厂。

- [ ] **步骤 3：实现无外部调用的决策阶段**

先在只读事务中生成 `PurchaseDecision(planId, coinId, symbol, quantity, clientOrderId)` 列表。只有活动计划、新鲜价格、满足逢低条件且本地不存在该 `clientOrderId` 时才产生决策。随后在独立短事务中插入状态为 `READY` 的 `OrderIntentEntity`；唯一约束竞争失败表示该触发已被其他实例领取。

- [ ] **步骤 4：实现下单与单独持久化事务**

逐条将意图标记为 `SUBMITTING` 后调用 `marketBuy`。每个成功结果通过 `OrderPersistenceService` 的新事务保存订单、将意图标记为 `CONFIRMED`，并更新币种均价、数量、收益及计划累计投入、收益率、触发次数和下次执行时间。外部调用期间不持有数据库事务。

最后一笔币种订单完成后，计划触发次数只增加一次。部分币种失败时保留已确认成功的订单，并记录其余币种错误供下次人工或计划触发处理。

- [ ] **步骤 5：先写结果不明确与对账测试，再实现对账**

测试下单超时后调用 `findOrder`：已存在则只持久化一次；不存在则标记待确认，不立即重复下单；两次对账仍只产生一个本地订单。

结果不明确时将意图标记为 `PENDING_RECONCILIATION`。`OrderReconciliationService` 定时扫描该状态，按 `clientOrderId` 查询并通过数据库唯一约束实现最终幂等；确认 Binance 不存在订单后标记 `NOT_FOUND`，不在同一次对账中重新下单。

- [ ] **步骤 6：运行交易执行完整测试**

运行：`./mvnw -Dtest=OrderIdFactoryTest,ScheduledPurchaseServiceTest,OrderReconciliationServiceTest test`

预期：所有金融条件、幂等和对账场景通过；验证模拟下单超时没有第二次 `marketBuy`。

- [ ] **步骤 7：提交**

```bash
git add src/main/java/com/multind/bitpongo-api/scheduler src/test/java/com/multind/bitpongo-api/scheduler
git commit -m "feat: migrate scheduled purchase execution"
```

### 任务 14：迁移资产快照、通知配置和钉钉接口

**文件：**
- 创建：`src/main/java/com/multind/bitpongo-api/plan/AssetSnapshotService.java`
- 创建：`src/main/java/com/multind/bitpongo-api/notification/NotificationApplicationService.java`
- 创建：`src/main/java/com/multind/bitpongo-api/notification/DingTalkClient.java`
- 创建：`src/main/java/com/multind/bitpongo-api/notification/HttpDingTalkClient.java`
- 创建：`src/main/java/com/multind/bitpongo-api/notification/NotificationController.java`
- 创建：`src/test/java/com/multind/bitpongo-api/plan/AssetSnapshotServiceTest.java`
- 创建：`src/test/java/com/multind/bitpongo-api/notification/NotificationControllerContractTest.java`
- 创建：`src/test/java/com/multind/bitpongo-api/notification/HttpDingTalkClientTest.java`

**接口：**
- 消费：`PlanRepository`、`SnapshotRepository`、`DictRepository`、`ExchangeGatewayRegistry`。
- 产出：实现 `AssetSnapshotUseCase.captureAll()`，并提供 `POST /api/users/ding`、`GET /api/users/notices`。

- [ ] **步骤 1：写每用户资产快照失败测试**

测试两个用户的活动计划分别使用自身交易所密钥，读取 USDT free 余额并写入 `type=asset`、正确 `plan_id` 和 `user_id`；停止计划不写快照；单个 Binance 失败不阻断其他计划。

- [ ] **步骤 2：运行快照测试确认红灯**

运行：`./mvnw -Dtest=AssetSnapshotServiceTest test`

预期：缺少快照服务。

- [ ] **步骤 3：实现快照服务**

每个计划独立短事务保存 `SnapshotEntity`。余额使用 `BigDecimal.toPlainString()` 写入兼容的 `value` 字段，日志只记录计划 ID 和安全错误码。

- [ ] **步骤 4：写通知契约与签名测试**

断言 `/notices` 返回 `dict.code=notify_method_init` 的值；`/ding` 保留请求字段 `webhook` 和 `signed`，并发送包含“智投宝通知”、当前时间和“恭喜您！当您收到这条消息时，表示您已配置正确！”的 Markdown。签名测试使用固定时间验证 HMAC-SHA256 与 Base64/URL 编码结果。

- [ ] **步骤 5：实现通知端口和 HTTP 适配器**

```java
interface DingTalkClient {
    Map<String, Object> sendMarkdown(String webhook, String secret,
                                     String title, String content);
}
```

使用 MockWebServer 验证请求体和签名查询参数。Webhook 与 Secret 不进入日志。外部非 2xx 响应转换为兼容业务错误。

- [ ] **步骤 6：运行快照和通知测试**

运行：`./mvnw -Dtest=AssetSnapshotServiceTest,NotificationControllerContractTest,HttpDingTalkClientTest test`

预期：全部通过，用户隔离与现有文案保持一致。

- [ ] **步骤 7：提交**

```bash
git add src/main/java/com/multind/bitpongo-api/{plan,notification} src/test/java/com/multind/bitpongo-api/{plan,notification}
git commit -m "feat: migrate snapshots and notifications"
```

### 任务 15：完成可观测性、部署、契约矩阵和全量验收

**文件：**
- 创建：`src/main/java/com/multind/bitpongo-api/common/web/StructuredLoggingFilter.java`
- 创建：`src/main/java/com/multind/bitpongo-api/infrastructure/DatabaseHealthIndicator.java`
- 创建：`src/test/java/com/multind/bitpongo-api/contract/PythonApiContractTest.java`
- 创建：`src/test/java/com/multind/bitpongo-api/security/ProductionTradingGuardTest.java`
- 创建：`src/test/java/com/multind/bitpongo-api/security/SecretScanTest.java`
- 创建：`Dockerfile`
- 创建：`compose.yml`
- 创建：`.env.example`
- 创建：`README.md`
- 创建：`docs/python-java-contract-matrix.md`
- 创建：`scripts/smoke-test.sh`
- 修改：`src/main/resources/application.yml`

**接口：**
- 消费：前 14 个任务的完整应用。
- 产出：生产镜像、本地 Compose、迁移和回滚说明、全契约验证和最终测试证据。

- [ ] **步骤 1：先写生产交易保护和密钥扫描测试**

```java
@Test
void productionTradingIsDisabledByDefault() {
    assertThat(properties.liveTrading()).isFalse();
    assertThat(properties.restBaseUrl().getHost()).isEqualTo("testnet.binance.vision");
}
```

密钥扫描测试读取跟踪文件，拒绝 Python 源中的已知默认数据库密码、JWT 密钥格式、私钥头、GitHub Token 和非示例 Binance Secret。

- [ ] **步骤 2：运行安全测试确认缺少保护配置**

运行：`./mvnw -Dtest=ProductionTradingGuardTest,SecretScanTest test`

预期：因保护 Bean、`.env.example` 或扫描规则缺失而失败。

- [ ] **步骤 3：完成配置校验与可观测性**

生产 Profile 必须要求数据库凭据和 JWT Secret；真实交易要求显式开关、生产地址确认和启动日志警告。增加请求关联 ID、用户 ID、计划 ID 的结构化日志，以及数据库、Quartz、行情健康指标。所有敏感字段加入日志脱敏测试。

- [ ] **步骤 4：建立 Python-Java 契约矩阵和总契约测试**

`docs/python-java-contract-matrix.md` 每行记录源 Python 文件、方法、Java Controller/Job 和测试类。必须逐项包含 20 个 REST/基础端点、1 个 WebSocket、计划定投和资产快照：基础 2 个、用户 6 个、交易所 7 个、策略 2 个、计划 3 个。

`PythonApiContractTest` 对每条 REST 路径执行代表性请求，断言方法、路径、状态、`code/message/data` 和 snake_case 字段；不得只检查 Controller 映射数量。

- [ ] **步骤 5：创建 Docker 与环境示例**

Dockerfile 使用 Java 26 构建阶段和非 root 运行阶段。`compose.yml` 包含 MySQL 健康检查、应用依赖健康条件、持久卷和只通过变量注入的密钥。`.env.example` 使用明显的非生产示例值，并保持：

```dotenv
BINANCE_LIVE_TRADING=false
BINANCE_TESTNET_REST_BASE_URL=https://testnet.binance.vision
BINANCE_PRODUCTION_REST_BASE_URL=https://api.binance.com
BINANCE_MARKET_STREAM_URL=wss://stream.binance.com:9443
```

- [ ] **步骤 6：编写迁移、运行和回滚文档**

README 必须包含 Java 26 安装、`./mvnw verify`、本地 MySQL、现有库备份、Flyway 基线、Testnet 凭据、可选代理、Compose、健康检查、生产交易双确认和回滚步骤。回滚不得删除 Flyway 或 Quartz 表；说明如何停止 Java 服务并恢复 Python 服务。

- [ ] **步骤 7：运行完整 Maven 验证**

运行：

```bash
./mvnw clean verify
```

预期：单元、MVC、WebSocket、MySQL Testcontainers、Quartz、契约和安全测试全部通过，无失败或错误。

- [ ] **步骤 8：构建并验证镜像与 Compose**

运行：

```bash
docker build -t bitpongo-api:local .
docker compose up -d --build
./scripts/smoke-test.sh http://localhost:8000
docker compose ps
```

预期：应用与 MySQL 均为 healthy；`/health`、代表性 REST 和 `/api/ws/price` 冒烟检查通过；默认配置没有生产下单请求。

- [ ] **步骤 9：检查仓库和提交最终交付**

运行：

```bash
git diff --check
git status --short
git ls-files | rg '(^target/|\.class$|\.log$|^\.env$)'
```

预期：无空白错误；只存在本任务预期文件；最后一条命令无输出。

提交：

```bash
git add Dockerfile compose.yml .env.example README.md docs scripts src pom.xml
git commit -m "docs: add deployment and migration verification"
```

## 最终验收检查表

- [ ] Java 26、Spring Boot 4.1.0 及直接依赖均为实施日最新稳定 GA。
- [ ] 全部 Python REST、WebSocket 和定时行为在契约矩阵中有 Java 实现及测试。
- [ ] 现有 MySQL 数据可直接读取，空库可由 Flyway 创建，业务表无破坏性迁移。
- [ ] Binance 官方 REST 与行情 Stream 生效，CCXT 不在依赖树中。
- [ ] 多交易所接口存在，首版仅 `binance` 可用。
- [ ] `BINANCE_LIVE_TRADING=false` 默认生效，自动测试无真实下单。
- [ ] Quartz 任务可持久化、暂停、恢复并在重启后核对。
- [ ] 订单 ID 确定、下单不盲目重试、对账不会重复入库。
- [ ] 所有财务计算使用 `BigDecimal`，关键舍入测试通过。
- [ ] 日志和响应不泄露密码、Token 或交易所 Secret。
- [ ] `./mvnw clean verify`、Docker 构建、Compose 健康检查和冒烟测试有新鲜成功输出。
