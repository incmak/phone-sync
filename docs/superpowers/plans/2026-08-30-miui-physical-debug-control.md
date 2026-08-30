# MIUI-Compatible Physical Debug Control Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a secure debug-only shell-broadcast control transport so the existing `action-origin-kill-after-claim` scenario can run on MI 11X despite its ROM blocking Android `run-as`.

**Architecture:** Keep the existing token plus `run-as` adapter as the default. Add an explicit host adapter that sends the same closed commands to the exact debug receiver, relies on the receiver's `android.permission.DUMP` component permission, and receives a bounded base64url JSON result from the ordered broadcast. Fix the debug provider authority independently. No release, protocol, Room, LAN, relay, pairing, or UI code changes.

**Tech Stack:** Kotlin, Android `BroadcastReceiver`, Android instrumentation, Go 1.23, ADB, existing E2E controller and scenario executor.

## Global Constraints

- Work in the primary checkout; do not create a worktree.
- Preserve the user's existing `AGENTS.md` and `CLAUDE.md` changes.
- Add no dependency, framework, companion APK, fixed credential, general command endpoint, or secret transport.
- Shell mode accepts only `STATUS`, `NOTIFICATION_FIXTURE`, `NOTIFICATION_MIRROR`, and `NOTIFICATION_ORIGIN`.
- Shell mode rejects `token`, `auth_input_id`, and `secret_input_id`.
- The receiver and provider remain debug-only and absent from release artifacts.
- Do not clear data, unpair, or modify personal apps.
- Do not force-stop either phone until fresh operator approval at the exact scenario step.

---

### Task 1: Protect and return results from the Android debug receiver

**Files:**

- Modify: `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/e2e/E2eControlSecurityTest.kt`
- Modify: `mobile/modules/twinotify-core/android/src/debug/AndroidManifest.xml`
- Modify: `mobile/modules/twinotify-core/android/src/debug/java/co/twinotify/core/e2e/E2eControlReceiver.kt`
- Modify: `mobile/modules/twinotify-core/android/src/debug/java/co/twinotify/core/e2e/E2eStateProvider.kt`

**Interfaces:**

- Produces: `E2eControlReceiver.executeShellForTest(Context, E2eCommand): E2eCommandResult` as an internal security-test seam.
- Produces: ordered broadcast result data containing `Base64.getUrlEncoder().withoutPadding().encodeToString(resultJson)`.
- Preserves: `executeForTest`, token files, secret files, and all existing command implementations.

- [x] **Step 1: Write focused failing Android security tests**

Change the authority assertion and add one shell allowlist test:

```kotlin
@Test
fun debugComponentsUseShellPermissionAndApplicationAuthority() {
    val packageManager = context.packageManager
    val receiver = packageManager.getReceiverInfo(
        ComponentName(context, E2eControlReceiver::class.java),
        PackageManager.ComponentInfoFlags.of(0),
    )
    assertEquals(android.Manifest.permission.DUMP, receiver.permission)
    assertEquals("${context.packageName}.e2e", E2eStateProvider.AUTHORITY)
}

@Test
fun shellControlIsClosedAndRejectsEverySecretHandle() = runBlocking {
    val receiver = E2eControlReceiver()
    assertEquals("unauthorized", receiver.executeForTest(context, E2eCommand("r", "STATUS")).code)
    assertEquals("forbidden", receiver.executeShellForTest(context, E2eCommand("r", "PAIR_INIT")).code)
    for (command in listOf(
        E2eCommand("r", "STATUS", token = "forbidden"),
        E2eCommand("r", "STATUS", params = mapOf("auth_input_id" to "r")),
        E2eCommand("r", "STATUS", params = mapOf("secret_input_id" to "r")),
    )) {
        assertEquals("invalid", receiver.executeShellForTest(context, command).code)
    }
}
```

Update the exported-broadcast test helper to send through `UiAutomation.executeShellCommand(...)`, because the target app UID must no longer be able to invoke its own exported receiver without DUMP.

