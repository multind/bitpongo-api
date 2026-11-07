# Outbound Webhook Reminders Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver user-selected price, strategy and trade reminders to DingTalk, Feishu, Slack and Discord from one settings entry.

**Architecture:** Store encrypted destinations separately from inbound webhooks. Materialize one leased delivery per event and destination; dispatch through fixed provider adapters.

**Tech Stack:** Java 26, Spring Boot, MySQL/Flyway, JUnit/Testcontainers, Vue 3/Vitest.

**Spec:** [Outbound Webhook Reminders](../specs/2026-11-07-outbound-webhook-reminders-design.md)

## Global Constraints

- Do not change `/api/webhooks/receive/{publicId}`.
- Only `PRICE_ALERT_TRIGGERED`, `STRATEGY_SIGNAL`, `TRADE_SUCCEEDED`, and `TRADE_FAILED`; no administrator or account notifications.
- Price alerts require an explicit Webhook selection. No auto-enabling or deriving addresses from legacy preferences.
- Ship disabled by default until the deployment encryption key is configured. Never log or return a complete URL or signing secret.

## Review Focus

- Deceptive hosts and redirects never receive credentials (task 1).
- Foreign destination IDs cannot be read, mutated, tested or queued (tasks 2–3).
- An ambiguous network failure may repeat a remote message; durable dedupe prevents duplicate queue rows, not exactly-once remote delivery (task 3).
- A disabled destination stops pending deliveries even if an event was already queued (task 3).
- Saving an existing alert with no Webhook selected must not silently opt it in (task 4).

## Task 1: Provider transport

Files: `src/main/java/com/multind/bitpongo/notification/WebhookReminderProvider.java`, `WebhookReminderClient.java` (same package), and corresponding `src/test/java` test.

- [ ] Write provider contract tests using injected transport and fixed clock: four official request shapes, signed and unsigned DingTalk/Feishu, Discord mention suppression, rejection of non-HTTPS, user-info, unexpected ports/hosts/paths and redirects, and separate success/retry/permanent outcomes. Verify current official endpoint and signature docs before pinning fixtures.
- [ ] Run `./mvnw -Dtest=WebhookReminderClientTest test`; verify failure before implementation.
- [ ] Implement `validate(WebhookReminderProvider, String): URI` and `send(WebhookReminderProvider, URI, String signingSecret, String text): DeliveryResult` in the client. Revalidate on send, disable redirects, bound timeouts and response size; inspect provider response codes, not only HTTP status. Retry network errors, 429 and 5xx; redact transport exceptions before returning.
- [ ] Rerun the contract suite and commit provider transport.

## Task 2: Destination settings

Files: `src/main/resources/db/migration/V47__outbound_webhook_reminders.sql`, `WebhookReminderService.java` and `WebhookReminderController.java` in the notification package, `application.yml`, `.env.example`.

- [ ] Write `WebhookReminderSettingsIntegrationTest`: create/list/update/delete/test for one owner, reject another owner, assert ciphertext in DB and no secrets in responses. Test sends have fixed text and a per-user limit.
- [ ] Run the test red; add destination schema with owner foreign key, provider, label, enabled state and separately encrypted address/secret. Add dedicated `OUTBOUND_WEBHOOK_ENABLED` and `OUTBOUND_WEBHOOK_ENCRYPTION_KEY` with fail-closed validation.
- [ ] Implement authenticated `GET/POST /api/users/notifications/webhook-destinations`, `PUT/DELETE /{id}`, `POST /{id}/test`. List returns `{available, items}`; each item exposes `id, provider, name, enabled, maskedUrl`. Omitted credentials on update preserve existing values; explicitly empty secret clears it. New destinations are disabled until the user enables them.
- [ ] Rerun the integration suite and commit settings.

## Task 3: Durable delivery and event wiring

Files: next Flyway migration after V47, `WebhookReminderOutbox.java`, `WebhookReminderDispatcher.java`, `NotificationOutboxEnqueuer.java`, `AlertNotificationBridge.java` in the notification package, and `alerts/PriceAlertService.java`.

- [ ] Write `WebhookReminderDeliveryIntegrationTest`: assert unselected price alerts and excluded events generate zero rows, selected price alerts work without Bitpongo selected, duplicate events yield one row per destination, a second owner gets none, and retry/disable cases do not block another destination.
- [ ] Run red. Implement `enqueue(NotificationEvent): void` with atomic dedupe on event plus destination, user-only ownership, a sanitized message snapshot and no plaintext credentials. Hook strategy and trade events in `NotificationOutboxEnqueuer`; hook price events independently in `AlertNotificationBridge` after checking for `webhook` in the explicit selection. Permit that channel in `PriceAlertService`.
- [ ] Implement leased dispatch with retry/backoff, terminal status, retention, and a fresh active-user/enabled-destination check before sending. Document at-least-once behavior.
- [ ] Rerun delivery and migration tests and commit.

## Task 4: Frontend and end-to-end acceptance

Files in `bitpongo-front`: `src/api/webhookReminderDestinations.ts`, `src/views/member/notification-methods/WebhookReminderSheet.vue`, that folder’s `index.vue`, `src/views/alerts/create.vue`, notification and wizard specs, and `src/i18n/lang/{lang-base,zh-cn,zh-tw,en-us}.ts`.

- [ ] Write red tests: one Webhook entry replaces the placeholder rows; provider-specific help and signature fields; save, test, toggle and delete; a price-alert draft sends `webhook` only when selected. Verify SMS/phone stay disabled and perception stays hidden.
- [ ] Implement the sheet with the task 2 API, show only available adapters, keep legacy preference fields unchanged on general settings save, and make Webhook an opt-in on the alert form.
- [ ] Run targeted frontend suites, `npm test`, `npm run build`, and backend `./mvnw verify`. Browser-check all three locales. Verify each real provider against a dedicated test destination when credentials are available; do not send to production channels for testing.
- [ ] Commit frontend changes. Deployment is separate and must use a dedicated encryption key, with the feature initially disabled.
