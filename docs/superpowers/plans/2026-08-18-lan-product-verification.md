# LAN Product Integration and Physical Verification Implementation Plan

> **For Codex:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` task by task. Apply `superpowers:test-driven-development`, `superpowers:verification-before-completion`, and `superpowers:finishing-a-development-branch`. UI work must be rechecked point by point against `AGENTS.md` before completion.

**Goal:** Turn the secure offline-pairing and direct-transport foundations into a truthful, usable Android product and prove the full notification/call experience on two physical phones under offline, screen-off, fallback, restart, and stress conditions.

**Architecture:** Keep native state authoritative. React Native renders native pairing/route/permission/config snapshots and invokes bounded commands. Add explicit relay enrollment for offline-created pairs, secure E2E controls for physical scenarios, CI contracts, and a release-evidence verifier that cannot pass without final-commit device artifacts.

**Tech stack:** Kotlin/Expo Modules, React Native/TypeScript, existing component system, Go E2E controller, shell verification scripts, GitHub Actions, Android physical devices.

**Prerequisites:** Plans 1 and 2 are committed, independently approved, and their physical boundaries are green.

**Design source:** `docs/superpowers/specs/2026-08-18-offline-lan-sync-design.md`

## Execution rules

- Do not infer product state from configured URLs or discovered services. Only authenticated native route state is “connected.”
- Never store service truth in AsyncStorage. Native DataStore/Room/Keystore state is authoritative.
- UI error handling must distinguish permission, Wi-Fi isolation, clock, identity, pin, relay enrollment, and ordinary offline queue states.
- No debug component, token, or test command may enter the release manifest.
- Every physical claim requires raw, sanitized evidence tied to exact APK and Git commit hashes.
- Do not fabricate success for unavailable relay, UI automation, network isolation, or device capabilities.
- Each task records RED/GREEN evidence under `.superpowers/sdd/reports/lan-product-task-<n>.md`.
- Stage explicit paths and preserve user-owned files.

## Task 1: Finish native configuration and truthful health contracts

**Modify:**

- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/ServiceConfigStore.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncServiceStatus.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/TwinotifyCoreModule.kt`
- `mobile/modules/twinotify-core/src/TwinotifyCoreModule.ts`
- `mobile/modules/twinotify-core/src/TwinotifyCore.types.ts`
- `mobile/hooks/useSyncStatus.ts`
- `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/ServiceLifecycleTest.kt`

### Step 1: Write type/status REDs

Define and test exact snapshots:

```typescript
type RouteKind = 'lan' | 'relay' | 'offline' | 'disabled';
type RoutePhase = 'stopped' | 'discovering' | 'connecting' | 'authenticated' | 'degraded';

interface SyncStatus {
  state: SyncState;
  route: RouteKind;
  routePhase: RoutePhase;
  queuedCount: number;
  queuedBytes: number;
  preferLan: boolean;
  alwaysConnected: boolean;
  lanPermission: 'granted' | 'not_required' | 'denied' | 'unknown';
  relayEnrollment: 'none' | 'pending' | 'verified' | 'failed';
  lastReceiptAt?: number;
  lastErrorCode?: string;
}
```

Prove native mapping for every coordinator state, including LAN discovery with relay connected, fallback in progress, offline queue, disabled best-effort mode, permission denied, and clock mismatch. Legacy `state` remains compatible but cannot erase route detail.

### Step 2: Implement durable config APIs

Expose:

- `setPreferLan(boolean)`;
- `setAlwaysConnected(boolean)`;
- `getServiceConfig()`;
- `getLanPermissionStatus()`;
- `openLanPermissionSettings()` where applicable;
- `retryTransportNow()`;
- `getPairStatus()` extended with LAN binding and relay-enrollment booleans, never secrets.

Every setter persists before restarting/reconfiguring the service. A failure returns a stable error code and leaves the prior value intact.

### Step 3: Run gates and commit

```bash
cd mobile/android
./gradlew --no-daemon :twinotify-core:testDebugUnitTest \
  --tests '*ServiceLifecycleTest' --tests '*TransportCoordinatorTest'
cd ../..
npm run typecheck
```

```bash
git add mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/ServiceConfigStore.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncServiceStatus.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/TwinotifyCoreModule.kt \
  mobile/modules/twinotify-core/src/TwinotifyCoreModule.ts \
  mobile/modules/twinotify-core/src/TwinotifyCore.types.ts mobile/hooks/useSyncStatus.ts \
  mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/ServiceLifecycleTest.kt
git commit -m "feat(mobile): expose authenticated sync route"
```