- [x] **Step 2: Run the Android test compile and observe RED**

Run:

```bash
cd mobile/android
./gradlew --no-daemon :app:compileDebugAndroidTestKotlin
```

Expected: FAIL because `executeShellForTest` does not exist and the manifest has neither the DUMP permission nor the correct provider authority.

- [x] **Step 3: Implement the minimal Android shell branch**

Set the manifest values:

```xml
<receiver
  android:name="co.twinotify.core.e2e.E2eControlReceiver"
  android:exported="true"
  android:permission="android.permission.DUMP">
```

```xml
android:authorities="${applicationId}.e2e"
```

Set `E2eStateProvider.AUTHORITY` to `com.twinotify.app.e2e`.

In `E2eControlReceiver`, add exactly this shell command set and validation shape:

```kotlin
private val SHELL_COMMANDS = setOf(
    "STATUS", "NOTIFICATION_FIXTURE", "NOTIFICATION_MIRROR", "NOTIFICATION_ORIGIN",
)

internal suspend fun executeShellForTest(context: Context, command: E2eCommand): E2eCommandResult {
    val requestId = safeRequestId(command.requestId)
    if (command.token != null || command.params.keys.any { it in setOf("auth_input_id", "secret_input_id") }) {
        return E2eCommandResult(requestId, "invalid", "private parameter unavailable")
    }
    if (command.name !in SHELL_COMMANDS) {
        return E2eCommandResult(requestId, "forbidden", "command is not shell-allowlisted")
    }
    validateOfflineParams(command)?.let { return E2eCommandResult(requestId, "invalid", it) }
    return try {
        executeAuthorized(context.applicationContext, command, requestId)
    } catch (_: Throwable) {
        E2eCommandResult(requestId, "error", "operation_failed")
    }
}
```

In `onReceive`, keep the current private-file path when `auth_input_id` exists. Otherwise call `executeShellForTest`, require `secretPayload == null`, encode `result.toJson().toString()` with base64url without padding, call `pending.setResultCode(Activity.RESULT_OK)` and `pending.setResultData(encoded)`, then finish. Preserve the current bounded error result on failure.

- [x] **Step 4: Run Android compile and focused tests GREEN**

Run:

```bash
cd mobile/android
./gradlew --no-daemon :app:compileDebugAndroidTestKotlin :twinotify-core:testDebugUnitTest :twinotify-core:lintDebug :app:assembleDebug
```

Expected: PASS. Inspect the debug merged manifest for the receiver DUMP permission and `${applicationId}`-resolved authority, then inspect the release manifest to confirm both components remain absent.

- [x] **Step 5: Commit Task 1**

```bash
git add mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/e2e/E2eControlSecurityTest.kt \
  mobile/modules/twinotify-core/android/src/debug/AndroidManifest.xml \
  mobile/modules/twinotify-core/android/src/debug/java/co/twinotify/core/e2e/E2eControlReceiver.kt \
  mobile/modules/twinotify-core/android/src/debug/java/co/twinotify/core/e2e/E2eStateProvider.kt
git commit -m "test(mobile): add DUMP-protected physical control"
```

---

### Task 2: Add the explicit Go shell-broadcast adapter

**Files:**

- Modify: `e2e/internal/adb/adb.go`
- Modify: `e2e/internal/adb/adb_test.go`
- Modify: `e2e/cmd/twinotify-e2e/main.go`
- Modify: `e2e/cmd/twinotify-e2e/main_test.go`
- Modify: `e2e/README.md`

**Interfaces:**

- Produces: `(*adb.Client).BroadcastReceiverResult(...) ([]byte, error)`.
- Produces: CLI `-control-transport run-as|shell-broadcast`, default `run-as`.
- Produces: `shellBroadcastDevice`, implementing only `control.Device` and `control.SecureRequestDevice`, never `control.SecretDevice`.

- [x] **Step 1: Write the focused failing Go tests**

Add one ADB test proving output is returned with the existing exact component arguments. Add one main-package test using:

