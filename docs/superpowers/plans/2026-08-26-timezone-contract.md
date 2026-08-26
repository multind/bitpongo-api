# BitPongo Time-Zone Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make strategy execution, UTC persistence, API timestamps, UI display, and Bark notifications follow one explicit and testable time-zone contract.

**Architecture:** A strategy stores an IANA schedule zone next to its cron rule, while all resolved executions and audit events are UTC instants. Quartz resolves each rule in its strategy zone; the API returns RFC-3339 instants; web/mobile clients format those instants in an explicit display zone; Bark distinguishes scheduled and actual execution times.

**Tech Stack:** Java 26, Spring Boot 4.1, Hibernate/JPA, Flyway, MySQL, Quartz, JUnit 5, Vue 3, TypeScript 5.9, Luxon 3.7, Vitest 4, Flutter/Dart.

**Spec:** `docs/superpowers/specs/2026-08-26-timezone-contract-design.md`

## Global Constraints

- Existing strategies retain `Asia/Shanghai` as their effective schedule zone.
- Recurring schedules use IANA region IDs such as `Asia/Shanghai`; abbreviations such as `CST` and fixed offsets are rejected.
- Absolute timestamps are UTC instants and API values contain `Z` or an explicit offset.
- Existing `DATETIME` values are not shifted without production evidence proving their legacy semantics.
- A missed automated buy is skipped; server recovery never creates a catch-up market order.
- Ambiguous submitted orders continue through deterministic `clientOrderId` reconciliation.
- Frontend and backend remain independently deployable during the compatibility rollout.
- Each repository is tested and committed independently; do not mix unrelated existing changes.

## File and Interface Map

### Backend repository: `/Volumes/ExternalDrive/Code/github/bitpongo-api`

- `db/migration/V11__timezone_contract.sql`: additive schedule and display-zone columns.
- `strategy/StrategyEntity.java`, `StrategyDtos.java`, `StrategyApplicationService.java`: schedule-zone ownership and validation.
- `scheduler/PlanScheduleService.java`, `QuartzPlanScheduleService.java`, `ScheduleReconciler.java`: per-strategy Quartz zones and non-replay recovery.
- `common/time/UtcDateTimes.java`: the sole legacy `DATETIME`/`Instant` compatibility boundary.
- `plan/PlanDtos.java`, controller DTOs, and timestamp-bearing entities: RFC-3339 API values.
- `auth/UserTimeZoneService.java` and controller DTOs: display-zone preference and last-device-zone synchronization.
- `notification/NotificationEvent.java`, payload, renderer, and dispatcher: separate scheduled, occurred, and sent instants.

### Frontend repository: `/Volumes/ExternalDrive/Code/github/bitpongo-front`

- `src/utils/timeUtils.ts`: strict instant parsing and zone-aware formatting.
- `src/mobile/app-context.ts`: current IANA device zone source.
- `src/api/index.ts`: time-zone preference and schedule-zone API types.
- `src/views/list/types/strategy.ts`: `schedule_timezone` contract.
- `src/views/list/components/TimeZoneSelect.vue`: reusable IANA zone selector.
- Strategy creation, plan cards/details, order/snapshot, exchange details, notification, and account views: explicit formatting and labels.

### Mobile repository: `/Volumes/ExternalDrive/Code/github/bitpongo-mobile`

- `lib/services/app_context_service.dart`: native IANA zone bridge remains authoritative.
- `test/services/app_context_service_test.dart`: proves identifier and offset are separate fields.

---

### Task 1: Add the additive database contract and UTC compatibility boundary

**Files:**
- Create: `src/main/resources/db/migration/V11__timezone_contract.sql`
- Create: `src/main/java/com/multind/bitpongo/common/time/UtcDateTimes.java`
- Create: `src/test/java/com/multind/bitpongo/common/time/UtcDateTimesTest.java`
- Modify: `src/main/resources/application.yml`
- Create: `src/test/java/com/multind/bitpongo/DatabaseMigrationIntegrationTest.java`

**Interfaces:**
- Produces: `UtcDateTimes.toInstant(LocalDateTime): Instant`
- Produces: `UtcDateTimes.toDatabase(Instant): LocalDateTime`
- Produces columns: `strategy.schedule_timezone`, `user.display_timezone_mode`, `user.display_timezone`, `user.last_device_timezone`

- [ ] **Step 1: Write failing conversion tests**

```java
@Test
void convertsLegacyDatetimeOnlyThroughUtc() {
    LocalDateTime stored = LocalDateTime.of(2026, 8, 25, 13, 0);
    assertThat(UtcDateTimes.toInstant(stored))
            .isEqualTo(Instant.parse("2026-08-25T13:00:00Z"));
    assertThat(UtcDateTimes.toDatabase(Instant.parse("2026-08-25T13:00:00Z")))
            .isEqualTo(stored);
}
```

- [ ] **Step 2: Run the focused test and confirm the class is missing**

Run: `./mvnw -Dtest=UtcDateTimesTest test`

Expected: FAIL because `UtcDateTimes` does not exist.

- [ ] **Step 3: Implement the explicit UTC boundary**

```java
public final class UtcDateTimes {
    private UtcDateTimes() {}

    public static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    public static LocalDateTime toDatabase(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
```

- [ ] **Step 4: Add the non-shifting Flyway migration**

