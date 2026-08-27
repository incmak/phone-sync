# Plan 015: Finish call sync as a truthful, user-reachable product

> **Executor instructions**: Primary checkout only. Use strict task-by-task TDD
> and retain every meaningful RED. No worktree, push, data clear, radio mutation,
> phone-number/contact logging, or commit before independent security, lifecycle,
> and UI review. Read the complete anti-slop law in `AGENTS.md` before UI work and
> re-check it point by point before handoff.

## Status

- **Priority**: P0
- **Effort**: L
- **Risk**: HIGH
- **Depends on**: Plans 011-014
- **Category**: direction
- **Planned at**: commit `66dc533`, 2026-08-27

## Drift check

```bash
git diff --stat 66dc533..HEAD -- \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/TwinotifyCoreModule.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncServiceStatus.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/NotifChannelSetup.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/call \
  mobile/modules/twinotify-core/src \
  mobile/hooks \
  mobile/app/settings/index.tsx \
  scripts/verify-release-evidence.sh \
  docs/test-scenarios.md
```

## Why this matters

Native call capture, persistence, transport, and rendering exist, but the
installed product exposes no way to grant `READ_PHONE_STATE` or enable capture.
Healthy native status fields are discarded by TypeScript. Call mirrors use the
ordinary default-importance channel despite the frozen high-priority contract.
Lower/conflicting call sequences are accepted without a peer receipt, and an
off-hook-first observation is falsely labeled outgoing. Finally, release
evidence requires no real cellular-call scenario.

This plan completes state-only call syncing. It does **not** add answer, reject,
hang-up, phone numbers, contacts, audio, call logs, `InCallService`, default
dialer role, or any remote call control.

## Frozen product contract

From `docs/superpowers/plans/2026-08-14-phone-call-state-sync.md`:

- explicit user opt-in;
- `READ_PHONE_STATE` only;
- payload contains random session UUID, ringing/active/idle, direction, sequence;
- no identity or content fields;
- generic action-free high-priority `CATEGORY_CALL` mirror;
- lower/conflicting sequences reject; exact duplicates are idempotent;
- physical evidence must remain pending unless actually captured.

## Current state

- Native `setCallCaptureEnabled` exists at `TwinotifyCoreModule.kt:236-273` and
  durable config defaults false.
- `TwinotifyCoreModule.ts`, `useTwinotifyCore.ts`, `useSyncStatus.ts`, and Settings
  expose none of the preference, permission, or health surface.
- onboarding requests notification permissions only.
- `CallStateMaterializer` posts on `CHANNEL_MIRRORS`, created at
  `IMPORTANCE_DEFAULT`.
- `SyncServiceStatus.callCaptureHealthCode` stores both failures and the healthy
  capability string `call_style_deferred_no_controls`.
- `CallStateReducer` maps every `sequence <= latest` to Stale; dispatcher accepts
  it, but no materialization receipt is generated.
- `CallStateCoordinator` labels an off-hook-first callback OUTGOING even though
  `TelephonyCallback.CallStateListener` provides no direction.
- `lan-direct-call-state` is synthetic; release verifier has no `PHY-CALL-01`.

## Scope

**In scope**:

- native module, service status, service config, call reducer/coordinator,
  notification channel/materializer, and their tests
- `mobile/modules/twinotify-core/src/TwinotifyCoreModule.ts`
- `mobile/modules/twinotify-core/src/TwinotifyCore.types.ts`
- `mobile/types/twinotify.d.ts`
- `mobile/hooks/useTwinotifyCore.ts`, `mobile/hooks/useSyncStatus.ts`
- `mobile/app/settings/index.tsx` and focused Settings/call-product tests
- `e2e/README.md`, `docs/test-scenarios.md`, `docs/release-evidence/README.md`
- `scripts/verify-release-evidence.sh` and self-test
- `advisor-plans/README.md`

**Out of scope**: remote controls, phone identity/content, new schema fields,
contact/call-log permissions, EAS credentials, or fabricating physical evidence.

## Implementation steps

### 1. Expose typed permission, preference, and health APIs

RED-first native/TS tests must require:

- `getCallCaptureEnabled(): Promise<boolean>` from durable config;
- `getCallStatePermissionAsync()` and `requestCallStatePermissionAsync()` using
  Expo's `appContext.permissions` plus
  `Permissions.get/askForPermissionsWithPermissionsManager`, returning the
  standard bounded permission result;
- typed `setCallCaptureEnabled(boolean)`;
- `SyncStatus` fields for enabled state, a disabled/failure reason, notification
  mode, and last-event timestamp.

Separate health from capability: healthy capture has no error code; expose
`call_style_deferred_no_controls` as a mode/capability field. Preserve old event
keys only if compatibility tests require them.

Permission denial must not persist a misleading enabled preference. Cancellation
must propagate by identity. Enabling while sync service is disabled persists the
opt-in and reports that capture starts with sync; disabling uses the existing
graceful shutdown gate.

### 2. Add the explicit Settings opt-in

Use the existing open Settings ledger and `TwSwitch`, not a new card stack.
Render one row named `Mirror call state` with concise privacy copy: only ringing,
active, and ended states; no phone numbers or controls. On enable:

1. show the Android rationale;
2. request permission;
3. only then persist enablement;
4. read back durable/native truth.

On denial or permanent denial, keep the switch off and render one accessible
recovery action to Android app settings. On unsupported telephony, disable the
switch with truthful copy. On native failure, roll back visual state. All touch
targets are at least 48dp and labels include state/subtitle. Do not add this
optional permission to onboarding's required-notification completion gate.

Add Jest coverage for loading, grant, denial, don't-ask-again recovery, enable,
disable, restart persistence, unsupported hardware, native rejection, and
screen-reader names. Re-run the UI XML checker and anti-slop audit.

### 3. Give call mirrors a dedicated high-priority channel

Create a new stable channel ID such as `mirrored_call_state_v1` at
`NotificationManager.IMPORTANCE_HIGH`. Android channel importance is immutable,
so never reuse `CHANNEL_MIRRORS`. Keep `CATEGORY_CALL`, ongoing state, generic
copy, no actions, no content/delete/full-screen intents. Instrumentation must
assert the posted notification channel ID and actual manager importance.

### 4. Correct call reduction semantics

Add explicit reducer outcomes for Apply, exact Duplicate, LowerSequence, and
Conflict. Determine exact duplicate using the authenticated msg/digest/inbound
journal rather than state name alone. Dispatcher behavior:

- exact same msg/digest duplicate is idempotent and replays existing lifecycle;
- lower sequence rejects with `call_sequence_lower`;
- same sequence with conflicting authenticated content rejects with
  `call_sequence_conflict`;
- no rejected call is acknowledged as applied or left waiting for an impossible
  peer receipt.

Change off-hook-first direction to UNKNOWN. INCOMING remains valid only after a
ringing observation. Reserve OUTGOING for a future source that proves direction.
Add fixed-vector stable-tag tests and centralize the duplicate hash helper without
changing persisted tag bytes.

### 5. Make real-call evidence a release requirement

Replace the obsolete `e2e/README.md` `-scenario call-state` command with the
supported evidence-pipeline scenario. Add `PHY-CALL-01` to test and release
evidence docs plus verifier/self-test. The manual protocol must cover both
directions on two phones:

- permission deny, grant, revoke, and recovery;
- real incoming ringing -> active -> idle;
- screen off;
- process restart during active call;
- explicit call-capture disable and service stop;
- one direct-LAN and one relay round;
- stable remote notification identity, high-priority channel, terminal removal,
  custody, receipt, and no resurrection.

Evidence is content-free: state enums, bounded health codes, route, timestamps,
counts, hashes, and pass/fail only. Never retain phone number, contact, SIM,
call audio, raw notification content, device ID, IP, SSID, key, or token.

If two suitable phones or a protected candidate are unavailable, the plan must
remain `BLOCKED: pending physical two-phone call run`; do not mark it DONE.

### 6. Gates and reviews

```bash
cd mobile && npm test -- --runInBand app/__tests__/settingsHandoffTrace.test.tsx app/__tests__/callSyncProduct.test.tsx
cd mobile && npm run typecheck && npm run lint
cd mobile/android && ANDROID_HOME=/Users/mak/Library/Android/sdk ./gradlew --no-daemon \
  :twinotify-core:testDebugUnitTest --tests '*Call*' --tests '*TwinotifyCoreModuleWorkflowTest' --rerun-tasks
cd mobile/android && ANDROID_HOME=/Users/mak/Library/Android/sdk ./gradlew --no-daemon \
  :twinotify-core:testDebugUnitTest :twinotify-core:compileDebugAndroidTestKotlin :twinotify-core:lintDebug :app:assembleDebug
cd e2e && GOCACHE=/private/tmp/twinotify-call-e2e-cache go test ./... -race -count=1 && go vet ./...
./scripts/verify-release-evidence.sh --self-test
git diff --check
```

Require independent native lifecycle/security review and independent UI/a11y
review before commit. Physical execution is a separate required final gate.

## Done criteria

- [ ] A fresh user can grant permission and enable/disable call state mirroring.
- [ ] UI always reflects durable/native state and bounded truthful health.
- [ ] Healthy capability is not mislabeled as an error.
- [ ] Call notifications use a new HIGH channel and remain action-free/private.
- [ ] Lower/conflicting sequences reject; exact duplicates remain idempotent.
- [ ] Off-hook-first is UNKNOWN, not fabricated OUTGOING.
- [ ] Full host/native/UI/verifier gates and both independent reviews pass.
- [ ] `PHY-CALL-01` passes on the exact candidate in both directions, or plan
  remains explicitly BLOCKED without a completion claim.

## STOP conditions

- Any implementation requests phone number, contacts, call log, audio, dialer
  role, `ANSWER_PHONE_CALLS`, or remote control capability.
- Permission must be requested before an explicit user action.
- Existing graceful shutdown/cancellation ordering would be bypassed.
- High importance would require changing the immutable ordinary mirror channel.
- Physical proof would include private call/device/network data.
- In-scope drift contradicts the frozen call-state contract.
