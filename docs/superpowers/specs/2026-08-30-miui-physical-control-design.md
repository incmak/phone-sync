# MIUI-Compatible Physical Debug Control Design

**Date:** 2026-08-30

**Status:** Approved in conversation; written review pending

**Scope:** A secure, debug-only host control path for the remaining mirrored-notification action verification on the Xiaomi MI 11X and POCO F1 when the target ROM cannot execute Android `run-as`.

**Depends on:** The existing authenticated E2E controller, fixed notification-action fixture, mirrored-notification action scenarios, and `action-origin-kill-after-claim` scenario.

## 1. Goal

Make the existing `action-origin-kill-after-claim` scenario controllable on the MI 11X without weakening the production app or exposing a general remote-control surface.

The physical path must let the host:

1. arm the existing `pause_after_claim` debug seam on the origin;
2. invoke the fixed mark-read action from the mirror;
3. observe one durable `CLAIMED` execution row through content-free counters;
4. stop and restart the origin only after fresh operator approval;
5. observe `OUTCOME_UNKNOWN` after the 60-second recovery window; and
6. prove the fixture action was not dispatched after restart.

## 2. Confirmed Root Causes

The failure is in the host debug transport, not notification mirroring, direct LAN, pairing, or the lock screen.

### 2.1 MI 11X cannot use `run-as`

With the same-signature debug APK installed and `DEBUGGABLE` present, MI 11X returns:

```text
seapp_context_lookup_internal: No match for app ... fromRunAs
selinux_android_setcontext: Error setting context ... Permission denied
run-as: couldn't set SELinux security context
```

POCO F1 successfully runs the same `run-as com.twinotify.app id` command. Unlocking a phone can restore wireless ADB, but cannot change this SELinux policy result.

The current Go controller requires `run-as` for token bootstrap, per-command authentication files, command results, and one-time secret files. It therefore cannot reach even content-free action controls on the MI 11X.

### 2.2 Debug provider authority is inconsistent

The debug manifest registers:

```text
co.twinotify.app.e2e
```

while `E2eStateProvider.stateUri(context)` and `query()` require:

```text
com.twinotify.app.e2e
```

Querying the registered authority reaches the provider but fails its own authority check. This is an independent debug-harness defect and must be corrected to `${applicationId}.e2e`.

## 3. Non-Goals

- Do not change release behavior, production permissions, notification protocol, action execution, Room, pairing, LAN, relay, or UI.
- Do not replace the existing `run-as` transport for emulators and ROMs where it works.
- Do not send pairing QR data, session secrets, keys, JWTs, notification text, or reply text through the new path.
- Do not add a fixed credential, unauthenticated exported endpoint, arbitrary command name, arbitrary component, arbitrary intent, shell string, or raw SQL control.
- Do not make offline-pairing ceremonies or other secret-bearing scenarios use the physical shell transport.
- Do not clear app data, unpair devices, or reinstall third-party personal apps.

## 4. Alternatives Considered

### 4.1 Selected: DUMP-protected ordered broadcast

Add an explicit physical control transport that invokes the existing debug receiver through ADB shell. The receiver is protected in the debug manifest by `android.permission.DUMP`. Android grants this signature-level permission to the shell UID on both named phones; ordinary installed apps cannot obtain it.

The receiver returns one bounded, base64url-encoded `E2eCommandResult` as the ordered broadcast result data. The host decodes it in memory. No credential or result file crosses `run-as`, and no token is placed in argv.

This is the smallest change that satisfies the remaining physical row. It reuses the existing command parser, allowlists, parameter validation, action controls, sanitized snapshots, scenario executor, and evidence model.

### 4.2 Rejected for this task: provider-based bidirectional streaming

A DUMP-protected provider could implement `content read` and `content write` streams for every existing private input and result. That could eventually replace `run-as` for secret ceremonies, but it adds file-descriptor lifecycle, one-time handle, cleanup, and streaming security work that the remaining action test does not need.

### 4.3 Rejected: signed companion instrumentation APK

A same-signature companion can authenticate strongly, but prior app-targeted instrumentation attempts could not retain the shared direct-LAN session while both targets were under long-running instrumentation. It also adds another installed process and lifecycle owner. The remaining scenario only needs shell-to-debug-app control.

## 5. Security Boundary

The physical control lane is accepted only when all of these are true:

- the component exists in a debug build;
- Android's component permission check proves the sender holds `android.permission.DUMP`;
- the intent targets the exact `E2eControlReceiver` component;
- the action equals `co.twinotify.e2e.CONTROL`;
- the command is already in `ALLOWED_COMMANDS`;
- the command's parameter keys and values pass the existing closed validators; and
- the encoded result is within a fixed 64 KiB UTF-8 bound.