```sql
ALTER TABLE strategy
    ADD COLUMN schedule_timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai' AFTER cron;

ALTER TABLE `user`
    ADD COLUMN display_timezone_mode VARCHAR(16) NOT NULL DEFAULT 'FOLLOW_DEVICE',
    ADD COLUMN display_timezone VARCHAR(64) NULL,
    ADD COLUMN last_device_timezone VARCHAR(64) NULL;
```

Do not update any existing timestamp column in this migration.

- [ ] **Step 5: Pin Hibernate JDBC timestamp handling to UTC**

Add under `spring.jpa.properties` in `application.yml`:

```yaml
hibernate.jdbc.time_zone: UTC
```

- [ ] **Step 6: Extend the migration integration test**

Assert the four new columns exist, all legacy strategies contain
`Asia/Shanghai`, and a fixture `DATETIME '2026-08-25 13:00:00'` is unchanged.

- [ ] **Step 7: Run migration and conversion tests**

Run: `./mvnw -Dtest=UtcDateTimesTest,DatabaseMigrationIntegrationTest test`

Expected: PASS, with Flyway applying V11 once.

- [ ] **Step 8: Commit the backend schema boundary**

```bash
git add src/main/resources/db/migration/V11__timezone_contract.sql \
  src/main/resources/application.yml \
  src/main/java/com/multind/bitpongo/common/time/UtcDateTimes.java \
  src/test/java/com/multind/bitpongo/common/time/UtcDateTimesTest.java \
  src/test/java/com/multind/bitpongo/DatabaseMigrationIntegrationTest.java
git commit -m "feat: add timezone persistence contract"
```

### Task 2: Make schedule zone part of the strategy API and domain

**Files:**
- Modify: `src/main/java/com/multind/bitpongo/strategy/StrategyEntity.java`
- Modify: `src/main/java/com/multind/bitpongo/strategy/StrategyDtos.java`
- Modify: `src/main/java/com/multind/bitpongo/strategy/StrategyApplicationService.java`
- Modify: `src/test/java/com/multind/bitpongo/strategy/StrategyApplicationServiceTest.java`
- Modify: `src/test/java/com/multind/bitpongo/strategy/StrategyControllerContractTest.java`

**Interfaces:**
- Consumes: `strategy.schedule_timezone` from Task 1
- Produces: `StrategyCreateRequest.scheduleTimezone(): String`
- Produces: `StrategyEntity.getScheduleTimezone(): String`
- Produces: `StrategyApplicationService.scheduleZone(String): ZoneId`

- [ ] **Step 1: Add failing request and validation tests**

Extend the create request fixture with:

```json
"schedule_timezone":"America/New_York"
```

Assert the response returns the same value, the entity stores it, a missing
field resolves to `Asia/Shanghai`, and these inputs return HTTP 400: `CST`,
`+08:00`, and `Not/AZone`.

- [ ] **Step 2: Run the strategy tests and confirm the field is absent**

Run: `./mvnw -Dtest=StrategyApplicationServiceTest,StrategyControllerContractTest test`

Expected: FAIL on missing `schedule_timezone` mapping and validation.

- [ ] **Step 3: Add the request and entity fields**

```java
public record StrategyCreateRequest(
        @NotBlank String name,
        @NotNull @Positive BigDecimal instalment,
        @JsonProperty("exchange_id") long exchangeId,
        String frequency,
        @NotBlank String cron,
        @JsonProperty("schedule_timezone") String scheduleTimezone,
        String condition,
        @NotEmpty List<@Valid CoinRequest> coins) {}
```

Map `schedule_timezone` in `StrategyEntity` with length 64.

- [ ] **Step 4: Validate a region-based IANA zone**

```java
public static ZoneId scheduleZone(String value) {
    String effective = value == null || value.isBlank() ? "Asia/Shanghai" : value;
    ZoneId zone;
    try {
        zone = ZoneId.of(effective);
    } catch (DateTimeException invalid) {
        throw new BusinessException(400, "策略时区无效");
    }
    if (zone instanceof ZoneOffset
            || (!("UTC".equals(effective) || effective.contains("/")))) {
        throw new BusinessException(400, "策略时区必须使用地区名称");
    }
    return zone;
}
```

Resolve a missing field to `Asia/Shanghai` during the compatibility window so
the backend may be deployed before the updated frontend. Use the resolved zone
when calculating the initial `nextTime`; do not use the process default or
server local zone for a user strategy.

- [ ] **Step 5: Run the focused strategy tests**

Run: `./mvnw -Dtest=StrategyApplicationServiceTest,StrategyControllerContractTest test`

Expected: PASS for Shanghai and New York; invalid zones return code 400.

- [ ] **Step 6: Commit the strategy contract**

```bash
git add src/main/java/com/multind/bitpongo/strategy \
  src/test/java/com/multind/bitpongo/strategy
git commit -m "feat: bind strategies to schedule zones"
```

### Task 3: Schedule Quartz triggers per strategy zone and disable trade replay

**Files:**
- Modify: `src/main/java/com/multind/bitpongo/scheduler/PlanScheduleService.java`
- Modify: `src/main/java/com/multind/bitpongo/scheduler/QuartzPlanScheduleService.java`
- Modify: `src/main/java/com/multind/bitpongo/scheduler/ScheduleReconciler.java`
- Modify: `src/main/java/com/multind/bitpongo/scheduler/PlanPurchaseJob.java`
- Create: `src/main/java/com/multind/bitpongo/scheduler/PlanExecutionMetrics.java`
- Modify: `src/main/java/com/multind/bitpongo/plan/PlanApplicationService.java`
- Modify: `src/test/java/com/multind/bitpongo/scheduler/QuartzPlanScheduleServiceTest.java`
- Modify: `src/test/java/com/multind/bitpongo/scheduler/ScheduleReconcilerIntegrationTest.java`
- Modify: `src/test/java/com/multind/bitpongo/scheduler/PlanPurchaseJobTest.java`
- Create: `src/test/java/com/multind/bitpongo/scheduler/PlanExecutionMetricsTest.java`

