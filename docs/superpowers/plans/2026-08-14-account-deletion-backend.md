# Account Deletion Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a password-confirmed account deletion API that immediately disables authentication and trading, removes exchange secrets, anonymizes personal data, and preserves anonymous history.

**Architecture:** Add account lifecycle fields and an external-identity tombstone table through Flyway. A focused transactional `AccountDeletionService` owns deletion semantics, while `AuthenticatedUserResolver` enforces active-account checks for both local JWT and WordPress-compatible tokens.

**Tech Stack:** Java 26, Spring Boot 4.1, Spring Security, Spring Data JPA, Flyway, MySQL 9.7 Testcontainers, JUnit 5, Mockito, MockMvc.

## Global Constraints

- Preserve every existing REST response contract except for the new `DELETE /api/users/account` endpoint.
- The request body is exactly `{ "password": "..." }`; success is the existing `ApiResponse` envelope with code `200` and no sensitive payload.
- Account deletion is irreversible; the original email may register a new account with a new identity.
- Set every user plan to `stop` before clearing exchange credentials.
- Preserve strategies, plans, orders, snapshots, and coins as anonymous history.
- Never log or return passwords, JWTs, exchange access keys, exchange secret keys, or the original email.
- A JWT issued before deletion must fail on the next authenticated request.
- Database changes and anonymization are one transaction; scheduler mutations run only after commit.
- Follow TDD for every behavior change and commit after every independently passing task.

---

## File Map

- `src/main/resources/db/migration/V4__account_lifecycle.sql`: lifecycle columns and external identity tombstones.
- `src/main/java/com/multind/bitpongo-api/auth/UserEntity.java`: maps `status` and `deletedAt`.
- `src/main/java/com/multind/bitpongo-api/auth/DeletedExternalIdentityEntity.java`: maps a provider/subject tombstone without personal data.
- `src/main/java/com/multind/bitpongo-api/auth/DeletedExternalIdentityRepository.java`: provider/subject lookup.
- `src/main/java/com/multind/bitpongo-api/auth/AccountDeletionService.java`: transactional deletion use case and after-commit scheduler pause.
- `src/main/java/com/multind/bitpongo-api/auth/UserDtos.java`: deletion request record.
- `src/main/java/com/multind/bitpongo-api/auth/UserController.java`: authenticated deletion endpoint.
- `src/main/java/com/multind/bitpongo-api/auth/UserApplicationService.java`: active-only login/register behavior and WordPress tombstone guard.
- `src/main/java/com/multind/bitpongo-api/auth/AuthenticatedUserResolver.java`: active-account enforcement for all bearer tokens.
- `src/main/java/com/multind/bitpongo-api/plan/PlanRepository.java`: locks a user's plans during deletion.
- `src/main/java/com/multind/bitpongo-api/exchange/ExchangeRepository.java`: locks a user's exchange credentials during deletion.
- `src/test/java/com/multind/bitpongo-api/infrastructure/persistence/*MigrationTest.java`: proves empty and legacy schema upgrades.
- `src/test/java/com/multind/bitpongo-api/auth/AccountDeletionServiceTest.java`: deletion transaction semantics.
- `src/test/java/com/multind/bitpongo-api/auth/AuthenticatedUserResolverTest.java`: immediate token invalidation and WordPress guards.
- `src/test/java/com/multind/bitpongo-api/auth/UserControllerContractTest.java`: HTTP contract.
- `src/test/java/com/multind/bitpongo-api/contract/PythonApiContractTest.java`: records the new route without changing legacy routes.

### Task 1: Persist account lifecycle state

**Files:**
- Create: `src/main/resources/db/migration/V4__account_lifecycle.sql`
- Create: `src/main/java/com/multind/bitpongo-api/auth/DeletedExternalIdentityEntity.java`
- Create: `src/main/java/com/multind/bitpongo-api/auth/DeletedExternalIdentityRepository.java`
- Modify: `src/main/java/com/multind/bitpongo-api/auth/UserEntity.java`
- Modify: `src/test/java/com/multind/bitpongo-api/infrastructure/persistence/EmptySchemaMigrationTest.java`
- Modify: `src/test/java/com/multind/bitpongo-api/infrastructure/persistence/LegacySchemaCompatibilityTest.java`