```go
encoded := base64.RawURLEncoding.EncodeToString([]byte(`{"request_id":"request-shell","code":"ok","payload":{"paired":true}}`))
runner := &privateRunner{output: []byte("Broadcasting: Intent\nBroadcast completed: result=-1, data=\"" + encoded + "\"\n")}
device := newShellBroadcastDevice(adb.New(runner, "physical-a"), "com.twinotify.app")
err := device.Broadcast(context.Background(), control.Command{
    RequestID: "request-shell", Name: "STATUS", Token: "host-only-request-key",
})
```

Assert the decoded result is returned, argv contains neither `run-as`, `token`, nor `auth_input_id`, and no stdin input occurs. Add one failure-path test proving malformed and oversized result data are rejected. Add a CLI validation test proving shell mode rejects `pair` and `offline-pairing` before ADB.

- [x] **Step 2: Run focused Go tests and observe RED**

Run:

```bash
cd e2e
go test ./internal/adb ./cmd/twinotify-e2e -race -count=1
```

Expected: FAIL because the result-returning ADB method, shell adapter, and transport validation do not exist.

- [x] **Step 3: Implement the result-returning ADB call**

Refactor `broadcastArgs` to delegate to a result-returning helper without changing existing callers:

```go
func (c *Client) BroadcastReceiverResult(ctx context.Context, packageName, receiver, action string, extras map[string]string) ([]byte, error) {
    if !validComponentName(packageName) || !validComponentName(receiver) {
        return nil, errors.New("package and receiver must be valid Android component names")
    }
    args := []string{"shell", "am", "broadcast", "-n", shellQuote(packageName + "/" + receiver), "-a", action}
    return c.broadcastArgsResult(ctx, args, extras)
}
```

Sort and quote extras exactly as the existing helper does. Keep error redaction unchanged.

- [x] **Step 4: Implement shell adapter and CLI validation**

Add `shellBroadcastDevice` with a mutex-protected one-result map. Parse only:

```text
Broadcast completed: result=-1, data="<base64url>"
```

Reject non-`-1` result codes, missing data, characters outside base64url, encoded values larger than the 64 KiB decoded bound, malformed JSON, unknown JSON fields, trailing JSON, request-ID mismatch, or missing code. Store the validated decoded JSON by request ID and consume it once from `ReadResult`.

Generate a random host-only request key for `control.New`; never send it to Android. The shell adapter implements `BoundRequestID` using the existing `control.NewBoundRequestID` and does not implement secret interfaces.

Add and validate:

```go
controlTransport := flag.String("control-transport", "run-as", "debug control transport: run-as or shell-broadcast")
```

Construct the existing `adbDevice` after reading the install token for `run-as`. Construct `shellBroadcastDevice` without reading an Android token for `shell-broadcast`. Accept shell mode only for `status`, `notification-actions-correctness`, or names beginning `action-` that already pass scenario validation.

- [x] **Step 5: Document and run all host tests GREEN**

Document the physical-only flag, DUMP boundary, closed command set, and secret-scenario rejection in `e2e/README.md`.

Run:

```bash
cd e2e
gofmt -w internal/adb/adb.go internal/adb/adb_test.go cmd/twinotify-e2e/main.go cmd/twinotify-e2e/main_test.go
go test ./... -race -count=1
cd ..
make host-verify
git diff --check
```

Expected: PASS.

- [x] **Step 6: Commit Task 2**

```bash
git add e2e/internal/adb/adb.go e2e/internal/adb/adb_test.go \
  e2e/cmd/twinotify-e2e/main.go e2e/cmd/twinotify-e2e/main_test.go e2e/README.md
git commit -m "test(e2e): support MIUI shell control"
```

---

### Task 3: Verify the physical preflight and stop at the force-stop gate

**Files:** No source changes unless a focused verification exposes a defect.

**Interfaces:** Uses the existing `action-origin-kill-after-claim` scenario with `-control-transport shell-broadcast`.