**Interfaces:**
- Consumes: `StrategyEntity.getScheduleTimezone()` from Task 2
- Produces: `schedule(long planId, String cron, ZoneId zone)`
- Produces: `resume(long planId, String cron, ZoneId zone)`
- Preserves: `MISFIRE_INSTRUCTION_DO_NOTHING`

- [ ] **Step 1: Change tests to require an explicit zone**

```java
service.schedule(42L, "0 0 21 * * ?", ZoneId.of("America/New_York"));
CronTrigger trigger = (CronTrigger) scheduler.getTrigger(
        TriggerKey.triggerKey("trigger_plan_42", "plans"));
assertThat(trigger.getTimeZone().getID()).isEqualTo("America/New_York");
assertThat(trigger.getMisfireInstruction())
        .isEqualTo(CronTrigger.MISFIRE_INSTRUCTION_DO_NOTHING);
assertThat(scheduler.getJobDetail(JobKey.jobKey("job_plan_42", "plans"))
        .requestsRecovery()).isFalse();
```

Add fixtures around the 2026 New York DST transitions. The spring gap must not
produce a catch-up execution, and the fall overlap must produce at most one
purchase for each resolved UTC `scheduledAt` idempotency key.

- [ ] **Step 2: Run scheduler tests and verify the old interface fails**

Run: `./mvnw -Dtest=QuartzPlanScheduleServiceTest,ScheduleReconcilerIntegrationTest,PlanPurchaseJobTest test`

Expected: FAIL because the schedule method has no `ZoneId` and the job requests recovery.

- [ ] **Step 3: Change the scheduling interface and Quartz builder**

```java
void schedule(long planId, String cron, ZoneId zone);
void resume(long planId, String cron, ZoneId zone);
```

Build each trigger with `TimeZone.getTimeZone(zone)`. Remove
`.requestRecovery(true)` from trade job creation. Keep the asset snapshot on its
configured administrative zone.

- [ ] **Step 4: Reconcile cron and zone together**

For every active plan, load its strategy zone and call:

```java
scheduleService.schedule(
        plan.getId(),
        StrategyApplicationService.normalizeCron(strategy.getCron()),
        StrategyApplicationService.scheduleZone(strategy.getScheduleTimezone()));
```

Resume uses the same three values. Add structured logs for `scheduledAt`,
`actualStartedAt`, `delayMs`, `recovering`, and `nextFireAt`.

Add Micrometer counters named `bitpongo.plan.execution` with a bounded `result`
tag whose allowed values are `on_time`, `delayed`, `misfire_skipped`, and
`recovery_skipped`. Do not tag metrics with user or plan IDs.

- [ ] **Step 5: Guard against a recovery invocation**

In `PlanPurchaseJob`, if `context.isRecovering()` is true, publish a skipped
operational event and return without calling `purchases.execute`. This guard is
defense in depth for existing persisted job details.

- [ ] **Step 6: Run all scheduler tests**

Run: `./mvnw -Dtest='com.multind.bitpongo.scheduler.*Test' test`

Expected: PASS; recovery never calls the purchase use case and New York triggers
retain their zone after reconciliation.

- [ ] **Step 7: Commit the Quartz behavior**

```bash
git add src/main/java/com/multind/bitpongo/scheduler \
  src/main/java/com/multind/bitpongo/plan/PlanApplicationService.java \
  src/test/java/com/multind/bitpongo/scheduler
git commit -m "fix: schedule plans in their own timezones"
```

### Task 4: Return RFC-3339 instants from backend APIs

**Files:**
- Modify: `src/main/java/com/multind/bitpongo/auth/UserDtos.java`
- Modify: `src/main/java/com/multind/bitpongo/auth/UserEntity.java`
- Modify: `src/main/java/com/multind/bitpongo/auth/UserApplicationService.java`
- Modify: `src/main/java/com/multind/bitpongo/auth/AccountDeletionService.java`
- Modify: `src/main/java/com/multind/bitpongo/exchange/ExchangeDtos.java`
- Modify: `src/main/java/com/multind/bitpongo/exchange/ExchangeEntity.java`
- Modify: `src/main/java/com/multind/bitpongo/exchange/ExchangeApplicationService.java`
- Modify: `src/main/java/com/multind/bitpongo/strategy/StrategyEntity.java`
- Modify: `src/main/java/com/multind/bitpongo/strategy/CoinEntity.java`
- Modify: `src/main/java/com/multind/bitpongo/strategy/StrategyApplicationService.java`
- Modify: `src/main/java/com/multind/bitpongo/plan/PlanDtos.java`
- Modify: `src/main/java/com/multind/bitpongo/plan/PlanEntity.java`
- Modify: `src/main/java/com/multind/bitpongo/plan/OrderEntity.java`
- Modify: `src/main/java/com/multind/bitpongo/plan/SnapshotEntity.java`
- Modify: `src/main/java/com/multind/bitpongo/plan/PlanApplicationService.java`
- Modify: `src/main/java/com/multind/bitpongo/plan/AssetSnapshotService.java`
- Modify: `src/main/java/com/multind/bitpongo/scheduler/OrderIntentEntity.java`
- Modify: `src/main/java/com/multind/bitpongo/scheduler/OrderIntentRepository.java`
- Modify: `src/main/java/com/multind/bitpongo/scheduler/OrderPersistenceService.java`
- Modify: `src/main/java/com/multind/bitpongo/scheduler/OrderReconciliationService.java`
- Modify: `src/main/java/com/multind/bitpongo/scheduler/ScheduledPurchaseService.java`
- Modify: `src/test/java/com/multind/bitpongo/plan/PlanControllerContractTest.java`
- Modify: `src/test/java/com/multind/bitpongo/exchange/ExchangeControllerContractTest.java`
- Modify: `src/test/java/com/multind/bitpongo/strategy/StrategyControllerContractTest.java`
- Modify: `src/test/java/com/multind/bitpongo/scheduler/OrderPersistenceServiceTest.java`