**Interfaces:**
- Produces: `UserEntity.isActive(): boolean`, `UserEntity.authProvider`, `UserEntity.status`, `UserEntity.deletedAt`.
- Produces: `DeletedExternalIdentityRepository.existsByProviderAndSubject(String, String): boolean`.

- [ ] **Step 1: Extend migration tests and verify they fail**

Add assertions that `user.auth_provider` is `local` and `user.status` is `active` for legacy row `id=1`,
`user.deleted_at` exists, and table
`deleted_external_identity` has a unique `(provider, subject)` index.

```java
assertThat(jdbc.queryForObject(
        "select status from user where id = 1", String.class)).isEqualTo("active");
assertThat(jdbc.queryForObject(
        "select count(*) from information_schema.tables " +
        "where table_schema=database() and table_name='deleted_external_identity'",
        Integer.class)).isEqualTo(1);
```

Run: `./mvnw -Dtest=EmptySchemaMigrationTest,LegacySchemaCompatibilityTest test`

Expected: FAIL because the lifecycle columns and tombstone table do not exist.

- [ ] **Step 2: Add the Flyway migration**

Create `V4__account_lifecycle.sql` with this schema:

```sql
ALTER TABLE `user`
    ADD COLUMN auth_provider VARCHAR(16) NOT NULL DEFAULT 'local' AFTER password,
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'active' AFTER auth_provider,
    ADD COLUMN deleted_at DATETIME NULL AFTER last_login;

CREATE TABLE deleted_external_identity (
    id BIGINT NOT NULL AUTO_INCREMENT,
    provider VARCHAR(32) NOT NULL,
    subject VARCHAR(128) NOT NULL,
    deleted_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_deleted_external_identity_provider_subject (provider, subject)
) ENGINE=InnoDB;
```

- [ ] **Step 3: Map lifecycle entities**

Add `authProvider`, `status`, and `deletedAt` accessors to `UserEntity`, plus:

```java
public boolean isActive() {
    return "active".equals(status);
}
```

Map `DeletedExternalIdentityEntity` to the new table with `id`, `provider`, `subject`, and `deletedAt` fields.
Create the repository method exactly as:

```java
boolean existsByProviderAndSubject(String provider, String subject);
```

- [ ] **Step 4: Run migration tests**

Run: `./mvnw -Dtest=EmptySchemaMigrationTest,LegacySchemaCompatibilityTest test`

Expected: PASS on both empty and legacy schemas.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V4__account_lifecycle.sql \
  src/main/java/com/multind/bitpongo-api/auth/UserEntity.java \
  src/main/java/com/multind/bitpongo-api/auth/DeletedExternalIdentityEntity.java \
  src/main/java/com/multind/bitpongo-api/auth/DeletedExternalIdentityRepository.java \
  src/test/java/com/multind/bitpongo-api/infrastructure/persistence
git commit -m "feat: add account lifecycle schema"
```

### Task 2: Reject inactive and deleted identities during authentication

**Files:**
- Create: `src/test/java/com/multind/bitpongo-api/auth/AuthenticatedUserResolverTest.java`
- Modify: `src/main/java/com/multind/bitpongo-api/auth/AuthenticatedUserResolver.java`
- Modify: `src/main/java/com/multind/bitpongo-api/auth/UserApplicationService.java`
- Modify: `src/test/java/com/multind/bitpongo-api/auth/UserControllerContractTest.java`

**Interfaces:**
- Consumes: `UserEntity.isActive()` and tombstone lookup from Task 1.
- Produces: `AuthenticatedUserResolver.resolve(String)` only returns active local users.
- Produces: WordPress login rejects provider subject tombstone `provider="wordpress"`.

- [ ] **Step 1: Write failing resolver tests**

Cover these exact cases:

```java
@Test void localJwtForDeletedUserIsRejected() {
    user.setStatus("deleted");
    assertThatThrownBy(() -> resolver.resolve(tokens.issue(user.getId())))
            .isInstanceOf(IllegalArgumentException.class);
}

@Test void wordpressTokenForDeletedSubjectIsRejected() {
    when(wordpress.resolveUser("wp-token"))
            .thenReturn(new AuthenticatedUser(7L, "old@example.com", "Old"));
    when(tombstones.existsByProviderAndSubject("wordpress", "7")).thenReturn(true);
    assertThatThrownBy(() -> resolver.resolve("wp-token"))
            .isInstanceOf(IllegalArgumentException.class);
}
```

Also update `UserControllerContractTest#setUp` to set `localUser.status="active"`, then assert deleted users
cannot use `/api/users/profile` or `/api/users/login`.

