# Mobile Web Frontend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `bitpongo` configurable inside the Flutter loopback server, expose a narrow native bridge adapter, and add the password-confirmed account deletion UI.

**Architecture:** Load a small runtime config script before Vue so the same static bundle can receive an absolute API URL from Flutter. Keep native integration behind a typed adapter and implement deletion as a normal authenticated HTTP flow that also works in a browser.

**Tech Stack:** Vue 3.5, TypeScript 5.9, Vite 7, Pinia 3, Vue Router 4, Axios 1.12, NutUI 4, Vitest, Vue Test Utils, pnpm.

## Global Constraints

- Work in `/Volumes/ExternalDrive/Code/github/bitpongo` and preserve existing browser behavior.
- Runtime `apiBaseUrl` wins over `VITE_URL_PREFIX`; browser deployments without runtime config keep using `VITE_URL_PREFIX`.
- The mobile embedded bundle must receive an absolute HTTP(S) API URL; production Flutter validates HTTPS.
- Native bridge calls are optional and feature-detected; the web app must run normally without Flutter.
- The account deletion screen must state that plans stop, exchange credentials are removed, and anonymous history remains.
- Never clear the current session until the backend confirms deletion.
- Use the existing Chinese UI style and NutUI components; do not introduce a second component library.
- Add test scripts rather than relying on auto-fixing lint commands for verification.
- Follow TDD and commit each independently passing task.

---

## File Map

- `public/app-config.js`: harmless browser default for the runtime configuration object.
- `index.html`: loads runtime config before the Vue entry.
- `types/env.d.ts`: types Vite and window runtime values.
- `src/config/runtime.ts`: validates and resolves runtime configuration.
- `src/utils/request/index.ts`: consumes the resolved API base URL.
- `src/mobile/bridge.ts`: typed, allowlisted Flutter bridge adapter.
- `src/api/index.ts`: account deletion call.
- `src/store/modules/user.ts`: delete-then-logout action.
- `src/views/member/account/index.vue`: account deletion explanation and password confirmation UI.
- `src/views/member/index.vue`: entry to account settings.
- `src/router/routes.ts`: account route.
- `src/**/*.spec.ts`: Vitest coverage for config, bridge, store, and view.
- `package.json`: deterministic `test` and `typecheck` scripts.

### Task 1: Add runtime API configuration

**Files:**
- Create: `public/app-config.js`
- Create: `src/config/runtime.ts`
- Create: `src/config/runtime.spec.ts`
- Modify: `index.html`
- Modify: `types/env.d.ts`
- Modify: `src/utils/request/index.ts`
- Modify: `package.json`

**Interfaces:**
- Produces: `resolveApiBaseUrl(runtimeValue: unknown, viteValue: unknown): string`.
- Produces: `runtimeConfig.apiBaseUrl: string`.

- [ ] **Step 1: Add deterministic test and typecheck scripts**

Add these scripts without changing the existing lint commands:

```json
"test": "vitest run",
"typecheck": "vue-tsc --noEmit -p tsconfig.app.json"
```

Run: `pnpm exec vitest --version && pnpm exec vue-tsc --version`

Expected: both commands print installed versions and exit successfully.

- [ ] **Step 2: Write failing runtime config tests**

```ts
import { describe, expect, it } from 'vitest';
import { resolveApiBaseUrl } from './runtime';

describe('resolveApiBaseUrl', () => {
  it('prefers a valid runtime URL', () => {
    expect(resolveApiBaseUrl('https://api.example.com', '/api')).toBe('https://api.example.com');
  });

  it('falls back to the Vite value', () => {
    expect(resolveApiBaseUrl(undefined, '/api')).toBe('/api');
  });

  it('rejects unsafe schemes', () => {
    expect(() => resolveApiBaseUrl('javascript:alert(1)', '/api')).toThrow('API 地址无效');
  });
});
```

Run: `pnpm test -- src/config/runtime.spec.ts`

Expected: FAIL because `runtime.ts` does not exist.

- [ ] **Step 3: Implement runtime config resolution**

Declare:

```ts
interface ZhitoubaoAppConfig { apiBaseUrl?: string }
interface Window { __ZHITOUBAO_APP_CONFIG__?: ZhitoubaoAppConfig }
```

Implement `resolveApiBaseUrl` to trim input and allow only absolute `http:`/`https:` URLs or a root-relative
path beginning with exactly one `/`. Export:

```ts
export const runtimeConfig = {
  apiBaseUrl: resolveApiBaseUrl(
    window.__ZHITOUBAO_APP_CONFIG__?.apiBaseUrl,
    import.meta.env.VITE_URL_PREFIX,
  ),
};
```

Create `public/app-config.js`:

```js
window.__ZHITOUBAO_APP_CONFIG__ = window.__ZHITOUBAO_APP_CONFIG__ || {};
```

Load `/app-config.js` in `index.html` before `/src/main.ts`, and set Axios `baseURL` to
`runtimeConfig.apiBaseUrl`.

- [ ] **Step 4: Run config tests and production build**

Run: `pnpm test -- src/config/runtime.spec.ts && pnpm typecheck && pnpm build`

Expected: PASS; `dist/app-config.js` exists and `dist/index.html` loads it before the hashed app entry.

- [ ] **Step 5: Commit**

```bash
git add package.json pnpm-lock.yaml public/app-config.js index.html types/env.d.ts \
  src/config/runtime.ts src/config/runtime.spec.ts src/utils/request/index.ts
git commit -m "feat: support runtime API configuration"
```

### Task 2: Add a typed native bridge adapter

**Files:**
- Create: `src/mobile/bridge.ts`
- Create: `src/mobile/bridge.spec.ts`
- Modify: `types/env.d.ts`

**Interfaces:**
- Produces: `getNativeContext(): Promise<NativeContext | null>`.
- Produces: `saveImage(request: ImageRequest): Promise<boolean>` and
  `shareImage(request: ImageRequest): Promise<boolean>`.
- Flutter channel name: `ZhitoubaoBridge`; payload envelope `{ version: 1, command, requestId, payload }`.

- [ ] **Step 1: Write failing adapter tests**

Use a fake channel with `postMessage(message: string)` and assert:

```ts
await getNativeContext();
expect(postMessage).toHaveBeenCalledWith(expect.stringContaining('"command":"getContext"'));
```

Also test that a missing channel returns `null` for context and `false` for save/share, and that both image
commands reject non-HTTP(S) URLs before posting.

Run: `pnpm test -- src/mobile/bridge.spec.ts`

Expected: FAIL because the adapter does not exist.

- [ ] **Step 2: Implement the narrow adapter**

Define:

```ts
export interface NativeContext {
  appVersion: string;
  platform: 'android' | 'ios';
  systemVersion: string;
  safeArea: { top: number; right: number; bottom: number; left: number };
}

export interface ImageRequest { url: string; title?: string }
```

Post only `getContext`, `saveImage`, and `shareImage` commands. Generate request IDs with
`crypto.randomUUID()` and expose
`window.__ZHITOUBAO_NATIVE_RESOLVE__(requestId, result)` for Flutter replies. Expire pending requests after 10
seconds and remove them from the map. Do not expose an arbitrary command function.

- [ ] **Step 3: Run bridge tests and typecheck**

