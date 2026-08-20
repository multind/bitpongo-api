# Flutter Mobile Shell Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create `/Volumes/ExternalDrive/Code/github/bitpongo-mobile` as a production-oriented Flutter app that loads remote `bitpongo` first and safely falls back to a bundled build.

**Architecture:** A pure configuration/navigation layer feeds a small WebView shell state machine. A loopback-only HTTP server serves the bundled Vue history-mode app and injects runtime API configuration; a narrow, origin-checked bridge owns native context, image saving, and sharing.

**Tech Stack:** Current Flutter stable and Dart, `flutter_inappwebview`, `connectivity_plus`, `url_launcher`, `http`, `share_plus`, `gal`, `path_provider`, `permission_handler`, `package_info_plus`, `crypto`, `mime`, Flutter Test, Integration Test.

## Global Constraints

- Project directory: `/Volumes/ExternalDrive/Code/github/bitpongo-mobile`.
- App name: `智投宝`; Dart package: `bitpongo-mobile`.
- Android applicationId and namespace: `com.multind.bitpongo`.
- iOS Bundle ID: `com.multind.bitpongo`.
- iOS deployment target: 15.0.
- Android minSdk: 26; targetSdk uses the latest value generated/required by the current Flutter stable toolchain.
- Resolve every direct Flutter dependency to the latest stable mutually compatible version at scaffold time; commit `pubspec.lock`.
- Do not copy Firebase, GetX, social login, scanner, notification, analytics, or permissive network settings from the reference project.
- Release builds require HTTPS `API_BASE_URL`; `WEB_BASE_URL` is optional but must be HTTPS when present.
- HTTP is allowed only for the loopback bundle and explicit debug development endpoints.
- The JavaScript bridge is available only to the configured remote origin and the active loopback origin.
- Never bypass TLS errors or enable global iOS/Android cleartext access.
- Support remote-first load, at most one automatic fallback per app launch, manual remote retry, and bundled-only operation.
- Follow TDD and commit after each independently passing task.

---

## File Map

- `lib/main.dart`: Flutter entry point and portrait/system UI setup.
- `lib/app/zhitoubao_app.dart`: Material app theme and shell root.
- `lib/config/app_config.dart`: compile-time environment parsing and release validation.
- `lib/web/navigation_policy.dart`: trusted, external, and blocked URL decisions.
- `lib/web/shell_state.dart`: immutable load source/phase/error state.
- `lib/web/load_coordinator.dart`: remote-first and single-fallback transitions.
- `lib/web/web_shell_page.dart`: InAppWebView integration and native overlays.
- `lib/web/native_bridge.dart`: envelope validation, origin verification, native context, save/share commands.
- `lib/services/local_web_server.dart`: loopback server, SPA fallback, and runtime config injection.
- `lib/services/network_status.dart`: connectivity signal abstraction.
- `lib/services/image_service.dart`: bounded trusted image download, save, and share.
- `lib/services/app_context_service.dart`: version/platform/safe-area payload.
- `assets/web_bundle`: committed fallback H5 output plus manifest.
- `scripts/build_web_bundle.sh`: builds sibling frontend and invokes sync.
- `scripts/sync_web_bundle.sh`: transactional artifact replacement and manifest generation.
- `scripts/verify.sh`: format, analyze, test, bundle, and platform-build checks.
- `test/**`: unit and widget tests.
- `integration_test/web_shell_test.dart`: remote failure and bundled fallback smoke flow.
- `android/app/src/**`: identity, scoped network security, permissions, launcher and splash assets.
- `ios/Runner/**`: identity, local networking exception, privacy strings, icons, and launch screen.
- `README.md`: local run, bundle sync, release build, and store checklist.

### Task 1: Scaffold the repository and lock current stable dependencies

**Files:**
- Create: entire Flutter scaffold at `/Volumes/ExternalDrive/Code/github/bitpongo-mobile`
- Modify: `pubspec.yaml`
- Create: `.metadata`, `.gitignore`, `analysis_options.yaml`, `pubspec.lock`

