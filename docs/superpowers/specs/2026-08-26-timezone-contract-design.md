# BitPongo Time-Zone Contract Design

**Date:** 2026-08-26
**Status:** Proposed

## 1. Objective

Establish one unambiguous time contract for strategy scheduling, persistence, APIs,
the web/mobile UI, Quartz, and Bark notifications. The design must prevent a local
wall-clock value such as `21:00` from being silently interpreted as UTC, server
local time, or the device's current time zone.

## 2. Core Model

The system separates three concepts:

1. **Schedule zone**: the IANA zone in which a recurring strategy rule is
   interpreted, for example `Asia/Shanghai`.
2. **Instant**: an absolute point on the UTC timeline, for example
   `2026-08-25T13:00:00Z`.
3. **Display zone**: the zone in which a user chooses to view timestamps. It may
   follow the current device or be fixed in account settings.

These concepts must never share an ambiguously named or zone-less field.

## 3. Product Rules

### 3.1 Strategy execution

- Every strategy owns a `schedule_timezone` IANA zone ID.
- Creating a strategy defaults this value to the device/browser IANA zone.
- The create screen shows the zone and lets the user change it before saving.
- A saved strategy remains anchored to its schedule zone when the user travels
  or changes the device zone.
- Changing a strategy's schedule zone is an explicit schedule edit and causes
  its Quartz trigger and next execution time to be recalculated.
- Existing strategies are migrated to `Asia/Shanghai` to preserve current
  behavior.

### 3.2 Display behavior

- Audit and event timestamps are displayed in the user's display zone.
- Recurrence descriptions are displayed in the strategy schedule zone.
- A next-execution instant is displayed in the strategy zone. If the user's
  display zone differs, the UI also shows the equivalent user-local time.
- Every schedule screen and notification includes a recognizable time-zone
  label; bare values such as `21:00` are not sufficient.

### 3.3 User display-zone preference

Account settings support:

- `FOLLOW_DEVICE` (default): the UI uses the current IANA device zone; the app
  synchronizes its latest valid IANA zone for server-rendered notifications.
- `FIXED`: the UI and server-rendered notifications use an explicitly selected
  IANA zone.

Fallback order is fixed preference, current device zone, last synchronized
device zone, then `UTC`. Numeric offsets such as `+08:00` are diagnostic data
only and are never the primary scheduling identity.

## 4. Persistence Contract

### 4.1 Recurring schedules

Add to `strategy`:

- `schedule_timezone VARCHAR(64) NOT NULL`

The existing `cron` field remains the recurrence expression. Together,
`cron + schedule_timezone` define the user's scheduling intent.

### 4.2 Absolute timestamps

The following values represent UTC instants:

- `plan.next_time`
- `order_intent.scheduled_fire_time`
- `plan_fire_execution.scheduled_fire_time`
- order, snapshot, user, exchange, and strategy audit timestamps
- notification outbox creation, lease, retry, sent, and update timestamps

During migration the existing `DATETIME` columns may remain for compatibility,
but application access must convert them through one UTC persistence boundary.
New Java domain/API code uses `Instant`, not zone-less `LocalDateTime`, for these
fields. Column comments and migration documentation declare their UTC semantic.

No migration may blindly shift existing values. Each legacy column is classified
by the code path that produced it, and migration tests use known rows to prove
whether conversion is required.

## 5. API Contract

### 5.1 Input

Strategy creation and schedule updates accept:

```json
{
  "cron": "0 21 * * *",
  "schedule_timezone": "Asia/Shanghai"
}
```

The backend validates the zone with `ZoneId.of` and rejects unknown or fixed
offset pseudo-zones for recurring schedules.

### 5.2 Output

All absolute timestamps use RFC 3339/ISO-8601 with `Z` or an explicit offset.
The canonical response is UTC:

```json
{
  "next_execution_at": "2026-08-25T13:00:00Z",
  "created_at": "2026-08-24T08:15:30Z",
  "schedule_timezone": "Asia/Shanghai"
}
```

The backend does not pre-format localized date strings for ordinary API
responses. During compatibility rollout, legacy fields such as `next_time` may
remain as aliases, but they must carry an explicit offset and have a documented
removal point.

## 6. Backend and Quartz

- The process clock is UTC.
- Absolute values use `Instant`.
- `ZonedDateTime` is used only when resolving a local recurrence in an IANA zone
  or formatting it for a user.
- `LocalDateTime` is not accepted at scheduling, event, API, or persistence
  boundaries unless the value is explicitly a wall-clock value paired with a
  `ZoneId` in the same object.
- Each Quartz trigger uses its strategy's `schedule_timezone` through
  `CronScheduleBuilder.inTimeZone`.
- Trigger reconciliation verifies both the cron expression and time zone; a
  mismatch causes rescheduling.
- `next_execution_at` is taken from Quartz as an instant and persisted as UTC.

