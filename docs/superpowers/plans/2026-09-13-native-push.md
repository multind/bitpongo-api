# Native push implementation plan

Implement the approved native-push-contract.md through authenticated account preferences and installation bindings, a durable per-device outbox, and FCM HTTP v1 with Google OAuth. Preserve administrator and legacy Bark delivery.

1. Prove native policy, error classification, isolation and lease behavior with failing tests.
2. Add cascading MySQL persistence, account settings/device API, versioned registration and test throttling.
3. Fan out sanitized existing events atomically, independently of Bark preferences, with recipient-scoped dedupe windows.
4. Dispatch using independent leases and guarded binding reads; honor provider retry delay, expiry and terminal errors.
5. Verify Maven unit/integration tests and dependency convergence; document deployment and unavailable external delivery gates.