**Interfaces:**
- Produces: a clean Flutter package named `bitpongo-mobile` with Android/iOS platforms.

- [ ] **Step 1: Verify the Flutter toolchain**

Run: `flutter --version && flutter doctor -v`

Expected: stable channel is reported. Record the exact Flutter/Dart versions in the future README. Resolve any
missing Android/iOS build tooling before dependency selection; a missing physical device is acceptable.

- [ ] **Step 2: Create the project and Git repository**

Run:

```bash
flutter create --platforms=android,ios --org com.multind \
  --project-name bitpongo-mobile /Volumes/ExternalDrive/Code/github/bitpongo-mobile
git -C /Volumes/ExternalDrive/Code/github/bitpongo-mobile init
```

Expected: default counter app builds and the repository has no parent repository capture.

- [ ] **Step 3: Resolve only required latest stable packages**

Run from the new repository:

```bash
flutter pub add flutter_inappwebview connectivity_plus url_launcher http share_plus gal \
  path_provider permission_handler package_info_plus crypto mime
flutter pub add --dev flutter_lints flutter_native_splash flutter_launcher_icons
flutter pub get
flutter pub outdated
```

Expected: direct packages show no newer stable compatible release. If a newest release is incompatible with the
current Flutter stable, keep the newest resolver-compatible release and record that fact in README.

- [ ] **Step 4: Replace the sample test with a failing app identity smoke test**

Create `test/app/zhitoubao_app_test.dart`:

```dart
testWidgets('renders the 智投宝 shell title', (tester) async {
  await tester.pumpWidget(const ZhitoubaoApp());
  expect(find.text('智投宝'), findsOneWidget);
});
```

Run: `flutter test test/app/zhitoubao_app_test.dart`

Expected: FAIL because `ZhitoubaoApp` does not exist.

- [ ] **Step 5: Add the minimal app root**

Create `lib/app/zhitoubao_app.dart` with a `MaterialApp` titled `智投宝`, no debug banner, and a temporary
`Scaffold(body: Center(child: Text('智投宝')))`. Make `lib/main.dart` call `runApp(const ZhitoubaoApp())` and
lock preferred orientations to portrait up/down.

- [ ] **Step 6: Run baseline checks and commit**

Run: `dart format . && flutter analyze && flutter test`

Expected: PASS.

```bash
git add .
git commit -m "chore: scaffold zhitoubao mobile app"
```

### Task 2: Implement configuration and navigation policy

**Files:**
- Create: `lib/config/app_config.dart`
- Create: `lib/web/navigation_policy.dart`
- Create: `test/config/app_config_test.dart`
- Create: `test/web/navigation_policy_test.dart`

**Interfaces:**
- Produces: `AppConfig.fromEnvironment({bool isRelease = kReleaseMode})`.
- Produces: `Uri AppConfig.apiBaseUri`, `Uri? AppConfig.webBaseUri`, `Duration loadTimeout`.
- Produces: `NavigationAction NavigationPolicy.decide(Uri uri)` where action is `allow`, `external`, or `block`.

- [ ] **Step 1: Write failing configuration tests**

Test a constructor/factory seam that accepts strings:

```dart
expect(AppConfig.parse(api: 'https://api.example.com', web: ''),
    hasWebBase(false));
expect(() => AppConfig.parse(api: 'http://api.example.com', web: '', isRelease: true),
    throwsFormatException);
expect(() => AppConfig.parse(api: 'https://api.example.com', web: 'javascript:x'),
    throwsFormatException);
```

Also assert release allows an omitted web URL but never an omitted API URL.

Run: `flutter test test/config/app_config_test.dart`

Expected: FAIL because `AppConfig` does not exist.

- [ ] **Step 2: Implement immutable configuration**

Use `String.fromEnvironment('API_BASE_URL')` and `String.fromEnvironment('WEB_BASE_URL')`. Normalize trailing
slashes, require absolute HTTP(S), require HTTPS in release, and use a 15-second main-frame timeout. Do not add a
`.env` parser or commit environment values.

