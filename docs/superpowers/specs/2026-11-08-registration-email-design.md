# Registration email verification

Use an eight-digit email code on the existing registration page. Compared with
an email link, entering a code avoids navigating out of the mobile registration
flow. Existing accounts, sessions and strategies are unaffected.

Reuse the account email challenge store, SMTP sender, ten-minute expiry,
60-second resend cooldown, request limits and five-attempt cap. Give registration
a separate purpose. Bind the code to the normalized email and consume it once,
atomically with successful account creation. Never issue a login token for an
unverified registration. Fail closed if mail is unavailable; no legacy bypass.
Queue delivery on a bounded executor so public issuance does not reveal whether
SMTP was called. Recipient-specific failures invalidate the code without changing
the public response. Users can retry after the cooldown. Registration and reset
rate buckets are separate.

Separately, make the password-reset endpoint use the same 8–128 character
letter-and-digit rule as registration and the reset UI. Reproduce the 8–11
character failure with a real reset code in an API integration test. This
addresses the confirmed rule mismatch; the user's exact production response
has not been captured.