## Task 2: Make onboarding and home truthful and repairable

**Modify:**

- `mobile/app/index.tsx`
- `mobile/app/onboarding/welcome.tsx`
- `mobile/app/onboarding/connect.tsx`
- `mobile/app/onboarding/perms.tsx`
- `mobile/app/onboarding/ready.tsx`
- `mobile/app/home.tsx`
- `mobile/state/onboardingState.ts`
- `mobile/hooks/useSyncStatus.ts`
- existing/new focused React tests under `mobile/app/**/__tests__`

### Step 1: Write UI REDs

Test these user-visible states from native fixtures:

- fresh user can choose nearby pairing without relay input;
- completed offline pair routes to permission setup and then home;
- `Direct on LAN` appears only for authenticated LAN;
- `Over relay` appears only for authenticated relay;
- discovery/connection show active but non-connected copy;
- offline shows exact queued count and distinct cause/repair action;
- LAN permission denial offers the correct settings action;
- isolated Wi-Fi explains router/client isolation without blaming internet;
- clock mismatch gives clock repair copy;
- best-effort mode explains that screen-off delivery is not guaranteed;
- retry invokes native `retryTransportNow`, not a UI-only state change;
- no obsolete phase/coming-soon comment remains on a shipped feature.

### Step 2: Implement with the existing design system

Keep content visible by default. Do not add a new hero stack, icon tile, pill-chip status system, gradient, broad shadow, entrance opacity gate, or fake control. Use concise copy and existing navigation/components. Replace emoji/generic placeholder icons in touched surfaces only when a real existing project icon is available; do not invent brand marks.

Connection state must remain legible at large font scale. Status color is supplementary; text carries meaning.

### Step 3: Visual and accessibility verification

- Run TypeScript and component tests.
- Install on both physical phones.
- Inspect light/dark modes where supported, 200% font scale, display scaling, portrait, and screen reader labels.
- Exercise every button with a real tap.
- Verify no text/control is clipped by cards, safe areas, or fixed heights.
- Re-read every point in `AGENTS.md` and record the point-by-point result.

### Step 4: Commit

```bash
git add mobile/app/index.tsx mobile/app/onboarding/welcome.tsx \
  mobile/app/onboarding/connect.tsx mobile/app/onboarding/perms.tsx \
  mobile/app/onboarding/ready.tsx mobile/app/home.tsx \
  mobile/state/onboardingState.ts mobile/hooks/useSyncStatus.ts
git commit -m "feat(mobile): show direct and fallback sync truthfully"
```

## Task 3: Finish LAN and reliability settings

**Modify:**

- `mobile/app/settings/index.tsx`
- `mobile/app/settings/pair.tsx`
- `mobile/app/filter.tsx` only if navigation/accessibility integration requires it
- focused settings tests

### Step 1: Write settings REDs

Prove:

- `Prefer LAN` reflects native config, can toggle, and rolls back on native failure;
- relay URL is optional for an offline-created pair;
- relay status distinguishes configured from enrolled/verified;
- Always Connected is user-controlled with accurate no-internet/screen-off explanation;
- LAN permission and current route are visible;
- paired detail shows application fingerprint and TLS fingerprint without secret material;
- existing relay pair offers `Enable nearby sync`;
- offline pair offers `Add relay fallback`;
- unpair warns that application and LAN identities rotate, then follows the reviewed stop/wipe flow;
- screen copy contains no “Phase 3,” “Phase 4,” or false “Coming soon” labels.

### Step 2: Implement and perform anti-slop review

Use existing `TwRow`, `TwSwitch`, `TwCard`, and text hierarchy. Do not add disabled fake controls. Every shown control must work. Avoid wrapping all metadata in chips. Preserve alignment across variable-length statuses and large fonts.

### Step 3: Run tests/real-device inspection and commit

```bash
cd mobile
npm run typecheck
npx expo-doctor
```

```bash
git add mobile/app/settings/index.tsx mobile/app/settings/pair.tsx mobile/app/filter.tsx
git commit -m "feat(mobile): configure nearby sync reliability"
```

## Task 4: Enroll an offline pair with an optional relay

**Create:**

- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing/RelayEnrollmentCoordinator.kt`
- `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/pairing/RelayEnrollmentCoordinatorTest.kt`
- `mobile/app/pair/relay-enrollment.tsx`

**Modify:**

- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing/PairProtocol.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/TwinotifyCoreModule.kt`
- `mobile/modules/twinotify-core/src/TwinotifyCoreModule.ts`
- `mobile/app/settings/pair.tsx`
- `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/pairing/RevocationPolicyTest.kt`

### Step 1: Write enrollment REDs

Test:

- normalized relay origin is shown and explicitly confirmed on both phones;
- enrollment uses existing device IDs/application keys and never rotates/replaces them;
- token/origin hash can travel over authenticated LAN control or QR fallback;
- peer identity must exactly match current `PeerStore`;
- conflicting relay binding is terminal and cannot rebind;
- partial relay handshake leaves LAN pair and service config intact;
- each phone persists `verified` only after its own authenticated relay session succeeds;
- cancellation/timeout clears pending enrollment;
- no token/JWT/key appears in status/error/log output;
- unpair revocation behavior remains marker-qualified and durable.

### Step 2: Implement coordinator and UI

Reuse current `/pair/init`, `/pair/hello`, `/pair/send_sig`, and `/pair/complete` protocol. Add no insecure relay registration shortcut. When LAN is active, carry only the bounded enrollment request over the authenticated direct control channel; relay HTTP/WS calls still go independently from both phones.

### Step 3: Verify against local relay and physical phones

Run focused JVM tests, relay protocol tests, then enroll an offline-created physical pair against an isolated test relay. Prove relay fallback only becomes available after both authenticated sessions pass.

### Step 4: Commit

```bash
git add mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing/RelayEnrollmentCoordinator.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing/PairProtocol.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/TwinotifyCoreModule.kt \
  mobile/modules/twinotify-core/src/TwinotifyCoreModule.ts \
  mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/pairing/RelayEnrollmentCoordinatorTest.kt \
  mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/pairing/RevocationPolicyTest.kt \
  mobile/app/pair/relay-enrollment.tsx mobile/app/settings/pair.tsx
git commit -m "feat(android): enroll optional relay fallback"
```

## Task 5: Extend the secure two-device E2E controller

**Modify:**

- `mobile/modules/twinotify-core/android/src/debug/AndroidManifest.xml`
- `mobile/modules/twinotify-core/android/src/debug/java/co/twinotify/core/e2e/E2eControlReceiver.kt`
- `mobile/modules/twinotify-core/android/src/debug/java/co/twinotify/core/e2e/E2eStateProvider.kt`
- `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/e2e/E2eControlSecurityTest.kt`
- `e2e/internal/adb/adb.go`
- `e2e/internal/control/control.go`
- `e2e/internal/scenario/executor.go`
- `e2e/internal/scenario/scenario.go`
- `e2e/cmd/twinotify-e2e/main.go`

**Create:**

- `e2e/internal/scenario/lan_reliability.go`
- `e2e/internal/scenario/lan_reliability_test.go`

### Step 1: Write security and orchestration REDs

Add allowlisted controls for transport retry, Wi-Fi cycling where host-supported, relay enrollment, process restart, and sanitized route snapshots. Tests must prove:

- provider/receiver still require the install token in constant time;
- package/component arguments are grammar-validated and shell-quoted;
- output/errors redact full ADB args, tokens, endpoints, pins, IDs, and content;
- release merged manifest contains no E2E component;
- executor requires two distinct hardware serials and rejects aliases of one phone;
- baseline correlation prevents stale notifications from satisfying predicates;
- unsupported UI/network/relay operations fail explicitly;
- all waits poll every 200 ms with per-step and scenario deadlines;
- failure snapshot always emits the five allowlisted sanitized artifacts.

### Step 2: Implement real physical controls

Use production native seams through the debug receiver, not fake status injection. Keep raw sensitive values only in process memory. Hash canonical IDs before artifacts. Include route generation so reconnect/failover assertions refer to a new authenticated session.

### Step 3: Run host/device security gates

```bash
cd e2e
go test ./... -race -count=1
go vet ./...
cd ../mobile/android
ANDROID_SERIAL=<serial> ./gradlew --no-daemon \
  :twinotify-core:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=co.twinotify.core.e2e.E2eControlSecurityTest
./gradlew --no-daemon :app:processReleaseMainManifest :twinotify-core:processReleaseManifest
```