- [ ] **Step 3: Write failing navigation policy tests**

Cover exact-origin remote routes, active loopback routes, external HTTPS, `mailto`, `tel`, and blocked schemes:

```dart
expect(policy.decide(Uri.parse('https://app.example.com/member')),
    NavigationAction.allow);
expect(policy.decide(Uri.parse('https://multind.com/help')),
    NavigationAction.external);
expect(policy.decide(Uri.parse('javascript:alert(1)')),
    NavigationAction.block);
```

Run: `flutter test test/web/navigation_policy_test.dart`

Expected: FAIL because the policy does not exist.

- [ ] **Step 4: Implement exact-origin policy and verify**

Allow only matching scheme/host/effective port for `webBaseUri`, plus the exact loopback scheme/host/port supplied
after server startup. Mark external HTTPS, `mailto`, and `tel` as external; block every other scheme.

Run: `flutter test test/config test/web/navigation_policy_test.dart && flutter analyze`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add lib/config lib/web/navigation_policy.dart test/config test/web/navigation_policy_test.dart
git commit -m "feat: add secure mobile configuration"
```

### Task 3: Serve the bundled history-mode app on loopback

**Files:**
- Create: `lib/services/local_web_server.dart`
- Create: `test/services/local_web_server_test.dart`
- Create: `assets/web_bundle/index.html`
- Create: `assets/web_bundle/app-config.js`
- Modify: `pubspec.yaml`

**Interfaces:**
- Produces: `Future<Uri> LocalWebServer.start(Uri apiBaseUri)`.
- Produces: `Future<void> LocalWebServer.stop()`.
- Produces: `AssetReader.load(String assetKey): Future<Uint8List>` for test injection.

- [ ] **Step 1: Write failing loopback server tests**

With an in-memory `AssetReader`, assert:

- `/` serves `index.html` as UTF-8 HTML.
- `/assets/app.js` serves the exact bytes and JavaScript MIME.
- `/member/account` falls back to `index.html`.
- `/missing.png` returns 404 instead of HTML.
- encoded `..` path traversal returns 400.
- `/app-config.js` contains JSON-escaped `https://api.example.com` and no other environment value.
- server address is loopback and port is nonzero.

Run: `flutter test test/services/local_web_server_test.dart`

Expected: FAIL because the server does not exist.

- [ ] **Step 2: Implement the server**

Bind `HttpServer.bind(InternetAddress.loopbackIPv4, 0, shared: false)`. Normalize and decode each path once;
reject NUL, backslash, or any `..` segment. Serve known extensions from `assets/web_bundle`, use the `mime`
package for content type, set `X-Content-Type-Options: nosniff`, and use `Cache-Control: no-store` for HTML and
runtime config. Use SPA fallback only when the final path segment has no dot.

Generate runtime configuration as:

```js
window.__ZHITOUBAO_APP_CONFIG__={"apiBaseUrl":"https://api.example.com"};
```

using `jsonEncode`, never string interpolation inside JavaScript quotes.

- [ ] **Step 3: Add a minimal committed fallback fixture and asset declaration**

Declare `assets/web_bundle/` recursively in `pubspec.yaml`. The temporary HTML must load `/app-config.js` and
show `智投宝正在准备离线内容`; Task 7 replaces it with the real frontend build.

- [ ] **Step 4: Run server tests and commit**

Run: `dart format . && flutter test test/services/local_web_server_test.dart && flutter analyze`

Expected: PASS.

```bash
git add lib/services/local_web_server.dart test/services/local_web_server_test.dart \
  assets/web_bundle pubspec.yaml pubspec.lock
git commit -m "feat: serve bundled web app on loopback"
```

### Task 4: Implement remote-first loading and single fallback

**Files:**
- Create: `lib/services/network_status.dart`
- Create: `lib/web/shell_state.dart`
- Create: `lib/web/load_coordinator.dart`
- Create: `test/web/load_coordinator_test.dart`