**Interfaces:**
- Consumes: `UtcDateTimes` from Task 1
- Produces: `PlanView.nextExecutionAt(): Instant`
- Produces: RFC-3339 JSON strings ending in `Z`
- Compatibility alias: `next_time` returns the same instant as `next_execution_at`

- [ ] **Step 1: Add contract assertions for explicit UTC values**

```java
.andExpect(jsonPath("$.data.next_execution_at")
        .value("2026-08-25T13:00:00Z"))
.andExpect(jsonPath("$.data.next_time")
        .value("2026-08-25T13:00:00Z"))
.andExpect(jsonPath("$.data.created_at")
        .value("2026-08-24T08:15:30Z"));
```

Add equivalent assertions for exchange creation, strategy creation, order rows,
and snapshots.

- [ ] **Step 2: Run controller and persistence tests**

Run: `./mvnw -Dtest=PlanControllerContractTest,ExchangeControllerContractTest,StrategyControllerContractTest,OrderPersistenceServiceTest test`

Expected: FAIL because current JSON contains zone-less `LocalDateTime` strings.

- [ ] **Step 3: Convert application boundaries to `Instant`**

Use `Instant` for new DTO fields and event/audit method signatures. Where legacy
JPA `DATETIME` fields must remain `LocalDateTime`, convert only with
`UtcDateTimes.toInstant` and `UtcDateTimes.toDatabase`; do not call
`ZoneId.systemDefault()` or use a scheduling zone for audit timestamps.

Define the plan response fields as:

```java
public record PlanView(
        Long id,
        BigDecimal totalFunds,
        BigDecimal totalRevenue,
        BigDecimal totalRatio,
        BigDecimal totalValue,
        @JsonProperty("next_execution_at") Instant nextExecutionAt,
        @JsonProperty("next_time") Instant nextTime,
        String status,
        Long userId,
        Integer triggeredCount,
        Instant createdAt,
        StrategyEntity strategy,
        List<CoinEntity> coins,
        List<OrderEntity> orders,
        List<SnapshotEntity> snapshots) {}
```

- [ ] **Step 4: Add an API timestamp shape regression test**

Recursively inspect serialized plan, exchange, strategy, order, and snapshot
fixtures. Every non-null JSON property ending in `_at` or `_time` must match:

```regex
^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$
```

- [ ] **Step 5: Run backend API contract tests**

Run: `./mvnw -Dtest='*ControllerContractTest,OrderPersistenceServiceTest' test`

Expected: PASS with no zone-less absolute timestamp.

- [ ] **Step 6: Commit the UTC API contract**

```bash
git add src/main/java src/test/java
git commit -m "refactor: expose absolute timestamps as UTC instants"
```

### Task 5: Add a user display-zone preference API

**Files:**
- Create: `src/main/java/com/multind/bitpongo/auth/UserTimeZoneService.java`
- Modify: `src/main/java/com/multind/bitpongo/auth/UserEntity.java`
- Modify: `src/main/java/com/multind/bitpongo/auth/UserDtos.java`
- Modify: `src/main/java/com/multind/bitpongo/auth/UserController.java`
- Create: `src/test/java/com/multind/bitpongo/auth/UserTimeZoneServiceTest.java`
- Modify: `src/test/java/com/multind/bitpongo/auth/UserControllerContractTest.java`

**Interfaces:**
- Produces: `GET /api/users/timezone`
- Produces: `PUT /api/users/timezone` with `{mode, timezone}`
- Produces: `POST /api/users/timezone/device` with `{timezone}`
- Produces: `UserTimeZoneService.resolveDisplayZone(long userId): ZoneId`

- [ ] **Step 1: Write failing service and controller tests**

Test these cases:

```text
FIXED + Asia/Tokyo       -> Asia/Tokyo
FOLLOW_DEVICE + latest  -> America/New_York
FOLLOW_DEVICE + missing -> UTC
fixed offset +08:00     -> HTTP 400
invalid Not/AZone       -> HTTP 400
```

- [ ] **Step 2: Run the user time-zone tests**

Run: `./mvnw -Dtest=UserTimeZoneServiceTest,UserControllerContractTest test`

Expected: FAIL because the endpoints and service do not exist.

- [ ] **Step 3: Add DTOs and validation**