- [x] **Step 1: Run fresh static and artifact verification**

Run focused Android tests, Go race tests, release-manifest verification, `git diff --check`, and `git status --short`. Confirm only the user's pre-existing instruction-file changes remain outside committed work.

- [x] **Step 2: Build one evidence-bound arm64 debug APK**

Run:

```bash
cd mobile/android
./gradlew --no-daemon :app:assembleDebug -PreactNativeArchitectures=arm64-v8a
```

Record SHA-256, install the same APK in place on MI 11X and POCO F1 through their live mDNS serials, and confirm pairing data survives. Use push plus `pm install -r` if streamed install drops the wireless route.

- [x] **Step 3: Run non-destructive physical control preflight**

Run `status` with `-control-transport shell-broadcast` on both phones. Confirm paired state and authenticated LAN. Arm `pause_after_claim`, verify the control returns `ok`, then release it immediately and verify cleanup. Do not invoke the fixture action and do not force-stop.

- [x] **Step 4: Begin the real scenario and pause for fresh approval**

Start `action-origin-kill-after-claim` with MI 11X as A and POCO F1 as B. Let it reset/post the fixed fixture, arm the pause, invoke mark-read, and observe exactly one new `CLAIMED` row. Immediately before its `A.force-stop` step, stop and request fresh user approval.

- [x] **Step 5: After approval, complete and audit the physical row**

Force-stop and restart MI 11X, wait at least 65 seconds, require POCO to reach `OUTCOME_UNKNOWN`, prove the mark-read fixture count is unchanged, cancel the fixed fixture, and require terminal convergence. Record only safe counters, routes, timestamps, APK hash, and commit.

Physical evidence recorded at `2026-08-30T14:26:52Z`:

- Application source commit: `d48a069`; arm64 debug APK SHA-256: `8a898a82763f2170a4d826202ae5beaee335b4ee29b457af4a9130422a01a9c2`.
- Origin: Xiaomi MI 11X, Android 16, build `BP4A.251205.006 release-keys`. Mirror: Xiaomi POCO F1, Android 15, build `BP1A.250505.005.D1 release-keys`. Both installed package version code 1 with the same APK.
- Pairing survived both in-place installs. Both devices reached authenticated LAN with zero queued rows. The shell-control preflight returned `armed`, then `released`, without invoking an action.
- The fixed mark-read fixture was reset and posted. Its dispatch count was 0. The real invocation advanced origin `execution_claimed` from 0 to 1 while the dispatch gate remained paused.
- After explicit operator approval, the origin was force-stopped and restarted. The mirror advanced `invocation_outcome_unknown` from 0 to 1 and presented `OUTCOME_UNKNOWN`; the origin completion journal advanced once while the fixture mark-read dispatch count remained 0.
- Cancelling the fixed fixture removed its action-bearing mirror from both active sets. Both active sets matched afterward, both routes remained authenticated LAN, and both queues were 0.
- Evidence contained counters and state only. No notification/reply content, device identifier, token, key, or pairing secret was recorded. The two exact `/data/local/tmp/twinotify-miui-control.apk` transfer copies were removed and verified absent; app data and pairing were not cleared.
- The point-by-point anti-slop audit found no UI impact: no screen, component, type, color, layout, icon, logo, motion, copy, accessibility control, or visual asset changed. The fresh release merged manifest contains no Twinotify E2E receiver, provider, control action, or authority.

- [x] **Step 6: Perform final anti-slop and scope audit**

Re-read the complete anti-slop law point by point. Confirm no UI or visual files changed, so no rendered UI regression was introduced. Confirm no debug-only component appears in release, no temporary control APK/file remains, and no user data or pairing was cleared.

---

## Completion Standard

Do not call this complete until the focused Android and Go gates pass, release exclusion is freshly verified, both phones pass shell-control preflight with the same APK hash, and the physical mid-claim row reaches `OUTCOME_UNKNOWN` without executing the fixture action after restart. The actual force-stop still requires fresh approval immediately before execution.
