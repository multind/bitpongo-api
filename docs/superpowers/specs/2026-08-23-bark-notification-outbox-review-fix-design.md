# Bark Outbox Review Fix Design

## Scope

This review round tightens Task 5 without changing later notification call sites. It corrects audience authorization, persisted payload hygiene, lease ownership, scheduler isolation, and configuration consistency.

## Audience authorization

`NotificationEvent` gains an optional typed `NotificationAudienceContext` containing a defensive copy of recipient user IDs plus an administrator flag. Construction rejects null collections, null IDs, and negative IDs. Only `SYSTEM_RECOVERED` consumes this context; attaching it to another event is invalid. The context is authorization metadata and is never serialized into the payload, dedupe key, or logs.

The resolver implements the explicit matrix:

- `SCHEDULER_FATAL`: administrator, plus the event user when present.
- `ORDER_MANUAL_REVIEW`: event user plus administrator.
- `TRADE_FAILED`: the event user only when their Bark setting is enabled; otherwise administrator fallback.
- `MARKET_OUTAGE`: administrator plus distinct users with active plans.
- `PLAN_EXECUTION_SKIPPED`, `TRADE_SUCCEEDED`, `ASSET_SNAPSHOT_FAILED`: event user only.
- `SERVICE_STARTED`: administrator only.
- `BARK_TEST`: no outbox audience.
- `SYSTEM_RECOVERED`: exactly the typed original-alarm audience, intersected with currently eligible targets.

User targets still require global user notifications to be enabled. Administrator targets require a configured administrator URL.

## Persisted payload hygiene

A centralized sanitizer copies only renderer-consumed fields for each event type. Unknown attributes and credential/transport-shaped data (`url`, `device`, `access`, `secret`, `token`, `key`, raw response content) are discarded. Persisted values pass through the existing error redaction and Unicode-safe truncation. The typed audience context never enters JSON.

## Lease ownership and timing

Flyway V8 adds nullable `lease_token VARCHAR(36)` to the existing outbox table. Claiming a due row assigns a UUID token and returns `(id, token)`. Loading, renewing, and terminal/retry updates require `id + token + SENDING`; renewal additionally requires a live lease. Each item renews atomically from a fresh clock reading before HTTP. A failed renewal skips delivery. Terminal timestamps and retry deadlines use a new clock reading after HTTP returns.

The existing 30-second lease remains safe because Bark HTTP is bounded to 10 seconds. Fresh per-item renewal prevents the tail of a 50-row batch from inheriting its initial claim deadline. An expired former owner cannot send or mutate a row after a new owner claims it.

## Scheduler and configuration

The core dispatcher remains an ordinary bean. A small scheduled trigger delegates every five seconds and is conditional on `zhitoubao.notifications.bark.dispatch-enabled`, defaulting to true. Integration tests can disable only scheduling while retaining the dispatcher and persistence beans.

Batch size is a single Java constant passed as the SQL limit parameter. Administrator rendering uses `zhitoubao.scheduling-zone`, matching the rest of the application.

## Verification

MySQL 9.7/Testcontainers tests persist and query real rows for the audience matrix and JSON sanitizer. Lease tests use concurrent workers, mixed priorities, controllable time, and a recording slow client to prove token ownership, renewal, non-overlap, and no duplicate send across the 30-second boundary. Dispatcher tests assert actual state transitions and completion-relative retry timing.