```java
public enum DisplayTimeZoneMode { FOLLOW_DEVICE, FIXED }

public record TimeZonePreference(
        @JsonProperty("mode") DisplayTimeZoneMode mode,
        @JsonProperty("timezone") String timezone,
        @JsonProperty("effective_timezone") String effectiveTimezone) {}
```

Reuse the region-zone validator from Task 2. `FIXED` requires `timezone`;
`FOLLOW_DEVICE` stores a nullable fixed value and resolves the latest device
zone.

- [ ] **Step 4: Add authenticated endpoints**

All three endpoints derive user ID from `AuthenticatedUser`; ignore any user ID
in request JSON. Device synchronization changes only `last_device_timezone` and
never modifies a strategy's `schedule_timezone`.

- [ ] **Step 5: Run the focused auth tests**

Run: `./mvnw -Dtest=UserTimeZoneServiceTest,UserControllerContractTest test`

Expected: PASS and cross-user mutation is impossible.

- [ ] **Step 6: Commit display-zone preferences**

```bash
git add src/main/java/com/multind/bitpongo/auth \
  src/test/java/com/multind/bitpongo/auth
git commit -m "feat: add user display timezone preferences"
```

### Task 6: Separate scheduled, occurred, and delivery times in Bark events

**Files:**
- Modify: `src/main/java/com/multind/bitpongo/notification/NotificationEvent.java`
- Modify: `src/main/java/com/multind/bitpongo/notification/NotificationEventType.java`
- Modify: `src/main/java/com/multind/bitpongo/notification/NotificationPayloadSanitizer.java`
- Modify: `src/main/java/com/multind/bitpongo/notification/NotificationOutboxDeliveryStore.java`
- Modify: `src/main/java/com/multind/bitpongo/notification/NotificationMessageRenderer.java`
- Modify: `src/main/java/com/multind/bitpongo/notification/UserBarkSettingService.java`
- Modify: `src/main/java/com/multind/bitpongo/market/BinanceMarketStreamLifecycle.java`
- Modify: `src/main/java/com/multind/bitpongo/plan/AssetSnapshotService.java`
- Modify: `src/main/java/com/multind/bitpongo/scheduler/OrderReconciliationService.java`
- Modify: `src/main/java/com/multind/bitpongo/scheduler/PlanPurchaseJob.java`
- Modify: `src/main/java/com/multind/bitpongo/scheduler/ScheduleReconciler.java`
- Modify: `src/main/java/com/multind/bitpongo/scheduler/ScheduledPurchaseService.java`
- Modify: `src/test/java/com/multind/bitpongo/notification/NotificationAudienceAndPayloadIntegrationTest.java`
- Modify: `src/test/java/com/multind/bitpongo/notification/NotificationAudienceNoAdminIntegrationTest.java`
- Modify: `src/test/java/com/multind/bitpongo/notification/NotificationAudienceResolverContextTest.java`
- Modify: `src/test/java/com/multind/bitpongo/notification/NotificationDedupeWindowTest.java`
- Modify: `src/test/java/com/multind/bitpongo/notification/NotificationMarketOutageAudienceIntegrationTest.java`
- Modify: `src/test/java/com/multind/bitpongo/notification/NotificationMessageRendererTest.java`
- Modify: `src/test/java/com/multind/bitpongo/notification/NotificationOutboxDispatcherTest.java`
- Modify: `src/test/java/com/multind/bitpongo/notification/NotificationRecoveredAudienceIntegrationTest.java`
- Modify: `src/test/java/com/multind/bitpongo/notification/OutboxNotificationPublisherTest.java`
- Modify: `src/test/java/com/multind/bitpongo/scheduler/ScheduledPurchaseServiceTest.java`

**Interfaces:**
- Consumes: `UserTimeZoneService.resolveDisplayZone` from Task 5
- Produces: `NotificationEvent.scheduledAt(): Instant`
- Produces: `NotificationEvent.occurredAt(): Instant`
- Produces payload keys: `scheduledAt`, `occurredAt`
- Produces event: `PLAN_EXECUTION_DELAYED`

- [ ] **Step 1: Add a failing renderer test for two distinct instants**

```java
NotificationEvent event = tradeSucceeded(
        Instant.parse("2026-08-25T13:00:00Z"),
        Instant.parse("2026-08-25T21:00:01Z"));

assertThat(renderer.render(event, "zh-CN", "Asia/Shanghai", null).body())
        .contains("计划时间：2026-08-25 21:00:00 Asia/Shanghai")
        .contains("成交时间：2026-08-26 05:00:01 Asia/Shanghai")
        .contains("延迟：28801秒");
```

- [ ] **Step 2: Run notification and purchase tests**

Run: `./mvnw -Dtest=NotificationMessageRendererTest,NotificationOutboxDispatcherTest,ScheduledPurchaseServiceTest test`

Expected: FAIL because the event has only one time and the renderer labels it
generically.

- [ ] **Step 3: Extend the event and JSON payload**

Add nullable `scheduledAt` before the existing actual `occurredAt`. Keep
deserialization backward compatible: an old payload with only `occurredAt`
continues to render as an event time without inventing a schedule.

- [ ] **Step 4: Publish actual trade completion time**

At the end of a successful purchase cycle, pass:

```java
scheduledAt = scheduledFireTime;
occurredAt = clock.instant();
```

If `Duration.between(scheduledAt, occurredAt)` exceeds two minutes, enqueue
`PLAN_EXECUTION_DELAYED` with the same plan/user and delay seconds. Do not send a
second market order.

- [ ] **Step 5: Render explicit localized labels**