**Interfaces:**
- Produces: enums `WebSource { remote, bundled }`, `ShellPhase { starting, loading, ready, error }`.
- Produces: `ShellState` with source, phase, target, progress, fallbackUsed, and safe error code.
- Produces: `LoadCoordinator.initialTarget(bool hasNetwork)` and transition methods `started`, `progressed`,
  `succeeded`, `mainFrameFailed`, `retryRemote`.

- [ ] **Step 1: Write failing state-machine tests**

Cover:

```dart
expect(coordinator.initialTarget(hasNetwork: true).source, WebSource.remote);
expect(coordinator.mainFrameFailed(remoteState, LoadError.timeout).source,
    WebSource.bundled);
expect(coordinator.mainFrameFailed(bundledState, LoadError.http).phase,
    ShellPhase.error);
expect(coordinator.mainFrameFailed(subresourceFailure, LoadError.http),
    same(remoteState));
```

Also assert missing remote URL and no network start bundled, API/subresource errors never trigger fallback, and
manual retry is the only transition from bundled back to remote during one launch.

Run: `flutter test test/web/load_coordinator_test.dart`

Expected: FAIL because the state types do not exist.

- [ ] **Step 2: Implement immutable transitions**

Keep all policy in `LoadCoordinator`; do not import Flutter widgets or WebView plugin types. Clamp progress to
0..1. Use safe error enum values `offline`, `timeout`, `http`, `tls`, `render`, `bundle`, never raw URLs or
credentials in state.

Implement `NetworkStatus` as a small wrapper over `connectivity_plus`; connectivity only selects the initial
target and enables a retry button, never declares the backend healthy.

- [ ] **Step 3: Run state tests and commit**

Run: `dart format . && flutter test test/web/load_coordinator_test.dart && flutter analyze`

Expected: PASS.

```bash
git add lib/services/network_status.dart lib/web/shell_state.dart lib/web/load_coordinator.dart \
  test/web/load_coordinator_test.dart
git commit -m "feat: add remote fallback state machine"
```

### Task 5: Add the trusted native bridge and bounded image service

**Files:**
- Create: `lib/services/app_context_service.dart`
- Create: `lib/services/image_service.dart`
- Create: `lib/web/native_bridge.dart`
- Create: `test/services/image_service_test.dart`
- Create: `test/web/native_bridge_test.dart`

**Interfaces:**
- Consumes: trusted origins from `NavigationPolicy`.
- Produces: bridge commands `getContext`, `saveImage`, `shareImage` with envelope version `1`.
- Produces: `ImageService.fetch(Uri, {int maxBytes = 15728640})`, `saveImage`, and `shareImage`.

- [ ] **Step 1: Write failing image validation tests**

Using a fake `http.Client`, test rejection of HTTP status errors, missing/unsupported MIME, bodies above 15 MiB,
non-HTTP(S) URLs, and a successful PNG download whose extension comes from validated MIME rather than URL text.

Run: `flutter test test/services/image_service_test.dart`

Expected: FAIL because `ImageService` does not exist.

- [ ] **Step 2: Implement bounded image save/share behavior**

Accept only `image/jpeg`, `image/png`, and `image/webp`. Stream bytes and abort above 15 MiB. Save a temporary
file under `getTemporaryDirectory`; call `Gal.putImage` only after the permission path is granted, and use the
current `share_plus` API to share one `XFile`. Always delete temporary files in `finally` after sharing.

- [ ] **Step 3: Write failing bridge tests**

Assert malformed JSON, messages above 16 KiB, wrong version, unknown command, untrusted current URL, unsafe image
URL, and duplicate request IDs are rejected. Assert `getContext` returns exactly:

```json
{"appVersion":"1.0.0","platform":"ios","systemVersion":"18.0","safeArea":{"top":0,"right":0,"bottom":0,"left":0}}
```

