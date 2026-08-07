# Zhitoubao API Java Migration Design

## 1. Objective

Replace the Python FastAPI service at `/Volumes/ExternalDrive/Code/Zhitoubao/zhitoubaoapi` with a Java service in this repository. The replacement is delivered as one complete application and preserves the existing frontend contract and MySQL data.

The implementation is a modular monolith. It retains a multi-exchange abstraction, implements Binance Spot only, and uses Binance's official Java Connector rather than CCXT. Real trading is disabled unless an explicit production switch is enabled.

## 2. Confirmed Scope

The Java service includes all behavior present in the source working tree:

- local and WordPress-backed login, registration, profile, notices, and DingTalk test notifications;
- exchange configuration CRUD, credential checks, balances, and minimum-order calculations;
- strategy creation with associated plans and coin allocations;
- active-plan listing, detail, status changes, current valuation, and profit calculations;
- persistent scheduled purchases and hourly asset snapshots;
- Binance market data ingestion and the frontend price WebSocket;
- database migrations, health checks, logging, Docker packaging, and deployment documentation.

The current uncommitted Python behavior that returns `strategy`, `plan`, and `coins` from strategy creation is part of the contract. The source's hard-coded local proxy is not copied; proxy configuration is externalized.

OKX is out of scope for this delivery. The exchange boundary must allow an OKX implementation to be added without changing strategy, plan, or scheduling modules.

## 3. Platform and Dependency Policy

Versions are the latest stable generally available releases as verified on 2026-08-07:

- Java 26, the latest Java GA release;
- Spring Boot 4.1.0;
- Maven with Maven Wrapper committed to the repository;
- `io.github.binance:binance-spot:10.1.1`, the latest stable Maven Central release;
- Spring Boot's dependency management for Spring Framework, Spring Security, Spring Data JPA, Hibernate, Jackson, Quartz, Micrometer, MySQL Driver, Flyway, JUnit, and Testcontainers-compatible transitive libraries;
- the latest stable direct dependency only when a dependency is not managed by the Spring Boot BOM.

Milestone, release-candidate, snapshot, and early-access dependencies are excluded. The build records all resolved versions and must pass Maven Enforcer dependency convergence checks. The implementation plan must recheck direct dependency versions before scaffolding in case a newer stable release is published.

Official references:

- <https://www.oracle.com/java/technologies/downloads/>
- <https://spring.io/projects/spring-boot/>
- <https://github.com/binance/binance-connector-java>
- <https://github.com/binance/binance-spot-api-docs/blob/master/web-socket-streams.md>

## 4. Architecture

The repository contains one Spring Boot application, one executable JAR, and one Docker image. Code is organized by business capability rather than as a global controller/service/repository stack.

### 4.1 Modules

- `auth`: local JWT, WordPress login and validation, local user synchronization, request authentication, and password compatibility.
- `exchange`: exchange credential storage, CRUD, masking, credential verification, account balance, market rules, and orders.
- `market`: Binance public stream lifecycle, current-price cache, symbol normalization, and the frontend WebSocket.
- `strategy`: strategy creation, coin allocation creation, and plan initialization as one transaction.
- `plan`: active plans, plan detail, status transitions, current valuation, profit, and revenue calculations.
- `scheduler`: Quartz jobs, persistent job recovery, scheduled purchases, hourly asset snapshots, and order reconciliation.
- `notification`: DingTalk notification delivery and notice configuration lookup.
- `common`: API response envelope, exceptions, JSON conventions, clock and identifiers, and shared configuration.
- `infrastructure`: JPA, Flyway, Binance Connector, WordPress HTTP client, database and external-client configuration.

Each module exposes focused application services. Controllers do not access repositories or external SDKs directly. Domain and application code depend on interfaces; infrastructure provides implementations.

### 4.2 Exchange Port

The initial boundary is equivalent to:

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

`BinanceExchangeGateway` implements the interface with the official Binance Spot REST client. An `ExchangeGatewayRegistry` resolves the configured exchange code and returns a clear unsupported-exchange response for every code except `binance`.

## 5. External Contract Compatibility

The Java service preserves existing HTTP methods and paths:

- `GET /` and `GET /health`;
- `/api/users/login`, `/register`, `/profile`, `/ding`, `/notices`, and `/v1/login`;
- `/api/exchanges/list`, `/{exchange_id}`, `/create`, `/check`, and `/minimumAmount`;
- `/api/strategies/create` and `/list/active`;
- `/api/plans/list/active`, `/{plan_id}/{plan_status}`, and `/{plan_id}`;
- WebSocket `/api/ws/price`.

