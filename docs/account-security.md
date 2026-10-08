# Account security

Independent Bitpongo email/password workflows; no dependency on the old tradebot service. SMS and social login are intentionally not enabled.

## Deployment

Set these environment variables in the API container (not the frontend):

The supplied `compose.yml` does not forward `ACCOUNT_EMAIL_*` or `ACCOUNT_SMTP_*` automatically. Adding them to `.env` alone is insufficient: explicitly add these variables to the API service's `environment` in a local deployment override. With Docker/1Panel, set them on the API container itself. Keep the override and credentials outside Git; no SMTP configuration is enabled by this migration.

```dotenv
ACCOUNT_EMAIL_ENABLED=true
ACCOUNT_SMTP_HOST=your-smtp-host
ACCOUNT_SMTP_PORT=587
ACCOUNT_SMTP_USERNAME=your-smtp-user
ACCOUNT_SMTP_PASSWORD=your-smtp-password
ACCOUNT_EMAIL_FROM=your-verified-sender@example.com
ACCOUNT_SMTP_SSL=false
```

Use STARTTLS on port 587 (required, no plaintext downgrade) or set `ACCOUNT_SMTP_SSL=true` for implicit TLS on port 465. SMTP has bounded 5-second connection/read/write timeouts. Sender/domain authorization, SPF/DKIM/DMARC, delivery limits and production inbox delivery must be verified with your provider. Do not commit credentials or enable SMTP debug logging. The sender uses Spring's mail starter: [Spring email configuration](https://docs.spring.io/spring-boot/reference/io/email.html).

Disabled/incomplete configuration returns `emailEnabled=false`; requesting a code returns 503. Password change remains available without SMTP. The readiness flag verifies configuration presence, not deliverability. A provider failure logs a sanitized warning, invalidates the challenge and preserves the generic reset response to avoid disclosing account existence. No automatic retry sends the same code; request again after the cooldown.

## API / UI

- `GET /api/users/security/status`: public capability status.
- `POST /api/users/security/password`: authenticated `{oldPassword,newPassword}`.
- `POST /api/users/security/code`: `{purpose: "RESET"|"EMAIL",email,oldPassword?}` returns an opaque `challengeId`, never the code. EMAIL requires authentication and the current password; RESET has a generic response for unknown accounts.
- `POST /api/users/security/reset`: public `{challengeId,code,newPassword}`.
- `POST /api/users/security/email`: authenticated `{challengeId,code,oldPassword}`; email comes only from the verified challenge, not the completion payload.

New passwords are 12–128 characters (existing passwords remain valid for login). Codes have 8 random digits, expire in 10 minutes and allow five attempts; failed attempts commit before returning errors. Resending invalidates previous codes for that purpose/owner/address. Codes are HMAC-SHA256 digests bound to a random challenge ID and the JWT server secret, not plaintext or reversible ciphertext. Changes are transactional and codes are bound to account security version, owner and purpose. Concurrent redemption succeeds once.

Sending is limited per email to one request per 60 seconds and five per hour, plus 20 requests per connecting IP per hour. Limits persist in MySQL. Only `request.getRemoteAddr()` is trusted, not arbitrary forwarded headers. Configure the server's **trusted** reverse-proxy address handling for your actual topology; otherwise the proxy's shared address has a shared quota. Add edge rate limits for public login/recovery and SMTP cost protection; this is not a CAPTCHA or a distributed-abuse prevention service.

## Session compatibility / rollback

Password changes and email-code requests share a ten-attempt per account/hour password-verification limit; failed password checks do not roll it back.

V24 adds `user.security_version=0`. Legacy Tokens without `sv` mean version 0 and remain valid until an account security change. New login Tokens carry the current version. Password change/reset and email change increment the version and revoke registered sessions in the same transaction: even previously unused Tokens are rejected on the next request. Login locks the user row before signing; session adoption checks the version under the same lock. Already-running authenticated requests are not cancelled.

Do not roll back to a backend that ignores `sv` after these operations: old tokens could become valid again. Do not reduce security versions or delete live revocation records. Account deletion removes challenges; hourly bounded cleanup removes challenges/rate buckets older than the retention buffer. Email binding changes the login address and does not re-enable deleted users or alter exchange permissions.

## Verification boundary

Tests substitute only SMTP delivery and exercise the real controller, MySQL transactions, hashing and authentication. Actual email receipt and iOS/Android WebView operation require configured-provider and physical-device acceptance; no real emails are sent by the suite.
