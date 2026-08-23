# Bark Outbox Review Fix Implementation Plan

> Execute with strict grouped RED -> GREEN and preserve evidence in the Task 5 report.

**Goal:** Close formal-review gaps in Bark outbox audience authorization, payload persistence, lease ownership, scheduling isolation, and configuration behavior.

**Architecture:** Keep the current single outbox table and Spring transaction boundaries. Add typed authorization context at publication, a per-event payload sanitizer, token-owned leases, and a conditional scheduled wrapper around the unchanged callable dispatcher.

**Stack:** Java 26, Spring Boot 4.1, Spring Data JPA/JdbcTemplate, Flyway, MySQL 9.7 Testcontainers, JUnit 5.

---

### Task 1: Lock audience and payload behavior with failing integration tests

**Files:**
- Modify: `src/test/java/com/zhitoubao/notifications/bark/outbox/OutboxNotificationPublisherTest.java`
- Create: `src/test/java/com/zhitoubao/notifications/bark/outbox/OutboxNotificationAudienceNoAdminTest.java`

1. Add real-row assertions for every audience-matrix positive and negative case.
2. Add typed-context validation and `SYSTEM_RECOVERED` audience-reuse assertions.
3. Query `body_payload` JSON and prove allowed renderer fields remain while secrets, complete URLs, raw response data, and unknown keys never persist.
4. Run the publisher integration tests and capture the expected RED failures.

### Task 2: Implement typed audience and payload sanitizer

**Files:**
- Modify: `src/main/java/com/zhitoubao/notifications/bark/NotificationEvent.java`
- Create: `src/main/java/com/zhitoubao/notifications/bark/NotificationAudienceContext.java`
- Modify: `src/main/java/com/zhitoubao/notifications/bark/outbox/NotificationAudienceResolver.java`
- Create: `src/main/java/com/zhitoubao/notifications/bark/outbox/NotificationPayloadSanitizer.java`
- Modify: `src/main/java/com/zhitoubao/notifications/bark/outbox/NotificationOutboxEnqueuer.java`
- Modify: relevant policy tests if arbitrary recovered-event attribute behavior is removed.

1. Add defensive-copy validation and source-compatible event construction.
2. Encode the exact audience switch and current-eligibility checks.
3. Whitelist renderer fields per event and redact/truncate before persistence.
4. Re-run both publisher classes until GREEN.

### Task 3: Lock lease ownership and timing with failing MySQL tests

**Files:**
- Modify: `src/test/java/com/zhitoubao/notifications/bark/outbox/NotificationOutboxLeaseIntegrationTest.java`
- Modify: `src/test/java/com/zhitoubao/notifications/bark/outbox/NotificationOutboxDispatcherTest.java`

1. Add mixed-priority claim-order assertions.
2. Add old-token renewal/load/terminal-update rejection after expiry and re-claim.
3. Add two-worker slow-client coverage crossing 30 seconds and assert exactly-once observed sends.
4. Assert sent/retry timestamps use post-HTTP clock time.
5. Run targeted tests and capture RED failures.

### Task 4: Implement V8 token-owned leases and conditional scheduling

**Files:**
- Create: `src/main/resources/db/migration/V8__add_notification_outbox_lease_token.sql`
- Modify: `src/main/java/com/zhitoubao/notifications/bark/outbox/NotificationOutboxLeaseService.java`
- Modify: `src/main/java/com/zhitoubao/notifications/bark/outbox/NotificationOutboxDeliveryStore.java`
- Modify: `src/main/java/com/zhitoubao/notifications/bark/outbox/NotificationOutboxDispatcher.java`
- Create: `src/main/java/com/zhitoubao/notifications/bark/outbox/NotificationOutboxDispatchScheduler.java`
- Modify: `src/main/java/com/zhitoubao/notifications/bark/BarkProperties.java`
- Modify: `src/main/resources/application.yml`

1. Add lease token schema and token-aware claim/renew/load/update queries.
2. Renew each item from a fresh clock value before loading/sending.
3. Read completion time after HTTP for sent/retry/skipped state changes.
4. Move `@Scheduled` to a property-conditional wrapper; keep core beans available when disabled.
5. Parameterize the single batch-size constant and inject the configured scheduling zone.
6. Re-run lease and dispatcher tests until GREEN.

### Task 5: Isolate contexts and verify the complete repository

**Files:**
- Modify: all non-scheduler Testcontainers test property sets.
- Modify: `.superpowers/sdd/2026-08-23-bark-notification-backend/task-5-report.md`

1. Set `dispatch-enabled=false` in all Testcontainers contexts that do not test scheduled triggering.
2. Add production-binding coverage for the default `true` value and disabled-trigger/core-bean behavior.
3. Run all Task 5 tests with the prescribed Maven repository.
4. Run the full Maven suite and inspect output for Hikari-after-close or scheduler failures.
5. Append grouped RED/GREEN commands and concurrent evidence to the report.
6. Review the diff, commit the fix independently, and report commit/tests/concerns/report path.