Request and response JSON remains compatible with the current frontend. Jackson maps Java names to the existing snake-case names. Successful and business-error responses retain:

```json
{
  "code": 200,
  "message": "ok",
  "data": null
}
```

The WebSocket accepts the existing subscription message:

```json
{
  "action": "subscribe",
  "symbols": ["BTC", "ETH"],
  "exchange": "binance"
}
```

It emits one compatible message per available symbol with `symbol`, `price`, and `exchange`. Invalid JSON and unavailable prices produce the current error shape.

Compatibility does not require reproducing defects. Authentication control-flow errors, premature database-session closes, null dereferences, sensitive logging, and inconsistent exception wrapping are corrected while preserving paths, JSON fields, status semantics, and user-facing messages.

## 6. Database Compatibility and Migration

The service directly reads the existing MySQL schema and data. JPA mappings retain the tables `user`, `exchange`, `strategy`, `plan`, `coin`, `order`, `snapshot`, and `dict`, their column names, and their relationships. Reserved identifiers such as `user` and `order` are explicitly quoted.

Money, price, quantity, fee, and ratio calculations use `BigDecimal`. Existing floating-point columns remain readable; Flyway may add precision-preserving columns or change compatible numeric definitions only through reviewed, non-destructive migrations. No business table is dropped or recreated.

Flyway uses a baseline for an existing populated database and creates a clean schema from migrations for new environments. Incremental migrations add only:

- Quartz JDBC job-store tables;
- indexes or constraints required for safe user-scoped queries and order idempotency;
- compatible column adjustments proven necessary by integration tests.

The existing user IDs returned by WordPress remain the local user IDs. The original PBKDF2 representation, `32-character hexadecimal salt + 64-character hexadecimal SHA-256 hash` with 100,000 iterations, remains readable so existing local passwords work.

Transactions are owned by application services. Strategy, plan, and coin creation commit together. Database work never holds a transaction open across a long-lived WebSocket connection.

## 7. Authentication and Security

Public paths match the Python allowlist. All exchange, strategy, and plan routes require a Bearer token and enforce `user_id` ownership in every query.

The application supports:

- local JWT creation and validation with the existing `id` claim and configurable HS256 secret;
- WordPress JWT login through `/wp-json/jwt-auth/v1/token`;
- WordPress token/user validation through `/wp-json/wp/v2/users/me`;
- local synchronization of the WordPress user record.

Database credentials, JWT secret, WordPress URL, Binance endpoints, proxy, and trading switches come from environment variables. Source-controlled defaults contain no production passwords or secrets. Logs never contain plaintext passwords, Bearer tokens, Secret Keys, or complete Access Keys.

Exchange list and detail responses retain their current masking behavior. Secret Keys are never returned unmasked. New credentials remain compatible with the current database layout; encryption-at-rest is not introduced silently because it would make existing readers and column sizes incompatible.

## 8. Binance Market Data and Trading

### 8.1 Market Data

One application-managed Binance `SpotWebSocketStreams` client supplies market prices. Browser WebSocket sessions read the shared cache and never open their own Binance connections.

The client:

- subscribes to the Binance Spot ticker stream required to populate all requested USDT symbols;
- normalizes Binance `BTCUSDT` names to the internal `BTC/USDT` form;
- records price and update time in a thread-safe cache;
- responds to ping/pong correctly;
- reconnects with bounded exponential backoff and jitter;
- rotates before Binance's 24-hour connection lifetime;
- exposes connected state and last-message time through health indicators.

### 8.2 Market Rules

Binance `exchangeInfo` supplies symbol status, `LOT_SIZE`, `MARKET_LOT_SIZE`, `PRICE_FILTER`, and `MIN_NOTIONAL`/`NOTIONAL`. Rules are cached with a bounded lifetime and refreshed on rule-related Binance errors.

Allocated quote amount is calculated from plan instalment and coin proportion. Existing semantics are preserved: if the allocation is below the minimum notional, the target amount is raised to the minimum. Base quantity is rounded down to Binance step size, checked against minimum and maximum quantity, and revalidated against notional limits before submission.

### 8.3 Safe Trading and Idempotency

`BINANCE_LIVE_TRADING` defaults to `false`. With the default, signed account and order operations use Binance Spot Testnet. Production REST endpoints are used only when the switch is explicitly true. Public market data may use production streams in both modes.