Run: `./mvnw -Dtest=AuthenticatedUserResolverTest,UserControllerContractTest test`

Expected: FAIL because resolver and login do not inspect lifecycle state.

- [ ] **Step 2: Enforce active local authentication**

Inject `UserRepository` and `DeletedExternalIdentityRepository` into `AuthenticatedUserResolver`.
For a decoded local JWT, load the user by ID and require `isActive()`. For a WordPress token, resolve the
external user, reject its tombstone, then require the local row with the same ID to be active. Throw
`IllegalArgumentException("账号不可用")` for every inactive path so the bearer filter returns 401 without
leaking whether an account existed.

- [ ] **Step 3: Guard login and registration paths**

Change local login to filter on `UserEntity::isActive`. Set `authProvider="local"` and `status="active"` on
registration. In
`wordpressLogin`, check provider subject `String.valueOf(session.userId())` before creating or updating a row,
refuse an existing inactive row rather than overwriting it, then set `authProvider="wordpress"` and refresh the
local password hash after the upstream login succeeds. Refreshing the hash ensures account deletion verifies the
user's current WordPress password rather than a stale password.

- [ ] **Step 4: Run authentication tests**

Run: `./mvnw -Dtest=AuthenticatedUserResolverTest,UserControllerContractTest,SecurityConfigurationTest test`

Expected: PASS; old active-user response payloads remain unchanged.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/multind/bitpongo-api/auth \
  src/test/java/com/multind/bitpongo-api/auth
git commit -m "feat: reject inactive accounts during authentication"
```

### Task 3: Implement transactional account deletion

**Files:**
- Create: `src/main/java/com/multind/bitpongo-api/auth/AccountDeletionService.java`
- Create: `src/test/java/com/multind/bitpongo-api/auth/AccountDeletionServiceTest.java`
- Modify: `src/main/java/com/multind/bitpongo-api/plan/PlanRepository.java`
- Modify: `src/main/java/com/multind/bitpongo-api/exchange/ExchangeRepository.java`

**Interfaces:**
- Produces: `void AccountDeletionService.delete(long userId, String password)`.
- Consumes: `PlanScheduleService.pause(long planId)` only after a successful commit.

- [ ] **Step 1: Write failing service tests**

Test password failure, full mutation, and after-commit scheduling. The successful test must assert:

```java
service.delete(7L, "secret");

assertThat(user.getAuthProvider()).isEqualTo("wordpress");
assertThat(user.getStatus()).isEqualTo("deleted");
assertThat(user.getDeletedAt()).isEqualTo(now);
assertThat(user.getEmail()).startsWith("deleted+7+").endsWith("@invalid.local");
assertThat(plan.getStatus()).isEqualTo("stop");
assertThat(exchange.getAccessKey()).isNull();
assertThat(exchange.getSecretKey()).isNull();
assertThat(exchange.getPassword()).isNull();
verify(tombstones).save(argThat(value ->
        value.getProvider().equals("wordpress") && value.getSubject().equals("7")));
```

For a wrong password assert `BusinessException` code `401` and verify no plan, exchange, user, or tombstone
save occurs. Use a transaction synchronization test to prove `schedules.pause(id)` is not called before commit.

Run: `./mvnw -Dtest=AccountDeletionServiceTest test`

Expected: FAIL because the service and locking repository methods do not exist.

- [ ] **Step 2: Add locked repository queries**

Add methods using `@Lock(LockModeType.PESSIMISTIC_WRITE)`:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select p from PlanEntity p where p.userId = :userId")
List<PlanEntity> findAllForAccountDeletion(@Param("userId") Long userId);
```

Add the same shape to `ExchangeRepository` for `ExchangeEntity`.

- [ ] **Step 3: Implement the deletion transaction**

`AccountDeletionService.delete` must:

1. Load the user and require active status.
2. Verify `passwords.matches(password, user.getPassword())`, otherwise throw code 401 with `密码错误`.
3. Lock plans and exchanges.
4. Set every plan status to `stop` and collect its ID.
5. Null exchange `accessKey`, `secretKey`, `password`; set exchange status to `deleted`.
6. If `user.authProvider` is `wordpress`, save a WordPress tombstone for subject `String.valueOf(userId)` if one
   does not already exist. A local account must not create an unrelated WordPress tombstone.
