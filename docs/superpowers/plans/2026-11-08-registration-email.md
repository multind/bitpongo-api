# Registration Email and Password Reset Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Require email ownership for new registrations and correct the reset password length mismatch.

**Architecture:** Reuse SMTP and the existing email challenge mechanism, with a registration-only purpose and transactional redemption. The Vue registration screen obtains the challenge and submits the code with the existing fields.

**Tech Stack:** Spring Boot, MySQL/Flyway, MockMvc/Testcontainers, Vue, Vitest.

**Spec:** [Registration email design](../specs/2026-11-08-registration-email-design.md)

## Global Constraints

- Keep existing logins, sessions, and strategies unchanged.
- Eight-digit codes, ten-minute expiry, 60-second resend cooldown, five-attempt cap.
- No registration bypass for old clients or when SMTP is unavailable.
- Use the existing 8–128 character letter-and-digit password policy.

## Review Focus

- Code submitted for a different email or purpose: reject without creating an account.
- Two requests redeem the same code: allow at most one registration.
- Wrong attempts and resends: persist the attempt count; invalidate the earlier code.
- Editing the email while an issuance response is pending: discard the stale challenge.
- Correct reset code plus an eight-character password: accept; replay remains rejected.

### Task 1: Reset request validation

**Files:** `AccountSecurityController.java`, `AccountRecoveryIntegrationTest.java` in the backend.

- [ ] Add a MockMvc test that obtains a real RESET challenge through the mocked SMTP boundary, resets to `Reset123`, then verifies the password hash and rejects replay.
- [ ] Run `./mvnw -Dtest=AccountRecoveryIntegrationTest test`; expect the reset request to fail with 400 before fixing.
- [ ] Remove the conflicting 12-character DTO minimum and retain the shared service validation.
- [ ] Re-run the integration test and commit the reset fix separately.

### Task 2: Backend registration contract

**Files:** `AccountRecoveryService.java`, `EmailCodeSender.java`, `UserController.java`, `UserDtos.java`, `UserApplicationService.java`, relevant integration tests.

- [ ] Add integration tests for missing, mismatched, wrong-purpose, expired, replayed and concurrent codes; confirm wrong attempts persist and only successful verified registration creates a user/token.
- [ ] Extend `POST /api/users/security/code` with `REGISTER` using the existing nullable-owner challenge schema. Do not send to an already registered address. Use a bounded executor to avoid exposing recipient-specific delivery outcomes; return 503 only for global configuration or saturation. Keep registration limits separate from recovery.
- [ ] Add `challengeId` and string `code` to `POST /api/users/register`. Bind them to the normalized email. Lock the challenge row and redeem in the same transaction as account creation; persist failed attempts before returning an error.
- [ ] Verify the new endpoint contract, recovery regressions and existing login behavior; commit.

### Task 3: Web registration

**Files:** frontend `src/views/register/index.vue`, `src/views/register/index.spec.ts`, `src/api/security.ts`, registration API/store and localized strings.

- [ ] Add Vitest cases for sending, email edits, late replies, countdown, leading zeroes, and submission with the challenge bound to the current email.
- [ ] Add code entry and resend action to the existing page in its current style, with three locale variants.
- [ ] Submit the challenge ID and string code via the registration store/API. Keep the current agreement and automatic login behavior.
- [ ] Run registration tests, backend integration tests, and frontend production build; commit. Coordinate frontend/backend and App bundle releases before enabling the new server contract in production.