Each scheduled purchase derives a deterministic `clientOrderId` from plan, symbol, and scheduled fire time. Query operations may retry on transient transport and rate-limit failures. An order submission is never blindly repeated: after an ambiguous result, the service queries by `clientOrderId` before deciding whether to submit or persist.

If Binance accepts an order but the database transaction fails, a reconciliation job retrieves the order by `clientOrderId` and persists it exactly once. A unique database constraint prevents duplicate local order records.

## 9. Scheduling and Calculations

Quartz uses the JDBC job store. At startup, the application reconciles active plans with Quartz so jobs survive restarts and missing jobs are restored without duplication.

- Creating a strategy creates its active plan and schedules `job_plan_<planId>` from the stored cron expression.
- Changing a plan away from `active` unschedules its purchase job.
- Returning a plan to `active` validates and restores its job.
- The asset snapshot job runs hourly and records the user's USDT free balance for every active plan.

The scheduled-purchase flow loads the plan and its user-scoped exchange, strategy, and coins; applies `last_average` or `total_average`; validates price freshness and market rules; places each permitted order; persists order facts; updates coin quantity and average; and recomputes plan funds, revenue, ratio, trigger count, and next execution time.

Calculations use immutable `BigDecimal` values with explicit scales and rounding modes. A missing or stale market price blocks that symbol's order and records an actionable error rather than treating the price as zero.

## 10. Error Handling and Observability

A global exception handler maps validation, authentication, authorization, not-found, database, WordPress, Binance authentication, Binance rate-limit, and unexpected failures into the compatible response envelope and Chinese messages.

Actuator exposes application health. Custom indicators report database connectivity, Quartz scheduler state, and Binance market-stream freshness. Structured logs include request correlation ID, user ID when authenticated, plan ID, and safe Binance error codes without credentials.

The existing `/health` response remains `{"status":"ok"}` for compatibility. Operational health is available through Actuator.

## 11. Packaging and Deployment

The repository delivers:

- complete Java source and tests;
- Maven Wrapper and reproducible `pom.xml`;
- Flyway migrations for clean and existing databases;
- `.env.example` without secrets;
- production Dockerfile;
- Docker Compose for the application and MySQL;
- README covering local development, migration, Testnet verification, production enablement, proxy settings, rollback, and operations.

The Spring Boot application runs Flyway validation and approved migrations on startup. A migration failure prevents application startup. The Docker image runs as a non-root user and includes a health check.

## 12. Verification and Acceptance

### 12.1 Automated Tests

- Unit tests cover PBKDF2 and JWT compatibility, masking, allocation, minimum notional, step-size rounding, average-down conditions, valuation, profit, and deterministic order IDs.
- MVC contract tests cover every existing REST path, request field, response field, status, and Chinese error message.
- WebSocket tests cover subscription parsing, compatible price messages, invalid JSON, and missing prices.
- MySQL Testcontainers tests load an existing-schema fixture, run Flyway baseline/migrations, verify JPA mappings and relationships, and exercise transaction rollback.
- Stubbed WordPress tests cover success, invalid credentials, invalid token, unavailable service, and user synchronization.
- Stubbed Binance tests cover credentials, balances, exchange rules, order success, authentication failure, rate limits, ambiguous submission, and reconciliation.
- Market-stream tests cover symbol normalization, cache freshness, disconnect, backoff, resubscription, and scheduled 24-hour rotation.
- Quartz tests cover create, pause, resume, restart recovery, and duplicate-fire protection.

Automated tests never use real API keys or production orders. An opt-in Testnet profile performs credentials, balance, rules, and minimum-size order smoke tests only when Testnet credentials are supplied.

### 12.2 Completion Gates

Delivery is complete only when:

1. `./mvnw verify` passes with no test failures;
2. the Docker image builds successfully;
3. Docker Compose starts the application and MySQL from a clean environment;
4. Flyway successfully upgrades an existing-schema fixture without deleting business data;
5. `/health`, representative REST endpoints, and `/api/ws/price` pass smoke tests;
6. the default configuration proves that production order submission is disabled;
7. a contract checklist shows every Python endpoint and scheduled behavior mapped to the Java implementation;
8. the repository contains no committed secrets or generated build artifacts.

## 13. Replacement Boundary

This repository becomes the Java implementation. The placeholder `main.py` is removed during implementation. The source Python repository is read-only migration input and is not modified. Its uncommitted strategy-response behavior is included, while its hard-coded proxy change is represented by external configuration.