### 6.1 Misfires and recovery

Automated buys use a **skip missed execution** policy. A missed scheduled buy is
not replayed after a long outage because delayed market orders can violate user
intent.

- Cron triggers retain `MISFIRE_INSTRUCTION_DO_NOTHING`.
- Trade jobs must not use Quartz recovery to replay a previously scheduled buy.
- Recovery is limited to reconciling an order that may already have been
  submitted, using the existing deterministic `clientOrderId` and order-intent
  state machine.
- A skipped schedule writes an operational event and may notify the administrator
  and affected user according to notification policy.

## 7. Frontend and Mobile

- Mobile continues supplying an IANA zone from `flutter_timezone`.
- Browser fallback uses `Intl.DateTimeFormat().resolvedOptions().timeZone`.
- The numeric offset is retained only for diagnostics and compatibility.
- Strategy creation sends the selected `schedule_timezone` and previews cron
  occurrences using that zone, not the JavaScript runtime default.
- A single time utility parses only timestamps containing `Z` or an explicit
  offset. Zone-less absolute timestamps are treated as contract violations, not
  guessed as local time.
- Formatting accepts an explicit target zone and locale.
- Plan cards, details, order history, exchange details, snapshots, and account
  timestamps all use the shared formatter.

## 8. Bark Notifications

Notification events carry distinct UTC fields:

- `scheduled_at`: intended strategy execution instant, when applicable.
- `occurred_at`: actual event or confirmed trade instant.
- `sent_at`: outbox delivery instant, retained for audit and diagnostics.

A successful trade message renders, in one explicit display zone:

```text
Trade succeeded
Scheduled: 2026-08-25 21:00:00 Asia/Shanghai
Executed: 2026-08-25 21:00:01 Asia/Shanghai
Symbols: BTCUSDT, ETHUSDT
Result: FILLED
```

If execution delay exceeds the configured threshold, the message includes the
delay and the scheduler emits a separate delayed-execution operational event.
The body must never label `scheduled_at` simply as `Time`.

## 9. DST Rules

An IANA zone controls daylight-saving gaps and overlaps.

- For a nonexistent local time during a spring-forward gap, the occurrence is
  skipped and the next valid recurrence is used.
- For an overlapping local time during fall-back, a recurring strategy executes
  at most once for that local schedule occurrence.
- Idempotency remains keyed by the resolved UTC `scheduled_at` instant.
- Tests cover a DST-observing zone such as `America/New_York` as well as
  `Asia/Shanghai`.

## 10. Migration and Rollout

1. Inventory existing time columns and capture production samples before any
   conversion.
2. Add `strategy.schedule_timezone` with `Asia/Shanghai` for existing rows.
3. Add the user display-zone preference and last synchronized device zone.
4. Introduce UTC/RFC-3339 API fields while retaining compatible aliases.
5. Update Quartz creation and reconciliation to use each strategy's zone.
6. Update the frontend/mobile create flow and shared formatter.
7. Split notification scheduled, occurred, and sent timestamps.
8. Rebuild all active Quartz triggers after deploying the schema and code.
9. Compare old and new next-fire instants for every active strategy before
   enabling execution.
10. Remove legacy zone-less API aliases only after all supported clients use the
    new contract.

Rollback preserves the added columns and data; it disables the new API fields
and trigger reconciliation without shifting stored timestamps back.

## 11. Observability

Every plan execution log includes:

- `planId`
- `scheduleTimezone`
- `scheduledAt` as UTC
- `actualStartedAt` as UTC
- `delayMs`
- Quartz recovery/misfire indicators
- `nextFireAt` as UTC

Metrics distinguish on-time executions, skipped misfires, delayed executions,
and order reconciliation. Alerting uses actual UTC instants and formats only at
the delivery edge.

## 12. Verification and Acceptance Criteria

- Creating `21:00 Asia/Shanghai` resolves to the correct UTC instant and Quartz
  trigger zone.
- The same strategy remains anchored to Shanghai when viewed from New York.
- UI historical timestamps render correctly in both zones from the same UTC
  API value.
- API absolute timestamps always contain `Z` or an explicit offset.
- A server restart after a missed buy does not place a catch-up market order.
- An in-flight ambiguous order is reconciled without duplicate submission.
- Bark shows scheduled and executed times separately and in the declared zone.
- Existing strategies retain their effective Shanghai schedule after migration.
- DST gap and overlap tests prove no duplicate automated buy.
- Database, API, Quartz, UI, and Bark integration tests use a shared set of
  cross-zone fixtures.

## 13. Non-Goals

- Reinterpreting historical timestamps without evidence of their original
  semantics.
- Supporting arbitrary abbreviations such as `CST` as schedule zones.
- Automatically changing an existing strategy when a user travels.
- Replaying all missed trading executions after downtime.