with test-controlled values. Assert replies call only
`window.__ZHITOUBAO_NATIVE_RESOLVE__(requestId, result)` using JSON-encoded arguments.

Run: `flutter test test/web/native_bridge_test.dart`

Expected: FAIL because the bridge does not exist.

- [ ] **Step 4: Implement bridge validation and context service**

Before every command, read the current main-frame URL and require an exact trusted origin. Parse envelope keys
`version`, `command`, `requestId`, `payload`; allow only the three commands. `AppContextService` uses
`package_info_plus`, `Platform`, and injected `MediaQueryData` to build the typed response. Log only command name,
result category, and duration.

- [ ] **Step 5: Run tests and commit**

Run: `dart format . && flutter test test/services/image_service_test.dart test/web/native_bridge_test.dart && flutter analyze`

Expected: PASS.

```bash
git add lib/services/app_context_service.dart lib/services/image_service.dart lib/web/native_bridge.dart \
  test/services/image_service_test.dart test/web/native_bridge_test.dart pubspec.lock
git commit -m "feat: add secure native bridge"
```

### Task 6: Integrate the WebView shell UI

**Files:**
- Create: `lib/web/web_shell_page.dart`
- Create: `test/web/web_shell_page_test.dart`
- Modify: `lib/app/zhitoubao_app.dart`
- Modify: `lib/main.dart`

**Interfaces:**
- Consumes: AppConfig, LocalWebServer, LoadCoordinator, NavigationPolicy, NativeBridge, NetworkStatus.
- Produces: the user-visible remote/bundled WebView experience.

- [ ] **Step 1: Write failing widget tests with an injected WebView surface**

Avoid platform views in widget tests by injecting a `WebSurfaceBuilder`. Test loading progress, remote error
fallback, bundled error UI, `重试线上版本`, `继续使用内置版本`, and app title semantics.

```dart
expect(find.byType(LinearProgressIndicator), findsOneWidget);
expect(find.text('无法加载智投宝'), findsOneWidget);
expect(find.text('重试线上版本'), findsOneWidget);
```

Run: `flutter test test/web/web_shell_page_test.dart`

Expected: FAIL because `WebShellPage` does not exist.

- [ ] **Step 2: Build the WebView controller integration**

Configure `InAppWebView` with JavaScript enabled, DOM storage enabled, third-party cookies disabled where the
platform permits, zoom disabled, safe-area background, and pull-to-refresh controller. Wire main-frame start,
progress, success, HTTP error, network error, render-process failure, and server-trust challenge to the state
machine. Never call `proceed` for an invalid certificate.

Use `shouldOverrideUrlLoading` with `NavigationPolicy`; open external actions through `url_launcher`. Register
only channel `ZhitoubaoBridge` and pass messages to `NativeBridge`. Wire `onDownloadStartRequest` to the same
bounded `ImageService`: accepted images present explicit save/share actions; unsupported content opens externally
only when its HTTPS URL passes `NavigationPolicy`.

- [ ] **Step 3: Implement navigation and lifecycle behavior**

Use `PopScope`: if WebView can go back, navigate back; otherwise allow app exit. Do not reload on lifecycle
resume. Stop the loopback server in `dispose`. Add a 15-second timer for the main document only and cancel it on
success/fallback/dispose. Pull-to-refresh reloads the active source and ends on every success/failure path.

- [ ] **Step 4: Replace the temporary app body and run widget tests**

Make `ZhitoubaoApp` construct `WebShellPage` from `AppConfig.fromEnvironment()`. Keep the app title available as
a semantic label in loading/error states.

