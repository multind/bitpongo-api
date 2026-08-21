# 本地注册与移除 WordPress 认证实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 删除全部 WordPress 认证行为，为本地账号提供注册后自动登录的完整后端、网页端和移动端内嵌页面交付。

**Architecture:** 后端只保留本地密码哈希和 JWT，注册与登录统一返回 `ApiResponse<LoginData>`；数据库通过增量 Flyway 迁移移除外部身份结构。网页端使用独立注册页面和统一会话写入方法，验证通过后将生产静态包同步到 Flutter 容器。

**Tech Stack:** Java 26、Spring Boot 4.1.0、Spring Security、Spring Data JPA、Flyway、MySQL、JUnit 5、MockMvc、Vue 3、TypeScript 5.9、Pinia 4、Vitest 4、NutUI 4、Flutter 3.41。

## Global Constraints

- `/api/users/v1/login` 必须删除，旧 WordPress Token 不再兼容。
- 注册密码至少 8 位，且必须同时包含字母和数字；前后端都必须校验。
- 注册成功必须在同一后端请求中返回 JWT，前端直接进入 `/member`。
- 不迁移或恢复任何 WordPress 用户，不增加找回密码、邮件验证、验证码或第三方登录。
- 不修改历史 Flyway 文件；使用新迁移删除 `deleted_external_identity` 和 `user.auth_provider`。
- 后端继续使用当前干净功能分支；网页端和移动端使用 `/private/tmp/bitpongo-front-local-registration` 与 `/private/tmp/bitpongo-mobile-local-registration` 隔离工作树，保护原工作树中的用户修改。
- 每个仓库只提交本任务文件，分别报告测试、提交和推送状态。

---

## 文件结构

### `bitpongo-api`

- 修改 `src/main/java/com/multind/bitpongo/auth/UserDtos.java`：定义服务端密码规则。
- 修改 `src/main/java/com/multind/bitpongo/auth/UserApplicationService.java`：注册后签发 JWT，处理并发重复邮箱，删除 WordPress 登录依赖。
- 修改 `src/main/java/com/multind/bitpongo/auth/UserController.java`：统一注册响应并删除 `/v1/login`。
- 修改 `src/main/java/com/multind/bitpongo/auth/AuthenticatedUserResolver.java`：只解析本地 JWT。
- 修改 `src/main/java/com/multind/bitpongo/auth/AccountDeletionService.java`：删除外部身份墓碑逻辑。
- 修改 `src/main/java/com/multind/bitpongo/auth/UserEntity.java`：删除 `authProvider`。
- 修改 `src/main/java/com/multind/bitpongo/auth/SecurityConfiguration.java`：删除 WordPress 登录入口，同时让已退役路径到达 MVC 并返回 404。
- 删除 `WordPressAuthClient.java`、`HttpWordPressAuthClient.java`、`WordPressSession.java`、`DeletedExternalIdentityEntity.java`、`DeletedExternalIdentityRepository.java`。
- 创建 `src/main/resources/db/migration/V5__remove_wordpress_authentication.sql`：删除外部身份结构。
- 修改 `src/main/resources/application.yml`：删除 WordPress 配置。
- 修改认证、数据库迁移和 API 契约测试；删除 `HttpWordPressAuthClientTest.java`。

### `bitpongo-front`

- 修改 `src/api/index.ts`：增加带类型的注册请求。
- 修改 `src/store/modules/user.ts`：抽取统一会话写入并增加 `register` action。
- 修改 `src/store/modules/user.spec.ts`：覆盖注册会话写入与失败保护。
- 创建 `src/views/register/index.vue`：独立注册表单。
- 创建 `src/views/register/index.spec.ts`：覆盖校验、提交和导航。
- 修改 `src/views/login/index.vue`：增加注册入口。
- 修改 `src/router/routes.ts`：增加 `/register`。
- 修改 `src/i18n/lang/lang-base.ts`、`zh-cn.ts`、`zh-tw.ts`、`en-us.ts`：增加注册和导航文案。

### `bitpongo-mobile`

- 更新 `assets/web_bundle/**`：同步已提交的网页端生产构建。
- 不修改 Flutter 源码、构建脚本或原工作树中的其他文件。

---

### Task 1: 后端注册后自动登录与密码规则