Add `scheduledTime`, `executedTime`, and `delay` labels to all three supported
languages. The outbox `sentAt` remains audit metadata and is not represented as
the trade time.

- [ ] **Step 6: Run the notification suite**

Run: `./mvnw -Dtest='com.multind.bitpongo.notification.*Test,ScheduledPurchaseServiceTest' test`

Expected: PASS for current payloads, backward-compatible old payloads, and the
eight-hour-delay fixture.

- [ ] **Step 7: Commit notification semantics**

```bash
git add src/main/java/com/multind/bitpongo/notification \
  src/main/java/com/multind/bitpongo/scheduler/ScheduledPurchaseService.java \
  src/test/java/com/multind/bitpongo/notification \
  src/test/java/com/multind/bitpongo/scheduler/ScheduledPurchaseServiceTest.java
git commit -m "fix: distinguish scheduled and actual trade times"
```

### Task 7: Build strict zone-aware frontend time utilities

**Files:**
- Modify: `/Volumes/ExternalDrive/Code/github/bitpongo-front/src/utils/timeUtils.ts`
- Create: `/Volumes/ExternalDrive/Code/github/bitpongo-front/src/utils/timeUtils.spec.ts`
- Modify: `/Volumes/ExternalDrive/Code/github/bitpongo-front/src/api/index.ts`
- Modify: `/Volumes/ExternalDrive/Code/github/bitpongo-front/src/mobile/app-context.ts`

**Interfaces:**
- Consumes: RFC-3339 timestamps and time-zone endpoints from Tasks 4 and 5
- Produces: `parseInstant(value: string): DateTime`
- Produces: `formatInstant(value: string, zone?: string): string`
- Produces: `formatScheduleInstant(value: string, scheduleZone: string, displayZone: string): { primary: string; secondary?: string }`

- [ ] **Step 1: Write failing Vitest cases**

```ts
expect(formatInstant('2026-08-25T13:00:00Z', 'Asia/Shanghai'))
  .toBe('2026-08-25 21:00');
expect(() => parseInstant('2026-08-25T13:00:00')).toThrowError(
  'Absolute timestamp requires Z or an explicit offset',
);
expect(formatScheduleInstant(
  '2026-08-25T13:00:00Z',
  'Asia/Shanghai',
  'America/New_York',
)).toEqual({
  primary: '2026-08-25 21:00 Asia/Shanghai',
  secondary: '2026-08-25 09:00 America/New_York',
});
```

- [ ] **Step 2: Run the time utility test**

Run from `bitpongo-front`: `pnpm test -- src/utils/timeUtils.spec.ts`

Expected: FAIL because strict parsing and explicit zones are not implemented.

- [ ] **Step 3: Implement with Luxon**

```ts
export function parseInstant(value: string): DateTime {
  if (!/(Z|[+-]\d{2}:\d{2})$/.test(value)) {
    throw new Error('Absolute timestamp requires Z or an explicit offset');
  }
  const parsed = DateTime.fromISO(value, { setZone: true });
  if (!parsed.isValid) throw new Error(`Invalid timestamp: ${value}`);
  return parsed;
}

export function formatInstant(value: string, zone = displayTimeZone()): string {
  return parseInstant(value).setZone(zone).toFormat('yyyy-LL-dd HH:mm');
}
```

`displayTimeZone()` returns the fixed user preference when present, otherwise
the current app-context zone, otherwise `UTC`.

- [ ] **Step 4: Add typed API functions**

```ts
export type DisplayTimeZoneMode = 'FOLLOW_DEVICE' | 'FIXED';
export interface TimeZonePreference {
  mode: DisplayTimeZoneMode;
  timezone: string | null;
  effective_timezone: string;
}

export const getTimeZonePreference = () => http.get<TimeZonePreference>('/users/timezone');
export const saveTimeZonePreference = (data: Pick<TimeZonePreference, 'mode' | 'timezone'>) =>
  http.put<TimeZonePreference>('/users/timezone', data);
export const syncDeviceTimeZone = (timezone: string) =>
  http.post<void>('/users/timezone/device', { timezone });
```

- [ ] **Step 5: Run frontend tests and type checking**

Run: `pnpm test -- src/utils/timeUtils.spec.ts src/mobile/app-context.spec.ts`

Run: `pnpm typecheck`

Expected: both commands PASS.

- [ ] **Step 6: Commit frontend time primitives**

```bash
git add src/utils/timeUtils.ts src/utils/timeUtils.spec.ts src/api/index.ts src/mobile/app-context.ts
git commit -m "feat: add zone-aware frontend time utilities"
```

### Task 8: Add schedule-zone selection and correct cron previews