Inspect merged release manifests explicitly.

### Step 4: Commit

```bash
git add mobile/modules/twinotify-core/android/src/debug/AndroidManifest.xml \
  mobile/modules/twinotify-core/android/src/debug/java/co/twinotify/core/e2e/E2eControlReceiver.kt \
  mobile/modules/twinotify-core/android/src/debug/java/co/twinotify/core/e2e/E2eStateProvider.kt \
  mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/e2e/E2eControlSecurityTest.kt \
  e2e/internal/adb/adb.go e2e/internal/control/control.go \
  e2e/internal/scenario/executor.go e2e/internal/scenario/scenario.go \
  e2e/internal/scenario/lan_reliability.go e2e/internal/scenario/lan_reliability_test.go \
  e2e/cmd/twinotify-e2e/main.go
git commit -m "test(e2e): control direct LAN reliability"
```

## Task 6: Make CI and generated-state gates cover LAN

**Modify:**

- `Makefile`
- `.github/workflows/mobile.yml`
- `.github/workflows/e2e-android.yml`
- `scripts/verify-generated-clean.sh`
- `e2e/scripts/run-two-emulators.sh`
- `e2e/scripts/validate-workflow.sh`
- `docs/test-scenarios.md`

**Create:**

- `scripts/verify-lan-contract.sh`
- `scripts/verify-lan-contract_test.sh`

### Step 1: Write workflow/contract REDs

Require CI triggers for every LAN protocol/native/UI/E2E path. Validate:

- mobile gate runs JVM, Android test compilation, lint, unit tests, and APK assembly;
- protocol/relay gates remain before mobile;
- release manifest isolation is checked;
- generated Room schema v5 is clean;
- E2E scheduled default is an executable scenario, not an unsupported aggregate;
- artifacts upload with `if: always()` and allowlist only sanitized files/test/lint reports;
- actions are pinned to reviewed immutable SHAs with `permissions: contents: read`;
- no workflow scans/uploads raw `/tmp` or run directories;
- LAN contract test rejects relay-only `CONNECTED` mapping, missing byte caps, missing physical scenario IDs, and absent v5 schema.

### Step 2: Add Make targets

Add focused targets such as:

```text
lan-contract-test
lan-native-test
e2e-lan
verify
```

`verify` remains protocol -> relay -> mobile -> generated/contract checks. Physical `e2e-lan` is separate and cannot silently skip devices.

### Step 3: Run static and full checks

```bash
bash -n scripts/verify-lan-contract.sh scripts/verify-lan-contract_test.sh \
  e2e/scripts/run-two-emulators.sh e2e/scripts/validate-workflow.sh
./scripts/verify-lan-contract_test.sh
./e2e/scripts/validate-workflow.sh
make lan-contract-test
git diff --check
```

Then run the complete root `make verify` with explicit Android SDK environment.

### Step 4: Commit

```bash
git add Makefile .github/workflows/mobile.yml .github/workflows/e2e-android.yml \
  scripts/verify-generated-clean.sh scripts/verify-lan-contract.sh \
  scripts/verify-lan-contract_test.sh e2e/scripts/run-two-emulators.sh \
  e2e/scripts/validate-workflow.sh docs/test-scenarios.md
git commit -m "ci: verify direct LAN synchronization"
```

## Task 7: Execute the final two-phone reliability matrix

**Modify:**

- `docs/release-evidence/README.md`
- `scripts/verify-release-evidence.sh`
- `docs/test-scenarios.md`

**Create:**

- `docs/release-evidence/lan-manifest.schema.json`

### Step 1: Extend release evidence REDs

The verifier must require final-commit evidence for:

- two distinct hardware serial hashes and Android API levels;
- standalone release APK SHA-256 and signed provenance;
- no-internet fresh pairing;
- direct notification post/update/cancel both directions;
- direct call ringing/active/idle both directions;
- duplicate/conflict behavior;
- process death/restart convergence;
- Wi-Fi loss/return convergence;
- screen-off Always Connected delivery;
- LAN loss to relay fallback;
- relay loss/restore LAN preference;
- permission denial/repair;
- unpair during traffic;
- burst/backpressure and long-idle reconnect;
- p50/p95/p99 latency and zero-loss counts;
- packet proof for no-relay direct scenarios;
- operator attestations and sanitized timelines.