7. Replace name with `已注销用户`, email with `deleted+<id>+<UUID>@invalid.local`, and password with a hash of a
   fresh 64-character random value; set status `deleted` and `deletedAt=now`.
8. Register one after-commit callback that pauses every collected plan ID. Scheduler errors are logged without
   restoring executable database state.

Keep the method annotated `@Transactional`; do not delete history repositories.

- [ ] **Step 4: Run service tests**

Run: `./mvnw -Dtest=AccountDeletionServiceTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/multind/bitpongo-api/auth/AccountDeletionService.java \
  src/main/java/com/multind/bitpongo-api/plan/PlanRepository.java \
  src/main/java/com/multind/bitpongo-api/exchange/ExchangeRepository.java \
  src/test/java/com/multind/bitpongo-api/auth/AccountDeletionServiceTest.java
git commit -m "feat: anonymize deleted accounts"
```

### Task 4: Expose the deletion HTTP contract

**Files:**
- Modify: `src/main/java/com/multind/bitpongo-api/auth/UserDtos.java`
- Modify: `src/main/java/com/multind/bitpongo-api/auth/UserController.java`
- Modify: `src/test/java/com/multind/bitpongo-api/auth/UserControllerContractTest.java`
- Modify: `src/test/java/com/multind/bitpongo-api/contract/PythonApiContractTest.java`

**Interfaces:**
- Produces: `DELETE /api/users/account` with `AccountDeletionRequest(@NotBlank String password)`.

- [ ] **Step 1: Write failing MockMvc contract tests**

Add `@MockitoBean AccountDeletionService accountDeletionService` and tests:

```java
mvc.perform(delete("/api/users/account")
        .header("Authorization", "Bearer " + tokens.issue(7L))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"password\":\"secret\"}"))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.code").value(200));

verify(accountDeletionService).delete(7L, "secret");
```

Also assert missing authentication returns 401 and blank password returns 400. Add
`DELETE /api/users/account` to the contract route set.

Run: `./mvnw -Dtest=UserControllerContractTest,PythonApiContractTest test`

Expected: FAIL with 404/405 because the endpoint does not exist.

- [ ] **Step 2: Add request DTO and controller method**

```java
public record AccountDeletionRequest(
        @NotBlank(message = "密码不能为空") String password) {}
```

```java
@DeleteMapping("/account")
public ApiResponse<Void> deleteAccount(
        @AuthenticationPrincipal AuthenticatedUser user,
        @Valid @RequestBody AccountDeletionRequest request) {
    accountDeletionService.delete(user.id(), request.password());
    return ApiResponse.success(null);
}
```

Inject `AccountDeletionService` through the controller constructor.

- [ ] **Step 3: Run HTTP contract tests**

Run: `./mvnw -Dtest=UserControllerContractTest,PythonApiContractTest,SecurityConfigurationTest test`

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/multind/bitpongo-api/auth/UserDtos.java \
  src/main/java/com/multind/bitpongo-api/auth/UserController.java \
  src/test/java/com/multind/bitpongo-api/auth/UserControllerContractTest.java \
  src/test/java/com/multind/bitpongo-api/contract/PythonApiContractTest.java
git commit -m "feat: expose account deletion endpoint"
```

### Task 5: Verify backend delivery

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: completed account deletion contract from Tasks 1-4.
- Produces: operator-facing behavior and verification instructions.

- [ ] **Step 1: Document the endpoint without sensitive examples**

Add the method/path, authentication requirement, irreversible effects, anonymous-history retention, and the
fact that the original email can create a new identity. Use `example-password` only; do not include a real token.

- [ ] **Step 2: Run focused tests**

Run: `./mvnw -Dtest='com.multind.zhitoubao.auth.*Test,com.multind.zhitoubao.contract.PythonApiContractTest' test`

Expected: PASS.

- [ ] **Step 3: Run full backend verification**

Run: `./mvnw verify`

Expected: PASS. If Docker is unavailable, record unit/MockMvc results separately and report Testcontainers as
blocked; do not claim full verification.

- [ ] **Step 4: Inspect secrets and diff**

Run: `git diff --check && git status --short && git diff --stat HEAD~4`

Expected: no whitespace errors, no credentials, only account lifecycle files and README changes.

- [ ] **Step 5: Commit documentation**

```bash
git add README.md
git commit -m "docs: document account deletion"
```