**Files:**
- Create: `/Volumes/ExternalDrive/Code/github/bitpongo-front/src/views/list/components/TimeZoneSelect.vue`
- Create: `/Volumes/ExternalDrive/Code/github/bitpongo-front/src/views/list/components/TimeZoneSelect.spec.ts`
- Create: `/Volumes/ExternalDrive/Code/github/bitpongo-front/src/views/list/components/StrategyCreation.spec.ts`
- Modify: `/Volumes/ExternalDrive/Code/github/bitpongo-front/src/views/list/types/strategy.ts`
- Modify: `/Volumes/ExternalDrive/Code/github/bitpongo-front/src/views/list/components/FrequencySetting.vue`
- Modify: `/Volumes/ExternalDrive/Code/github/bitpongo-front/src/views/list/components/StrategyCreation.vue`
- Modify: `/Volumes/ExternalDrive/Code/github/bitpongo-front/src/views/list/strategy/index.vue`
- Modify: `/Volumes/ExternalDrive/Code/github/bitpongo-front/src/i18n/lang/lang-base.ts`
- Modify: `/Volumes/ExternalDrive/Code/github/bitpongo-front/src/i18n/lang/zh-cn.ts`
- Modify: `/Volumes/ExternalDrive/Code/github/bitpongo-front/src/i18n/lang/zh-tw.ts`
- Modify: `/Volumes/ExternalDrive/Code/github/bitpongo-front/src/i18n/lang/en-us.ts`
- Modify: `/Volumes/ExternalDrive/Code/github/bitpongo-front/src/i18n/consistency.spec.ts`

**Interfaces:**
- Consumes: current app-context zone and backend `schedule_timezone`
- Produces: `Strategy.schedule_timezone: string`
- Produces: `<TimeZoneSelect v-model="strategy.schedule_timezone" />`

- [ ] **Step 1: Add failing component tests**

Assert that the create flow initializes `schedule_timezone` from
`getAppContext()?.timeZone`, lets the user select `America/New_York`, sends that
exact value, and displays `21:00 America/New_York` without converting the wall
clock to the device zone.

- [ ] **Step 2: Run the component tests**

Run: `pnpm test -- src/views/list/components/TimeZoneSelect.spec.ts src/views/list/components/StrategyCreation.spec.ts`

Expected: FAIL because the selector and strategy field do not exist.

- [ ] **Step 3: Implement the IANA selector**

Use `Intl.supportedValuesOf('timeZone')` when available. The deterministic
fallback is:

```ts
['UTC', 'Asia/Shanghai', 'Asia/Taipei', 'Asia/Tokyo',
 'Europe/London', 'America/New_York', 'America/Los_Angeles']
```

Filter out fixed offsets and abbreviations. Search matches both the raw IANA ID
and the localized display label.

- [ ] **Step 4: Parse cron in its schedule zone**

```ts
CronExpressionParser.parse(strategy.cron, {
  currentDate: new Date(),
  tz: strategy.schedule_timezone,
});
```

Render the recurrence description in `schedule_timezone`; use
`formatScheduleInstant` only for resolved next-execution instants.

- [ ] **Step 5: Add localized labels**

Add concise Simplified Chinese, Traditional Chinese, and English strings for
“Strategy time zone”, “Follows this time zone even when you travel”, and the
secondary local-time label.

- [ ] **Step 6: Run strategy tests, type checking, and build**

Run: `pnpm test -- src/views/list/components/TimeZoneSelect.spec.ts src/views/list/components/StrategyCreation.spec.ts`

Run: `pnpm typecheck && pnpm build`

Expected: all commands PASS.

- [ ] **Step 7: Commit schedule-zone UI**

```bash
git add src/views/list src/i18n
git commit -m "feat: select strategy execution timezones"
```

### Task 9: Apply the display zone across all UI timestamps and account settings

**Files:**
- Modify: `/Volumes/ExternalDrive/Code/github/bitpongo-front/src/views/list/components/PlanCard.vue`
- Modify: `/Volumes/ExternalDrive/Code/github/bitpongo-front/src/views/list/details/index.vue`
- Modify: `/Volumes/ExternalDrive/Code/github/bitpongo-front/src/views/member/exchange/index.vue`
- Modify: `/Volumes/ExternalDrive/Code/github/bitpongo-front/src/views/member/exchange/details.vue`
- Modify: `/Volumes/ExternalDrive/Code/github/bitpongo-front/src/views/member/account/index.vue`
- Modify: `/Volumes/ExternalDrive/Code/github/bitpongo-front/src/views/member/account/index.spec.ts`
- Modify: `/Volumes/ExternalDrive/Code/github/bitpongo-front/src/views/member/notice/index.vue`
- Modify: `/Volumes/ExternalDrive/Code/github/bitpongo-front/src/views/member/notice/index.spec.ts`
- Create: `/Volumes/ExternalDrive/Code/github/bitpongo-front/src/views/list/time-display.spec.ts`

**Interfaces:**
- Consumes: `formatInstant`, `formatScheduleInstant`, and preference API from Task 7
- Produces: account setting for `FOLLOW_DEVICE` or `FIXED`
- Produces: one device-zone synchronization after authenticated app startup

- [ ] **Step 1: Add failing cross-zone view tests**

Mount plan card/detail and exchange detail with
`2026-08-25T13:00:00Z`. Assert Shanghai displays `2026-08-25 21:00`, New York
displays `2026-08-25 09:00`, and a plan scheduled in Shanghai shows Shanghai as
the primary schedule zone in both cases.

- [ ] **Step 2: Run view tests**

Run: `pnpm test -- src/views/list/time-display.spec.ts src/views/member/account/index.spec.ts src/views/member/notice/index.spec.ts`

Expected: FAIL because views call `new Date` or `toLocaleString` directly.

- [ ] **Step 3: Replace direct date parsing**

Remove direct `new Date(apiTimestamp)`, manual `getHours`, and
`toLocaleString()` calls for server timestamps. Route all absolute timestamp
display through `formatInstant`; route next execution through
`formatScheduleInstant`.

- [ ] **Step 4: Implement account display-zone controls**

The account view presents:

```text
Display time zone
(*) Follow device
( ) Fixed: [Asia/Shanghai]
```