The shell lane never accepts `token`, `secret_input_id`, or `auth_input_id`. `ExecuteSecret` is unsupported by its host adapter and fails before ADB. Pairing and offline-pairing continue to require the existing private `run-as` transport.

The receiver keeps token-authenticated execution as a separate internal branch for the existing transport and instrumentation tests. Tokenless execution is reachable only through `onReceive`, after Android has enforced the manifest permission. It is not exposed as a public test method.

The release manifest must continue to contain none of the debug receiver, provider, DUMP component permission, fixture control, or shell response code.

## 6. Host Contract

Add an explicit CLI option:

```text
-control-transport run-as|shell-broadcast
```

`run-as` remains the default. `shell-broadcast` is accepted only for `status` and the notification-action scenarios. It is rejected for `pair`, `offline-pairing`, LAN aggregate scenarios, and any path that calls `ExecuteSecret`.

The shell adapter:

1. creates a random, safe request ID locally;
2. sends a fixed-argument explicit broadcast and waits for its ordered result;
3. never includes `token`, `auth_input_id`, or a private file path;
4. captures the broadcast result data;
5. validates the base64url alphabet and decoded size;
6. strictly decodes `E2eCommandResult`; and
7. returns the result through the existing `control.Device` interface.

ADB command construction remains argument-based. Package, component, command, request ID, and all extras keep the existing grammar and allowlist validation. Errors remain sanitized and never include complete argv or raw broadcast output.

## 7. Android Contract

In `src/debug/AndroidManifest.xml`:

- set `android:permission="android.permission.DUMP"` on `E2eControlReceiver`;
- change the provider authority to `${applicationId}.e2e`;
- keep both components absent from `src/main` and release merging.

In `E2eControlReceiver`:

- preserve the current private-file token path unchanged;
- when no authentication-file handle is present, execute only the shell-safe command subset after normal parsing and parameter validation;
- reject all secret-handle parameters on that branch;
- serialize the existing sanitized result type, enforce the response bound, encode it with base64url without padding, set ordered result data, and finish the pending result;
- return a bounded error code if serialization or response delivery fails.

No notification or reply content is added to status, results, logs, metrics, or evidence.

## 8. Failure Handling

- Missing DUMP permission: Android rejects delivery before receiver code runs.
- Implicit or wrong component: host validation rejects it; the shell adapter always uses the exact component.
- Unknown command or parameter: existing controller validation returns `forbidden` or `invalid`.
- Secret-bearing command: host rejects before ADB; receiver also rejects as defense in depth.
- Missing or malformed broadcast result: host returns a typed control error and performs no scenario mutation after that step.
- Oversized or malformed base64/JSON result: host rejects it and does not write evidence claiming a pass.
- Device disconnect: existing typed offline handling applies; no automatic force-stop or retry is allowed.
- Scenario cleanup before the approved force-stop: release the existing claim pause through the same debug control when the device is reachable.

## 9. Verification

Use focused tests only:

1. A Go test proves shell mode emits the exact explicit broadcast arguments, never uses `run-as`, never includes a token, and decodes one bounded result.
2. One Go failure-path test proves malformed or oversized broadcast output is rejected.
3. Android instrumentation proves the receiver requires `android.permission.DUMP`, the provider authority equals `${context.packageName}.e2e`, tokenless direct test execution remains unavailable, secret parameters are rejected on the shell branch, and release merging excludes the components.
4. Existing E2E, Android security, and release-manifest tests remain green.
5. Physical preflight on MI 11X and POCO F1 proves `status`, `pause_after_claim`, and cleanup controls work without `run-as`, while both phones remain paired and Direct on Wi-Fi.
6. Immediately before the real `A.force-stop`, ask the user for fresh approval. Do not infer it from approval of this design.
7. The physical row passes only if one claim is observed before the stop, the action is not dispatched after restart, the mirror reaches `OUTCOME_UNKNOWN`, and terminal state converges.

After verification, restore both phones to the same evidence-bound APK. If release restoration is performed, confirm the debug receiver and provider are absent again and preserve app data.

## 10. Acceptance Criteria

- MI 11X can execute the shell-safe notification-action controls despite its `run-as` SELinux denial.
- POCO F1 uses the same evidence-bound debug APK and remains connected during preflight.
- Ordinary apps cannot invoke the physical controller because Android enforces `android.permission.DUMP`.
- The new lane carries no credentials, secrets, notification content, or reply content.
- Existing default `run-as` behavior is unchanged.
- Provider authority is internally consistent.
- Release artifacts contain no physical controller surface.
- No data is cleared and pairing survives the test APK updates.
- No force-stop occurs without fresh approval at the exact test step.
