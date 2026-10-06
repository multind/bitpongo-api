# Login sessions / 登录会话

## Compatibility

- Existing signed, unexpired tokens remain valid. Authentication registers a session on first use, not retroactively at login time.
- New tokens contain a random `jti`, making separate logins independently revocable. Tokens issued before this change can be identical when issued for the same user in the same second. Devices sharing one such token share one session; revoke it together or sign in again to obtain separate sessions.
- Session storage contains SHA-256 token digests, never raw tokens. Listing returns no token or digest, email, IP address, or device fingerprint. It lists at most 100 active sessions, current session first.
- `GET /api/users/sessions` returns `{code,message,data:{items:[...]}}` with `id`, `createdAt`, `lastSeenAt`, `expiresAt`, and `current`. Timestamps are UTC instants. The UI converts them using the user's display timezone.
- `DELETE /api/users/sessions/{id}` revokes only a session owned by the authenticated user. Revoking the current session signs that client out locally after success. Other clients fail authentication on their next request; requests already authenticated are not cancelled.
- Registration and revocation serialize on the user row. Revoked rows must remain until token expiry: deleting them earlier would let a still-valid token register again. Account deletion clears session metadata; the inactive-account check prevents re-adoption.

## Deployment

Apply Flyway V22 before enabling this version of the API. No JWT secret rotation or forced logout is needed. No additional credentials or mount is required. Database failures in session checks fail authentication closed; monitor API/database health before rollout.

Do not roll back to an API that ignores revocation: it would accept previously revoked but still unexpired tokens. A rollback requires an explicit security plan, not merely replacing the image. Do not delete active or revoked session rows while their tokens can remain valid. Automatic cleanup runs every ten minutes, deleting at most 1,000 records per run whose expiry is more than one day in the past. It uses application UTC time, not the database server's clock. `LOGIN_SESSION_CLEANUP_DELAY` and `LOGIN_SESSION_CLEANUP_INITIAL_DELAY` are millisecond intervals (default 600000).

## 中文说明

旧 Token 不强制失效；首次访问时登记为会话。新登录生成独立标识，只撤销用户选择的会话。旧 Token 若被多个设备共用，无法可靠区分设备，界面明确提示这一限制，不伪造设备名称。数据库只存摘要，列表不暴露 Token、摘要或设备指纹。撤销已认证请求不会中途取消；下一次请求开始拒绝认证。

上线须先执行 V22，数据库故障时认证不会绕过校验。回退到不检查撤销状态的旧后端会恢复被撤销 Token 的权限，必须另行制定安全回退方案。默认每十分钟清理最多 1,000 条已过期超过一天的记录，使用应用 UTC 时间；不得提前删除仍有效 Token 的撤销记录。