**Files:**
- Modify: `src/test/java/com/multind/bitpongo/auth/UserControllerContractTest.java`
- Modify: `src/main/java/com/multind/bitpongo/auth/UserDtos.java`
- Modify: `src/main/java/com/multind/bitpongo/auth/UserApplicationService.java`
- Modify: `src/main/java/com/multind/bitpongo/auth/UserController.java`

**Interfaces:**
- Consumes: `JwtTokenService.issue(long)`、`PasswordCompatibilityService.hash(String)`、`UserRepository.saveAndFlush(UserEntity)`。
- Produces: `UserApplicationService.register(UserCreateRequest): LoginData`，以及 `POST /api/users/register -> ApiResponse<LoginData>`。

- [ ] **Step 1: 修改注册契约测试，使其表达新响应和密码规则**

将原 `registerCreatesPythonCompatibleUserResponse` 改为使用 `abc12345`，断言：

```java
.andExpect(jsonPath("$.code").value(200))
.andExpect(jsonPath("$.data.token").isString())
.andExpect(jsonPath("$.data.info.id").value(9))
.andExpect(jsonPath("$.data.info.email").value("new@example.com"));
```

增加参数化或逐项请求测试，确保 `short1`、`abcdefgh`、`12345678` 都返回 400，消息为 `密码至少8位，且必须同时包含字母和数字`。增加邮箱输入 ` New@Example.COM ` 的测试，验证查询和响应都使用 `new@example.com`。

- [ ] **Step 2: 运行测试并确认 RED**

Run:

```bash
JAVA_HOME=/Users/zhangcong/Library/Java/JavaVirtualMachines/openjdk-26.0.1/Contents/Home mvn -Dmaven.repo.local=/Volumes/ExternalDrive/maven-repo/.m2/repository -Dtest=UserControllerContractTest test
```

Expected: FAIL；当前注册返回裸 `UserResponse`，且弱密码仍会进入服务。

- [ ] **Step 3: 实现最小注册契约**

在 `UserCreateRequest.password` 上增加：

```java
@Pattern(
        regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,}$",
        message = "密码至少8位，且必须同时包含字母和数字")
```

将服务返回值和控制器响应改为：

```java
public LoginData register(UserCreateRequest request) {
    // 规范化、检查重复、哈希并保存
    UserEntity saved = users.saveAndFlush(user);
    return new LoginData(tokens.issue(saved.getId()), info(saved));
}

public ApiResponse<LoginData> register(@Valid @RequestBody UserCreateRequest request) {
    return ApiResponse.success(users.register(request));
}
```

- [ ] **Step 4: 增加并发重复邮箱失败测试**

模拟：

```java
when(users.findByEmail("race@example.com")).thenReturn(Optional.empty());
when(users.saveAndFlush(any(UserEntity.class)))
        .thenThrow(new DataIntegrityViolationException("duplicate email"));
```

断言注册返回 400 和 `用户已存在`，而不是 500。

- [ ] **Step 5: 运行测试并确认第二次 RED**

Run: 使用 Step 2 相同命令。

Expected: FAIL；数据库唯一约束异常尚未映射为业务错误。

- [ ] **Step 6: 最小处理唯一约束竞争**

仅包围 `saveAndFlush`：

```java
try {
    UserEntity saved = users.saveAndFlush(user);
    return new LoginData(tokens.issue(saved.getId()), info(saved));
} catch (DataIntegrityViolationException exception) {
    throw new BusinessException(400, "用户已存在");
}
```

- [ ] **Step 7: 验证并提交 Task 1**

Run: 使用 Step 2 相同命令，Expected: PASS。

```bash
git add src/main/java/com/multind/bitpongo/auth/UserDtos.java src/main/java/com/multind/bitpongo/auth/UserApplicationService.java src/main/java/com/multind/bitpongo/auth/UserController.java src/test/java/com/multind/bitpongo/auth/UserControllerContractTest.java
git commit -m "feat: register local users with session"
```

---

### Task 2: 删除 WordPress 认证代码和数据库结构