Run: `pnpm test -- src/mobile/bridge.spec.ts && pnpm typecheck`

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/mobile/bridge.ts src/mobile/bridge.spec.ts types/env.d.ts
git commit -m "feat: add typed mobile bridge adapter"
```

### Task 3: Add account deletion API and store behavior

**Files:**
- Modify: `src/api/index.ts`
- Modify: `src/store/modules/user.ts`
- Create: `src/store/modules/user.spec.ts`

**Interfaces:**
- Produces: `deleteAccount(data: { password: string }): Promise<void>`.
- Produces: `userStore.deleteAccount(password: string): Promise<void>`; logout happens only after success.

- [ ] **Step 1: Write failing store tests**

Mock `@/api` and create a Pinia test store. Assert:

```ts
await store.deleteAccount('secret');
expect(api.deleteAccount).toHaveBeenCalledWith({ password: 'secret' });
expect(store.token).toBe('');
expect(store.info).toEqual({});
```

For a rejected API promise, assert the original token and info remain unchanged.

Run: `pnpm test -- src/store/modules/user.spec.ts`

Expected: FAIL because neither API nor store action exists.

- [ ] **Step 2: Implement the API and action**

Add to `src/api/index.ts`:

```ts
export function deleteAccount(data: { password: string }): Promise<void> {
  return http.delete('/users/account', { data });
}
```

Import it in the store and implement:

```ts
async deleteAccount(password: string) {
  await deleteAccount({ password });
  this.logout();
}
```

- [ ] **Step 3: Run store tests**

Run: `pnpm test -- src/store/modules/user.spec.ts && pnpm typecheck`

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/api/index.ts src/store/modules/user.ts src/store/modules/user.spec.ts
git commit -m "feat: add account deletion client"
```

### Task 4: Build the account deletion screen

**Files:**
- Create: `src/views/member/account/index.vue`
- Create: `src/views/member/account/index.spec.ts`
- Modify: `src/views/member/index.vue`
- Modify: `src/router/routes.ts`

**Interfaces:**
- Consumes: `userStore.deleteAccount(password)` from Task 3.
- Produces: route name `memberAccount`, path `/member/account`.

- [ ] **Step 1: Write failing component tests**

Mount the view with Pinia and router mocks. Test that it renders all three consequences, disables confirmation
when the password is blank, keeps the page/session on rejection, and navigates to `/login` only after the store
action resolves.

```ts
expect(wrapper.text()).toContain('停止全部运行中的策略');
expect(wrapper.text()).toContain('删除交易所 API 密钥');
expect(wrapper.text()).toContain('匿名保留历史记录');
```

Run: `pnpm test -- src/views/member/account/index.spec.ts`

Expected: FAIL because the view does not exist.

- [ ] **Step 2: Implement the page**

Use NutUI cells, password input, checkbox, and button. Require both nonblank password and an explicit checkbox.
On submit show a second dialog titled `确认注销账号`; call the store only from `onOk`. Show a loading state to
prevent duplicate requests. On failure show the returned safe message and leave the password field available for
retry. On success replace the route with `/login`.

- [ ] **Step 3: Add route and member entry**

Add route metadata with border/back navigation and an `账号设置` cell in `src/views/member/index.vue`. Keep
`退出登录` as a separate action. Do not put deletion in the logout dialog.

- [ ] **Step 4: Run component and regression checks**

Run: `pnpm test -- src/views/member/account/index.spec.ts src/store/modules/user.spec.ts && pnpm typecheck`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/views/member/account src/views/member/index.vue src/router/routes.ts
git commit -m "feat: add in-app account deletion flow"
```

### Task 5: Verify the mobile web deliverable

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: Tasks 1-4.
- Produces: documented runtime configuration and embedded-build contract for Flutter.

- [ ] **Step 1: Document the runtime contract**

Document `window.__ZHITOUBAO_APP_CONFIG__.apiBaseUrl`, the default `/app-config.js`, the three bridge commands,
the account deletion route, and the requirement that embedded builds use an absolute API URL.

- [ ] **Step 2: Run all tests and static checks**

Run: `pnpm test && pnpm typecheck && pnpm exec eslint "src/**/*.{ts,vue}" --max-warnings 0`

Expected: PASS without auto-fixing files.

- [ ] **Step 3: Build and inspect the embedded artifact**

Run: `pnpm build && test -f dist/index.html && test -f dist/app-config.js`

Expected: PASS; built HTML references `/app-config.js` before the application entry and contains no real API
credentials.

- [ ] **Step 4: Inspect the final diff**

Run: `git diff --check && git status --short`

Expected: no whitespace errors, generated `dist` remains ignored unless the repository already tracks it, and no
unrelated dependency upgrades.

- [ ] **Step 5: Commit documentation**

```bash
git add README.md
git commit -m "docs: document mobile web integration"
```
