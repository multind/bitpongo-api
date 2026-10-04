# Personal profile and market color preferences

This migration adds authenticated `GET /api/users/profile/details` and `PUT /api/users/profile/details`. The existing `GET /api/users/profile` response is unchanged.

The new endpoint uses camelCase JSON, matching the migrated H5 pages:

```json
{
  "name": "Alice",
  "country": "HK",
  "introduction": "Personal introduction",
  "upDownColor": "GREEN_UP"
}
```

- `name`: trimmed, required, up to 50 Unicode code points. Control characters are rejected.
- `country`: optional two-letter ISO country/region code, normalized to uppercase. This is profile metadata, not an exchange eligibility or residency check.
- `introduction`: optional plain text, up to 500 UTF-16 code units; line breaks are supported. The UI does not render it as HTML.
- `upDownColor`: `GREEN_UP` or `RED_UP`; defaults to `GREEN_UP` for existing accounts.

Only the authenticated account can be updated. The endpoint cannot change passwords, emails, account status or ownership. Settings are stored in the MySQL `user_profile_preferences` table added by Flyway V19; nickname remains in the existing `user.name` column. Account deletion removes the new personal details within the same database transaction.

No new service credentials or storage mounts are needed for these fields. The H5 restores saved nickname/color at login and application startup, protects against stale asynchronous responses, and applies the color convention to market ticker changes and candlesticks. Order status/error colors are unchanged.

Avatar upload, email/phone verification, password recovery and independently revocable login sessions remain separate pending migration work. No placeholder page should be interpreted as completion of those capabilities.

## JSON compatibility

The backend's existing global snake_case naming policy is preserved. The newly migrated market, index, alert, Webhook, subscription and profile DTOs explicitly declare camelCase at their boundaries. `H5JsonContractTest` loads the application's real Jackson configuration to cover both these fields and the legacy profile's `created_at` response.
