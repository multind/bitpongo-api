# Webhook alerts

Webhook alerts create inbox/log entries and enqueue notifications. They never place exchange orders.

## Deployment

Set `WEBHOOK_CREDENTIAL_ENCRYPTION_KEY` to an independently generated 32-byte Base64 key (`openssl rand -base64 32`). Keep it stable across deployments and out of Git. Without it, users can save drafts, but cannot activate endpoints. Changing it makes existing encrypted signing keys unreadable; rotate each affected endpoint explicitly.

The frontend derives the receiving URL from its configured API base URL. Publish `POST /api/webhooks/receive/{publicId}` through the existing HTTPS reverse proxy. The public identifier is not the signing secret. Do not log request bodies, signature headers or signing keys.

## Signing

Activation returns a signing key once. Rotation invalidates the previous signing key; revocation disables the endpoint. Send a JSON object containing `message` and optionally `title` (maximum lengths 900 and 100 characters; body limit 4096 bytes).

Headers:

- `X-Webhook-Timestamp`: Unix timestamp in seconds, within five minutes of server time.
- `X-Webhook-Nonce`: unique 16–64 character value using letters, digits, `_` or `-`.
- `X-Webhook-Signature`: lowercase or uppercase hexadecimal HMAC-SHA256 of the exact UTF-8 bytes below, using the returned signing-key string as the HMAC key.

```text
timestamp + "\n" + nonce + "\n" + raw_request_body
```

Do not reserialize the JSON after signing it. Duplicate receipts/events return 409 without a second notification. Accepted traffic is limited to 60 requests per minute per endpoint. Nonmatching keywords still consume the receipt nonce and rate budget but do not notify. Old receipt records are cleaned after ten minutes on accepted requests, including keyword-filtered requests.

An optional IP allowlist compares exact numeric addresses against the servlet remote address. Forwarded headers are not trusted. Behind a reverse proxy this may be the proxy address: configure trusted-proxy handling deliberately before depending on original-client IP filtering.

Inbox and log deletion are independent visibility changes; event identities remain for deduplication. Account deletion physically removes this user's events, rules, encrypted signing credentials and associated receipts.