Saving calls `saveTimeZonePreference`. When authenticated startup finishes and
mode is `FOLLOW_DEVICE`, call `syncDeviceTimeZone` with the app-context IANA ID.
Do not change strategy schedule zones.

- [ ] **Step 5: Keep Bark settings consistent**

Display the effective account zone on the notification page. Stop treating a
separate Bark timezone as the primary user preference; send the effective zone
when maintaining compatibility with the existing Bark endpoint.

- [ ] **Step 6: Run the complete frontend verification**

Run: `pnpm test`

Run: `pnpm typecheck`

Run: `pnpm build`

Expected: all commands PASS with no direct zone-less parsing in affected views.

- [ ] **Step 7: Commit the display-zone UI**

```bash
git add src
git commit -m "fix: render application times in explicit zones"
```

### Task 10: Verify the native zone bridge remains an IANA identifier

**Files:**
- Modify only if the test exposes a defect: `/Volumes/ExternalDrive/Code/github/bitpongo-mobile/lib/services/app_context_service.dart`
- Modify: `/Volumes/ExternalDrive/Code/github/bitpongo-mobile/test/services/app_context_service_test.dart`

**Interfaces:**
- Produces bridge fields: `timeZone: String` and `timeZoneOffsetMinutes: int`
- Constraint: the offset never replaces the IANA identifier

- [ ] **Step 1: Add a native bridge regression test**

```dart
expect(context.timeZone, 'America/New_York');
expect(context.timeZoneOffsetMinutes, -240);
expect(context.build(mediaQuery)['timeZone'], 'America/New_York');
expect(context.build(mediaQuery)['timeZoneOffsetMinutes'], -240);
```

Inject independent loaders so the test proves the fields are not derived from
each other.

- [ ] **Step 2: Run the focused Flutter test**

Run: `flutter test test/services/app_context_service_test.dart`

Expected: PASS on current code; if it fails, change only the bridge mapping
needed to preserve the identifier.

- [ ] **Step 3: Run all Flutter tests**

Run: `flutter test`

Expected: PASS.

- [ ] **Step 4: Commit only if source or test changed**

```bash
git add lib/services/app_context_service.dart test/services/app_context_service_test.dart
git commit -m "test: preserve native IANA timezone context"
```

### Task 11: Perform cross-repository regression and deployment checks

**Files:**
- Create: `scripts/audit-timezone-data.sql`
- Modify: `README.md`
- Modify: `docs/python-java-contract-matrix.md`
- Verify: all files changed by Tasks 1-10

**Interfaces:**
- Consumes: final backend, frontend, and mobile contracts
- Produces: production preflight SQL and documented deployment order

- [ ] **Step 1: Add a read-only production audit script**

The script reports, without updating data:

```sql
SELECT id, cron, schedule_timezone FROM strategy ORDER BY id;
SELECT id, status, next_time FROM plan WHERE status = 'active' ORDER BY id;
SELECT TRIGGER_NAME, CRON_EXPRESSION, TIME_ZONE
FROM QRTZ_CRON_TRIGGERS ORDER BY TRIGGER_NAME;
SELECT id, event_type,
       JSON_UNQUOTE(JSON_EXTRACT(body_payload, '$.scheduledAt')) AS scheduled_at,
       JSON_UNQUOTE(JSON_EXTRACT(body_payload, '$.occurredAt')) AS occurred_at,
       created_at, sent_at
FROM notification_outbox ORDER BY id DESC LIMIT 50;
```

- [ ] **Step 2: Document deployment order**

Document this exact sequence:

1. Back up MySQL and run the read-only audit.
2. Deploy backend schema/API compatibility release.
3. Verify active Quartz trigger cron and zone against each strategy.
4. Deploy web frontend.
5. Bundle the verified frontend into mobile and release the app normally.
6. Observe delay and misfire metrics through at least one scheduled execution.
7. Remove legacy API aliases only in a later release.

- [ ] **Step 3: Run backend verification**

Run from `bitpongo-api`: `./mvnw test`

Run: `./mvnw -DskipTests package`

Run: `git diff --check`

Expected: all commands exit 0.

- [ ] **Step 4: Run frontend verification**

Run from `bitpongo-front`: `pnpm test && pnpm typecheck && pnpm build`

Run: `git diff --check`

Expected: all commands exit 0.

- [ ] **Step 5: Run mobile verification**

Run from `bitpongo-mobile`: `flutter test`

Run: `flutter analyze`

Run: `git diff --check`

Expected: all commands exit 0.

- [ ] **Step 6: Review the original production symptom**

For a test strategy at `21:00 Asia/Shanghai`, verify:

```text
Quartz scheduled_at: 2026-08-25T13:00:00Z
UI primary:          2026-08-25 21:00 Asia/Shanghai
Order occurred_at:   within the configured delay threshold
Bark scheduled:      2026-08-25 21:00:00 Asia/Shanghai
Bark executed:       the actual converted execution instant
Recovery replay:     no market order after a missed window
```

- [ ] **Step 7: Commit backend audit documentation**

```bash
git add scripts/audit-timezone-data.sql README.md docs/python-java-contract-matrix.md
git commit -m "docs: add timezone deployment preflight"
```

- [ ] **Step 8: Record repository delivery separately**

Report backend, frontend, and mobile commit SHAs; test results; build results;
and remote push results as separate facts. Do not describe a local commit as
successfully pushed until the remote SHA is observed.