Run: `dart format . && flutter test test/web/web_shell_page_test.dart && flutter analyze`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add lib/main.dart lib/app lib/web/web_shell_page.dart test/web/web_shell_page_test.dart
git commit -m "feat: integrate mobile web shell"
```

### Task 7: Build and synchronize the real frontend bundle

**Files:**
- Create: `scripts/build_web_bundle.sh`
- Create: `scripts/sync_web_bundle.sh`
- Create: `test/scripts/web_bundle_scripts_test.sh`
- Replace: `assets/web_bundle/**`
- Create: `assets/web_bundle/manifest.json`

**Interfaces:**
- Consumes: sibling frontend runtime config contract and `pnpm build`.
- Produces: atomic `assets/web_bundle` with `index.html`, `app-config.js`, assets, and manifest.

- [ ] **Step 1: Write a failing shell-level script test**

Create a temporary fake dist with `index.html`, `app-config.js`, and `assets/app.js`. Run the sync script and
assert stale files are removed, required files remain, and `manifest.json` contains `frontendCommit`,
`builtAtUtc`, and SHA-256 entries. Also pass a dist without `index.html` and assert the current target is unchanged.

Run: `bash test/scripts/web_bundle_scripts_test.sh`

Expected: FAIL because scripts do not exist.

- [ ] **Step 2: Implement transactional synchronization**

`sync_web_bundle.sh` must use `set -euo pipefail`, resolve absolute source/target paths, validate both required
files, copy to `mktemp -d` under `assets`, delete `.DS_Store`, generate deterministic sorted SHA-256 entries, and
rename the prepared directory into place only after all checks pass. Never use an unresolved glob as a deletion
target.

- [ ] **Step 3: Implement sibling frontend build**

Default frontend path to `../bitpongo`, require `pnpm`, run `pnpm install --frozen-lockfile` only when
`node_modules` is absent, then `pnpm build` and call the sync script with `dist`. Record
`git -C "$FRONTEND_DIR" rev-parse HEAD` in the manifest; do not require a clean frontend worktree merely to run
the script, but print a warning if dirty.

- [ ] **Step 4: Run script tests and sync the real bundle**

Run: `bash test/scripts/web_bundle_scripts_test.sh && ./scripts/build_web_bundle.sh`

Expected: PASS; `assets/web_bundle/index.html`, `app-config.js`, hashed assets, and manifest exist.

- [ ] **Step 5: Prove SPA and runtime injection through the Dart server**

Run: `flutter test test/services/local_web_server_test.dart`

Expected: PASS with the real asset declaration; `/member/account` returns the built index and `/app-config.js`
is generated by the server at runtime.

- [ ] **Step 6: Commit**

```bash
git add scripts test/scripts assets/web_bundle pubspec.yaml
git commit -m "feat: bundle zhitoubao web frontend"
```

### Task 8: Harden Android and iOS platform configuration

**Files:**
- Modify: `android/app/build.gradle.kts`
- Modify: `android/app/src/main/AndroidManifest.xml`
- Create: `android/app/src/main/res/xml/network_security_config.xml`
- Modify: `android/app/src/debug/AndroidManifest.xml`
- Modify: `ios/Podfile`
- Modify: `ios/Runner/Info.plist`
- Modify: `ios/Runner.xcodeproj/project.pbxproj`
- Modify/Create: platform icon and launch assets generated from `assets/branding/app_icon.png`
- Create: `assets/branding/app_icon.png`
- Create: `assets/branding/splash.png`
- Modify: `pubspec.yaml`

**Interfaces:**
- Produces: store-ready identity, platform floors, scoped permissions, icons, and splash screens.

- [ ] **Step 1: Configure identity and version floors**

Set Android namespace/applicationId to `com.multind.bitpongo`, minSdk 26, display name `智投宝`, and portrait
orientation. Set every iOS build configuration product bundle identifier to `com.multind.bitpongo`, deployment
target and Podfile platform to 15.0, and display name `智投宝`.

- [ ] **Step 2: Add only required permissions and scoped network rules**

Android main manifest: internet, network state, and media permissions required by the resolved `gal` package for
supported OS versions. The release network security config has `cleartextTrafficPermitted=false` by default and
permits cleartext only for `127.0.0.1`/`localhost`. Debug manifest may additionally permit local LAN development.

iOS: add photo-library add usage text and `NSAllowsLocalNetworking=true`; do not add
`NSAllowsArbitraryLoads`. Add camera/photo selection descriptions only if WebView file selection testing proves
the existing frontend invokes them.

- [ ] **Step 3: Generate branding assets**

Create a 1024×1024 opaque master from the existing square orange-and-white
`bitpongo/src/assets/logo.png`; because the mark is flat artwork, resize it once from the source and inspect
the 1024 image at full size for edge artifacts. Save it as `assets/branding/app_icon.png`, create the splash image
from the same opaque master, configure latest `flutter_launcher_icons` with `remove_alpha_ios: true` and
`flutter_native_splash`, then run:

```bash
sips -z 1024 1024 ../bitpongo/src/assets/logo.png --out assets/branding/app_icon.png
cp assets/branding/app_icon.png assets/branding/splash.png
dart run flutter_launcher_icons
dart run flutter_native_splash:create
```

Inspect every iOS/Android generated icon set for missing slots and transparent-background store violations.

- [ ] **Step 4: Run platform metadata checks**

Run: `flutter analyze && flutter test && flutter build apk --debug --dart-define=API_BASE_URL=http://10.0.2.2:8000`

Expected: PASS; generated manifest contains the exact applicationId and no global arbitrary cleartext flag.

- [ ] **Step 5: Commit**

```bash
git add android ios assets/branding pubspec.yaml pubspec.lock
git commit -m "chore: configure mobile platforms"
```

### Task 9: Add integration tests, release scripts, and operator documentation

**Files:**
- Create: `integration_test/web_shell_test.dart`
- Create: `scripts/verify.sh`
- Modify: `README.md`

**Interfaces:**
- Consumes: completed shell from Tasks 1-8.
- Produces: repeatable local/release verification and store handoff instructions.

- [ ] **Step 1: Add an integration-test server seam**

Use a local test HTTP server whose `/` can return success or close the connection. Test remote success remains
remote, remote failure loads the bundled marker, and external navigation calls an injected launcher instead of
changing the WebView main frame.

Boot exactly one iOS simulator, then run:

```bash
flutter devices
flutter test integration_test/web_shell_test.dart -d ios
```

Expected: PASS on at least one Android emulator or iOS simulator. Record the actual device ID in the verification
log, not in source.

- [ ] **Step 2: Create the verification script**

`scripts/verify.sh` runs, in order:

```bash
dart format --output=none --set-exit-if-changed .
flutter analyze
flutter test
bash test/scripts/web_bundle_scripts_test.sh
```

With `VERIFY_PLATFORM_BUILDS=1`, additionally build Android AAB and iOS simulator using an HTTPS example API
define. The script exits on first failure and never supplies signing secrets.

- [ ] **Step 3: Write README run and release instructions**

Document exact toolchain versions, `--dart-define` usage, Android emulator host `10.0.2.2`, iOS simulator host,
bundle synchronization, manual remote retry, release AAB command, unsigned iOS build command, signing ownership,
privacy strings, demo-account requirement, IPv6-only test, account deletion review notes, and the fact that no
production URL currently exists.

- [ ] **Step 4: Run complete verification**

Run:

```bash
./scripts/verify.sh
flutter build appbundle --release --dart-define=API_BASE_URL=https://api.example.invalid
flutter build ios --simulator --release --dart-define=API_BASE_URL=https://api.example.invalid
flutter pub outdated
git diff --check
```

Expected: all checks pass; direct dependencies have no newer stable compatible versions. If local Xcode or
Android SDK blocks a platform build, report that build separately and do not describe delivery as fully verified.

- [ ] **Step 5: Inspect the final repository**

Run: `git status --short && git log --oneline --decorate -12`

Expected: only intentional uncommitted generated build directories are ignored; no signing files, API secrets,
real production URLs, or reference-project credentials are present.

- [ ] **Step 6: Commit**

```bash
git add integration_test scripts/verify.sh README.md
git commit -m "test: verify mobile release workflow"
```