**Files:**
- Modify: `src/test/java/com/multind/bitpongo/auth/AuthenticatedUserResolverTest.java`
- Modify: `src/test/java/com/multind/bitpongo/auth/AccountDeletionServiceTest.java`
- Modify: `src/test/java/com/multind/bitpongo/auth/UserControllerContractTest.java`
- Modify: `src/test/java/com/multind/bitpongo/infrastructure/persistence/EmptySchemaMigrationTest.java`
- Modify: `src/test/java/com/multind/bitpongo/infrastructure/persistence/LegacySchemaCompatibilityTest.java`
- Delete: `src/test/java/com/multind/bitpongo/auth/HttpWordPressAuthClientTest.java`
- Modify: `src/main/java/com/multind/bitpongo/auth/AuthenticatedUserResolver.java`
- Modify: `src/main/java/com/multind/bitpongo/auth/AccountDeletionService.java`
- Modify: `src/main/java/com/multind/bitpongo/auth/UserEntity.java`
- Modify: `src/main/java/com/multind/bitpongo/auth/UserApplicationService.java`
- Modify: `src/main/java/com/multind/bitpongo/auth/UserController.java`
- Modify: `src/main/java/com/multind/bitpongo/auth/SecurityConfiguration.java`
- Delete: `src/main/java/com/multind/bitpongo/auth/WordPressAuthClient.java`
- Delete: `src/main/java/com/multind/bitpongo/auth/HttpWordPressAuthClient.java`
- Delete: `src/main/java/com/multind/bitpongo/auth/WordPressSession.java`
- Delete: `src/main/java/com/multind/bitpongo/auth/DeletedExternalIdentityEntity.java`
- Delete: `src/main/java/com/multind/bitpongo/auth/DeletedExternalIdentityRepository.java`
- Create: `src/main/resources/db/migration/V5__remove_wordpress_authentication.sql`
- Modify: `src/main/resources/application.yml`

**Interfaces:**
- Consumes: 本地 `JwtTokenService.decodeUserId(String)` 和活动用户查询。
- Produces: 仅本地 JWT 的 `AuthenticatedUserResolver.resolve(String)`，无 WordPress Bean 或表依赖。

- [ ] **Step 1: 先修改测试表达删除后的行为**

测试变化：

- `AuthenticatedUserResolverTest` 用任意外部字符串调用 `resolve`，断言保留 `JwtTokenService` 的非法 Token 异常，删除所有 WordPress mock。
- `AccountDeletionServiceTest` 构造器不再传墓碑仓库；注销成功仍验证计划停止、密钥清除、用户匿名化和提交后暂停调度。
- `UserControllerContractTest` 删除 WordPress 成功/墓碑测试；对 `POST /api/users/v1/login` 断言 404。
- `EmptySchemaMigrationTest` 断言 `deleted_external_identity` 不存在，`user` 只保留 `status` 和 `deleted_at` 两个生命周期列。
- `LegacySchemaCompatibilityTest` 断言升级后 `auth_provider` 列不存在，而 `status='active'`。

- [ ] **Step 2: 运行删除行为测试并确认 RED**

Run:

```bash
JAVA_HOME=/Users/zhangcong/Library/Java/JavaVirtualMachines/openjdk-26.0.1/Contents/Home mvn -Dmaven.repo.local=/Volumes/ExternalDrive/maven-repo/.m2/repository -Dtest=AuthenticatedUserResolverTest,AccountDeletionServiceTest,UserControllerContractTest,EmptySchemaMigrationTest,LegacySchemaCompatibilityTest test
```

Expected: FAIL；WordPress 路由、Token 回退和数据库对象仍存在。

- [ ] **Step 3: 删除运行时代码和配置**

将解析器简化为：

```java
public AuthenticatedUser resolve(String token) {
    long userId = jwtTokens.decodeUserId(token);
    UserEntity user = requireActive(userId);
    return new AuthenticatedUser(userId, user.getEmail(), user.getName());
}
```

从用户服务、控制器、注销服务和实体中删除 WordPress/墓碑依赖与方法；删除五个 WordPress/外部身份源文件及 HTTP 客户端测试；删除 `application.yml` 的 `zhitoubao.wordpress` 块。

为了让已删除的公开地址返回 404 而不是先被认证过滤器拦截，安全配置只保留一个退役路径匹配：

```java
.requestMatchers(HttpMethod.POST,
        "/api/users/login", "/api/users/register", "/api/users/v1/login").permitAll()
```

该路径没有控制器映射，不执行任何登录逻辑。

