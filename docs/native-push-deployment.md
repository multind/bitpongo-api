# Native Apple / Google Play notifications

Native delivery uses FCM HTTP v1 for Android and FCM → APNs for Apple. It is disabled by default. Existing administrator and user Bark behavior remains supported; native account preferences are independent of Bark configuration. No public send API or FCM topics are used.

## Server configuration

Set `FCM_ENABLED=true`, `FCM_PROJECT_ID` to the real Firebase project, and `GOOGLE_APPLICATION_CREDENTIALS` to an external readable service-account JSON file. Enable the Firebase Cloud Messaging API and grant the service account permission to send messages for the project. Never commit the file or put its contents in environment variables/logs. Missing or malformed files leave the application usable with `available=false`. Configuration availability validates local credentials, not remote IAM, APNs configuration or device receipt.

The implementation uses Google's maintained OAuth credential library and HTTP v1, keeping transport retries in the database outbox. Dependencies are converged explicitly with Maven Enforcer.

| Optional variable | Default | Meaning |
| --- | --- | --- |
| FCM_DISPATCH_DELAY | 5s | Dispatch polling interval |
| FCM_DISPATCH_INITIAL_DELAY | 30s | Startup delay |
| FCM_ALERT_TTL_SECONDS | 86400 | Alert TTL, clamped to 60–86400 seconds |
| FCM_STALE_DEVICE_DAYS | 90 | Device retention since last registration |
| FCM_DELIVERY_RETENTION_DAYS | 30 | Terminal delivery record retention |
| FCM_CLEANUP_DELAY | 24h | Cleanup interval |
| FCM_CLEANUP_INITIAL_DELAY | 5m | First cleanup delay |

For Docker, add a private override file only when enabling FCM. Use a host path that exists and mount it read-only; the default compose file must not bind a nonexistent credential file:

```yaml
services:
  api:
    environment:
      FCM_ENABLED: "true"
      FCM_PROJECT_ID: ${FCM_PROJECT_ID:?required}
      GOOGLE_APPLICATION_CREDENTIALS: /run/secrets/fcm-service-account.json
    volumes:
      - type: bind
        source: ${FCM_CREDENTIALS_HOST_PATH:?required}
        target: /run/secrets/fcm-service-account.json
        read_only: true
        bind:
          create_host_path: false
```

Device registration also accepts optional `activate` (default false). Remote DELETE revokes the binding and clears queued work and the raw token; an automatic sync keeps it revoked, does not restore the raw token, and returns `registered=false`. Only a new account binding or explicit user activation (`activate=true`) clears that revocation. Device listings retain revoked devices as disabled. Revoked tombstones retain the installation/account identity and an opaque non-routable fingerprint indefinitely, even beyond the stale-device interval; ordinary token transfers cannot erase them. Account deletion removes them. An invalid token is distinct from user revocation: a rotated token can recover an invalid binding.

Ordinary logout, permission loss and account switch use authenticated `POST /api/users/notifications/push/devices/{installationId}/logout` with `{ "token": "old-device-token" }`, returning the usual envelope with null data. This atomically detaches only the matching account/token fingerprint when the binding is not explicitly revoked; pending deliveries cascade away. Delayed cleanup for an older rotated token cannot remove a new binding. Explicit DELETE revocation survives this logout cleanup. Native clients retain the old token only in secure pending-cleanup storage until the request succeeds; they do not use DELETE for ordinary logout.

## Device configuration

Register Android/iOS applications in Firebase matching the mobile package/bundle IDs. Mobile uses public Firebase application configuration, never service-account credentials. Upload the Apple APNs authentication key in Firebase, configure matching Apple team/bundle IDs and provisioning capabilities, and verify production/sandbox behavior on physical devices. The mobile guide specifies native build setup.

The authenticated `/api/users/notifications/push` endpoints manage account preferences and UUID installation bindings. Test requests target only an owned, permission-enabled installation and are limited to one per account per minute. The API returns `queued`, not receipt confirmation. Provider `ACCEPTED` means provider acceptance only.

Payload data contains string fields `eventId`, `userId`, `eventType`, optional `planId`. Titles/bodies use the existing sanitized localized renderer. Native tests use `PUSH_TEST`. Financial fields are not sent via topics. Frontend/native handlers must validate the signed-in account and allowlisted route before opening a plan. Locale variants such as `en-us` are accepted and stored as `en-US`; timezone must be an IANA name.

Device presentation uses the registration's canonical locale and IANA timezone when each delivery is queued (including tests). Different devices on the same account can use different languages/timezones immediately after registration; saving account settings is not required. Account preferences still control categories, enabled state and sound. Already queued messages retain their rendered language/timezone.

Android channel selection follows urgency independently of category preferences: all time-sensitive events (including failed trades, manual review and market outage) use `bitpongo_alerts`; normal successful trades use `bitpongo_trades`; other events use `bitpongo_system`. Silent/passive variants append `_silent`. This matches foreground native channel importance.

## Reliability and operational limits

Each device has independent dedupe, attempts, lease owner and expiration. Registration rotation/rebinding removes stale pending deliveries; delivery verifies account status, preferences, permission and binding revision under database locks. Successful siblings are not intentionally resent. UNREGISTERED disables only the matching binding; generic INVALID_ARGUMENT does not disable a device. Provider 429/5xx retry uses exponential backoff of at least 60 seconds and honors longer Retry-After values, capped at eight attempts and alert TTL. Logout, account soft deletion and stale-device cleanup remove pending bindings/deliveries.

A crash after provider acceptance but before database commit can cause a retry; exactly-once receipt is not promised. Client eventId dedupe is required. Device registration and same-account changes may wait for the bounded provider request while final ownership is locked. Restrict database access and avoid SQL parameter trace logging because registration tokens are sensitive.

## Acceptance gates

Run Maven unit and MySQL integration suites with JDK 26. Tests use a mocked provider and isolated Testcontainers database; they must never invoke production delivery. Separately verify credentials/IAM, APNs setup, Android/iOS permission denial, token rotation/logout/account switch, foreground/background/cold start and actual physical-device receipt. Those external gates remain unverified without real configuration/devices.

References: [FCM HTTP v1](https://firebase.google.com/docs/cloud-messaging/send/v1-api), [FCM error codes](https://firebase.google.com/docs/cloud-messaging/error-codes), [token management](https://firebase.google.com/docs/cloud-messaging/manage-tokens).