Self-test must cover missing artifacts, hash mismatch, wrong Git commit, duplicate physical device, unsigned/tampered attestation, symlink escape, malformed metrics, false route status, missing packet proof, and a complete success fixture.

### Step 2: Build the standalone release APK

Use a release build containing `assets/index.android.bundle`, not a Metro-dependent debug APK. Record exact Gradle command, size, SHA-256, signing provenance, and Git commit.

Install to both explicit physical serials and cold-launch with the laptop/Metro stopped.

### Step 3: Run all physical scenarios

Run once on a LAN with internet uplink blocked and once with relay fallback available. For each direction and scenario, capture:

- source monotonic start;
- destination observation;
- route kind/generation;
- queue counts before/after;
- receipt time;
- result and bounded reason.

Do not capture notification/call content. Repeat ordinary awake LAN events at least 100 times after warmup. Require median below 500 ms and p95 below one second. Screen-off results are separately reported and accepted only with Always Connected.

### Step 4: Run the evidence verifier

```bash
RELEASE_EVIDENCE_DIR=<private-evidence-dir> make release-audit
```

Expected: verifier ties every artifact to exact final app/relay/E2E commits and the installed APK.

### Step 5: Commit documentation/verifier changes

```bash
git add docs/release-evidence/README.md docs/release-evidence/lan-manifest.schema.json \
  docs/test-scenarios.md scripts/verify-release-evidence.sh
git commit -m "test(release): require offline LAN evidence"
```

Private physical artifacts remain outside Git as documented.

## Task 8: Final completion audit, UI review, and handoff

### Step 1: Run authoritative repository gates from a clean generated tree

```bash
make proto-test
make relay-verify
ANDROID_HOME="$ANDROID_HOME" make mobile-verify
cd e2e && go test ./... -race -count=1 && go vet ./...
cd ..
./scripts/verify-generated-clean.sh
./scripts/verify-lan-contract.sh
git diff --check
```

Inspect XML counts, lint HTML/text reports, release APK, generated schema, merged manifests, and Docker/relay logs. A command exit alone is insufficient.

### Step 2: Re-audit every explicit design requirement

Create `.superpowers/sdd/reports/offline-lan-completion-audit.md` mapping every acceptance criterion in the design to:

- source implementation;
- automated test;
- physical scenario;
- raw evidence path;
- verdict.

Anything missing or indirect remains incomplete.

### Step 3: Perform the mandatory anti-slop/UI quality pass

Re-read all of `AGENTS.md`. Check every touched screen point by point on both phones. Fix contrast, clipping, margins, alignment, dead controls, fake state, inaccessible targets, default-template artifacts, and any inconsistent visual language. Rerun TypeScript, mobile verification, screenshots, and interaction checks after fixes.

### Step 4: Request independent final review

Review the complete Plan 1-3 range and all evidence. Require explicit findings for:

- pairing transcript/authentication;
- Keystore/TLS pinning and secret persistence;
- NSD privacy and Android permission behavior;
- custody/receipt boundaries and Room migration;
- route ownership/backpressure/lifecycle;
- notification and call convergence;
- debug/release isolation;
- UI truthfulness/accessibility/anti-slop compliance;
- physical evidence authenticity.

Fix all Critical, Important, and correctness-affecting findings. Rerun the full gate and affected physical scenarios on the final commit tree.

### Step 5: Finish the branch safely

Verify staged scope, commits, and user-owned files. Push non-force unless a previously reviewed local rewrite requires an exact `--force-with-lease`. Confirm `HEAD == origin/main` and clean status except documented user-owned files.

Do not mark the parent goal complete merely because LAN plans are complete. Return to the full phone-sync completion audit and verify any remaining explicit notification/call/product requirement.

## Plan 3 completion evidence

Plan 3 is complete only when:

- users can choose, pair, understand, configure, repair, and unpair offline LAN sync without developer help;
- optional relay fallback can be enrolled securely after offline pairing;
- UI reflects authenticated native truth and contains no dead/phase-placeholder controls;
- release APK cold-launches without Metro/laptop;
- both physical phones pass the complete offline/direct/fallback/screen-off/restart/stress matrix;
- evidence is sanitized, signed, hash-bound, and accepted by the verifier;
- CI and root verification cover all new paths;
- anti-slop/accessibility review is complete;
- independent review reports no Critical or Important issue.