- [ ] **Step 4: 增加数据库清理迁移**

创建：

```sql
DROP TABLE IF EXISTS deleted_external_identity;
ALTER TABLE `user` DROP COLUMN auth_provider;
```

同步删除实体字段和所有测试引用，不修改 V1-V4。

- [ ] **Step 5: 验证无 WordPress 运行时引用**

Run:

```bash
rg -n "WordPress|wordpress|WORDPRESS|authProvider|auth_provider|DeletedExternalIdentity" src/main
```

Expected: 只允许 V4 历史迁移和 V5 清理迁移中出现 `auth_provider`/`deleted_external_identity`；Java 与 `application.yml` 无匹配。

- [ ] **Step 6: 验证测试并提交 Task 2**

Run: 使用 Step 2 相同 Maven 命令，Expected: PASS。

```bash
git add -A src/main src/test
git commit -m "refactor: remove WordPress authentication"
```

---

### Task 3: 网页端注册 API 与会话仓库

**Files:**
- Modify: `src/api/index.ts`
- Modify: `src/store/modules/user.ts`
- Modify: `src/store/modules/user.spec.ts`

**Interfaces:**
- Consumes: `POST /users/register` 的 `{ token, info }` 数据。
- Produces: `registerAccount(data): Promise<AuthSession>` 和 `useUserStore().register(name, email, password)`。

- [ ] **Step 0: 准备隔离工作树依赖**

在 `/private/tmp/bitpongo-front-local-registration` 运行：

```bash
pnpm install --frozen-lockfile
```

Expected: 依赖安装成功且 `pnpm-lock.yaml` 不变。

- [ ] **Step 1: 先增加用户仓库注册测试**

在 API mock 中增加 `registerAccount: vi.fn()`，测试：

```ts
vi.mocked(api.registerAccount).mockResolvedValue({
  token: 'new-token',
  info: { id: 9, name: '新用户', email: 'new@example.com' },
});

const result = await store.register('新用户', 'new@example.com', 'abc12345');

expect(api.registerAccount).toHaveBeenCalledWith({
  name: '新用户', email: 'new@example.com', password: 'abc12345',
});
expect(store.token).toBe('new-token');
expect(store.info).toEqual(result.info);
```

另测 API 失败时原有 `token` 和 `info` 不被覆盖。

- [ ] **Step 2: 运行测试并确认 RED**

Run:

```bash
pnpm test -- src/store/modules/user.spec.ts
```

Expected: FAIL；注册 API 和 action 尚不存在。

- [ ] **Step 3: 实现带类型的注册和统一会话写入**

在 API 层定义：

```ts
export interface AuthSession {
  token: string;
  info: { id: number; name: string; email: string };
}

export function registerAccount(data: {
  name: string;
  email: string;
  password: string;
}): Promise<AuthSession> {
  return http.post('/users/register', data);
}
```

用户仓库新增 `setSession(session: AuthSession)`，让 `login` 和 `register` 都只在请求成功后调用它。

- [ ] **Step 4: 验证并提交 Task 3**

Run:

```bash
pnpm test -- src/store/modules/user.spec.ts
pnpm typecheck
```

Expected: PASS。

```bash
git add src/api/index.ts src/store/modules/user.ts src/store/modules/user.spec.ts
git commit -m "feat: add local registration session"
```

---

### Task 4: 网页端注册页面、导航和多语言文案

**Files:**
- Create: `src/views/register/index.vue`
- Create: `src/views/register/index.spec.ts`
- Modify: `src/views/login/index.vue`
- Modify: `src/router/routes.ts`
- Modify: `src/i18n/lang/lang-base.ts`
- Modify: `src/i18n/lang/zh-cn.ts`
- Modify: `src/i18n/lang/zh-tw.ts`
- Modify: `src/i18n/lang/en-us.ts`

**Interfaces:**
- Consumes: `useUserStore().register(name, email, password)`。
- Produces: `/register` 页面及登录/注册双向导航。

- [ ] **Step 1: 创建失败的页面行为测试**

使用 `@vue/test-utils` 挂载注册页并 mock 用户仓库、路由和 NutUI toast，覆盖：

- 密码 `abcdefgh` 不提交并显示密码规则提示。
- `abc12345` 与确认密码不同不提交。
- 未勾选协议不提交。
- 合法输入只调用一次 `register`，参数为姓名、邮箱和密码。
- 注册成功调用 `router.push({ path: '/member' })`。
- 后端拒绝时显示错误且不导航。

- [ ] **Step 2: 运行页面测试并确认 RED**

Run:

```bash
pnpm test -- src/views/register/index.spec.ts
```

Expected: FAIL；注册页面尚不存在。

- [ ] **Step 3: 实现独立注册页面**

页面状态：

```ts
const formData = reactive({
  name: '', email: '', password: '', confirmPassword: '', agreed: false,
});
const passwordPattern = /^(?=.*[A-Za-z])(?=.*\d).{8,}$/;
```

提交顺序固定为：NutUI 必填校验 → 密码规则 → 确认密码 → 协议确认 → `userStore.register` → `/member`。失败信息复用登录页现有的后端错误提取方式。

- [ ] **Step 4: 增加路由、双向导航和四份语言类型/文案**

新增路由：

```ts
{
  name: 'register',
  path: '/register',
  component: () => import('@/views/register/index.vue'),
  meta: { title: 'member.register', keepAlive: true },
}
```

给 `member` 增加 `register` 标题，给顶层语言接口增加 `register` 文案组。中文至少包含：`创建账号`、`姓名`、`邮箱`、`确认密码`、密码规则提示、确认密码不一致、请先同意用户协议、注册中、注册失败、已有账号、去登录。繁体中文和英文提供等义文案。

登录页底部增加按钮或文本链接到 `/register`；注册页链接到 `/login` 和 `/agreement`。

- [ ] **Step 5: 验证网页端并提交 Task 4**

Run:

```bash
pnpm test
pnpm typecheck
pnpm build
```

Expected: 全部 PASS，生产构建生成 `dist/`。

```bash
git add src/views/register src/views/login/index.vue src/router/routes.ts src/i18n/lang/lang-base.ts src/i18n/lang/zh-cn.ts src/i18n/lang/zh-tw.ts src/i18n/lang/en-us.ts
git commit -m "feat: add registration page"
```

---

### Task 5: 完整后端验证与移动端静态包同步

**Files:**
- Modify: `bitpongo-mobile/assets/web_bundle/**`

**Interfaces:**
- Consumes: 已提交并完成生产构建的 `bitpongo-front` 工作树。
- Produces: 清单带前端提交 SHA 的移动端内嵌网页包。

- [ ] **Step 1: 完整验证后端**

Run:

```bash
JAVA_HOME=/Users/zhangcong/Library/Java/JavaVirtualMachines/openjdk-26.0.1/Contents/Home mvn -Dmaven.repo.local=/Volumes/ExternalDrive/maven-repo/.m2/repository clean verify
```

Expected: `BUILD SUCCESS`，包括 MySQL Testcontainers 空库和旧库迁移测试。

- [ ] **Step 2: 检查后端工作树**

Run:

```bash
git diff --check
git status --short
```

Expected: `git diff --check` 无输出且 `git status --short` 为空。若存在遗漏，回到对应任务完成测试、精确暂存并提交；不得用 `git add .` 吸收无关文件。

- [ ] **Step 3: 从已提交的网页端构建并同步移动包**

在移动端隔离工作树运行：

```bash
FRONTEND_DIR=/private/tmp/bitpongo-front-local-registration ./scripts/build_web_bundle.sh
```

检查 `assets/web_bundle/manifest.json` 的 `frontendCommit` 必须等于网页端当前 HEAD，且 `git status --short` 只出现 `assets/web_bundle/**`。

- [ ] **Step 4: 验证移动端静态包和 Flutter 测试**

Run:

```bash
bash test/scripts/web_bundle_scripts_test.sh
flutter test
```

Expected: 全部 PASS。

- [ ] **Step 5: 提交移动端静态包**

```bash
git add assets/web_bundle
git commit -m "chore: bundle registration frontend"
```

- [ ] **Step 6: 最终三仓库审计**

分别运行：

```bash
git diff --check
git status --short
git log --oneline -5
```

确认隔离工作树干净，原始网页端和移动端用户修改仍原样保留。报告三个仓库的测试、提交和远端推送状态；未经再次选择集成方式，不自动合并或推送。
