# Offline Pairing and LAN Identity Implementation Plan

> **For Codex:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task by task. Use `superpowers:test-driven-development` for every behavior change, `superpowers:systematic-debugging` for failures, and `superpowers:requesting-code-review` before each commit is pushed.

**Goal:** Let two fresh Android phones establish the existing one-peer trust relationship over a shared Wi-Fi network with no internet, relay, laptop, or public certificate authority.

**Architecture:** Keep the existing X25519/Ed25519 application identity. Add a separate Android-Keystore P-256 TLS identity, a sealed LAN binding record, a canonical mutually signed pairing transcript, and a temporary NSD/TLS pairing transport. Persist trust through a recoverable two-phase binding commit. Expose a small native pairing state machine to the existing Expo screens.

**Tech stack:** Kotlin, coroutines, Android Keystore/JSSE, `NsdManager`, DataStore, libsodium, Expo Modules, React Native/TypeScript, JUnit4, AndroidX instrumentation.

**Design source:** `docs/superpowers/specs/2026-08-18-offline-lan-sync-design.md`

## Execution rules

- Start from the reviewed design commit or later. Record `git rev-parse HEAD` in the task report.
- Preserve user-owned `AGENTS.md` and unrelated worktree changes. Stage explicit paths only.
- Do not change relay delivery, direct LAN notification transport, or route selection in this plan.
- Never log QR payloads, pair tokens, transcript bytes, private keys, LAN secrets, device names, or raw device IDs.
- Every parser is closed-world and bounded before allocation.
- Every task records the RED command/output, GREEN command/output, and touched paths in `.superpowers/sdd/reports/offline-pairing-task-<n>.md`.
- Do not push until an independent review finds no Critical or Important issue.

## Task 1: Freeze pairing wire contracts and cryptographic derivations

**Create:**

- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing/lan/LanPairingModels.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing/lan/LanPairingCodec.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing/lan/LanPairingCrypto.kt`
- `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/pairing/lan/LanPairingCodecTest.kt`
- `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/pairing/lan/LanPairingCryptoTest.kt`

### Step 1: Write the failing contract tests

Test these exact invariants:

- QR version is exactly `1`; unknown, missing, duplicated, and extra fields fail.
- Session ID and device ID are bounded canonical UUID-based identifiers.
- Session token is exactly 32 decoded bytes.
- application public keys and TLS SPKI digest have exact decoded lengths.
- lifetime is positive and capped at five minutes.
- transcript encoding is length-delimited, deterministic, role-independent, and includes both identities, both TLS pins, both nonces, session ID, lifetime, and negotiated version.
- transcript signatures use the existing Ed25519 identity and reject field mutation.
- SAS is six decimal digits derived from the transcript digest with unbiased reduction.
- the LAN secret is 32 bytes from HKDF-SHA256 with a domain-separated label.
- raw secret fields are redacted from `toString()` and exception messages.

Run:

```bash
cd mobile/android
./gradlew --no-daemon :twinotify-core:testDebugUnitTest \
  --tests '*LanPairingCodecTest' \
  --tests '*LanPairingCryptoTest'
```

Expected RED: unresolved LAN pairing contract types. Preserve the compile failure in the report.

### Step 2: Implement the minimal pure contract

Use immutable byte-array wrappers that copy on construction/access. Parse with exact key inventories, decoded-size checks, and a maximum QR JSON byte size of 4096. Use `MessageDigest.isEqual` for digest comparisons. Use `Mac.getInstance("HmacSHA256")` for HKDF extract/expand and existing libsodium signing operations for transcript signatures.

Expose these stable boundaries:

```kotlin
data class LanPairingQr(/* bounded public/session fields */)
data class LanPairingHello(/* peer public identity, pin, nonce */)
data class LanPairingTranscript(/* canonical complete transcript */)

object LanPairingCodec {
    fun encodeQr(value: LanPairingQr): String
    fun decodeQr(raw: String): LanPairingQr
    fun canonicalTranscript(value: LanPairingTranscript): ByteArray
}

object LanPairingCrypto {
    fun signTranscript(transcript: ByteArray, secretKey: ByteArray): ByteArray
    fun verifyTranscript(transcript: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean
    fun shortAuthenticationString(transcript: ByteArray): String
    fun derivePairSecret(sessionToken: ByteArray, transcript: ByteArray): ByteArray
}
```

### Step 3: Prove GREEN and regression coverage

Run the focused command twice, then the full module JVM suite:

```bash
cd mobile/android
./gradlew --no-daemon :twinotify-core:testDebugUnitTest
```

Expected: all tests pass, no raw token/secret in XML or console output.

### Step 4: Commit the contract task

```bash
git add mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing/lan \
  mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/pairing/lan
git commit -m "feat(android): define offline pairing transcript"
```

## Task 2: Add the Keystore-backed LAN TLS identity

**Create:**

- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing/lan/LanIdentityStore.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing/lan/LanTlsContextFactory.kt`
- `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/pairing/lan/LanIdentityStoreTest.kt`

**Modify:**

- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing/UnpairOps.kt`

### Step 1: Write instrumentation REDs

Tests must prove:

- first load generates alias `twinotify.lan.tls.v1` using P-256, SHA-256, sign/verify purposes, and a valid self-signed certificate;
- repeated load returns the same public SPKI and non-exportable private key handle;
- SPKI SHA-256 is stable and exactly 32 bytes;
- the server `SSLContext` can initialize from the AndroidKeyStore key manager;
- a pin verifier accepts only an exact constant-time SPKI match;
- `delete()` removes the alias and a later load produces a different pin;
- failures expose bounded codes, not provider messages containing aliases or certificate material.

Run:

```bash
cd mobile/android
./gradlew --no-daemon :twinotify-core:compileDebugAndroidTestKotlin
```

Expected RED: missing `LanIdentityStore`/`LanTlsContextFactory`.

### Step 2: Implement Keystore identity and TLS contexts

Use `KeyPairGenerator("EC", "AndroidKeyStore")` with `ECGenParameterSpec("secp256r1")`, SHA-256, and an X.509 certificate validity window that comfortably exceeds app lifetime. Do not request user authentication for background use. Build server key managers from `AndroidKeyStore`; build client trust from a pin-checking `X509TrustManager` that rejects before application data.

Do not disable hostname/certificate checks globally. The LAN client factory is a dedicated trust boundary used only with the paired SPKI pin.

### Step 3: Wire full unpair deletion

Add `LanIdentityStore.delete()` to `UnpairOps.wipeAll()` after active service shutdown and before application key rotation. Keep the existing wipe ordering tests green and add a focused assertion through an injectable wipe seam if required.

### Step 4: Run device tests

Select one explicit online serial:

```bash
adb devices
cd mobile/android
ANDROID_SERIAL=<serial> ./gradlew --no-daemon \
  :twinotify-core:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=co.twinotify.core.pairing.lan.LanIdentityStoreTest
```

Expected: all identity tests pass on a physical Android 14+ device. Emulator-only evidence is insufficient for final hardware-backed claims.

### Step 5: Commit

```bash
git add mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing/lan/LanIdentityStore.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing/lan/LanTlsContextFactory.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing/UnpairOps.kt \
  mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/pairing/lan/LanIdentityStoreTest.kt
git commit -m "feat(android): add pinned LAN TLS identity"
```

## Task 3: Persist a sealed, crash-safe LAN binding

**Create:**

- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/LanPairStore.kt`
- `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/storage/LanPairStoreTest.kt`

**Modify:**

- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/PeerStore.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing/UnpairOps.kt`
- `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/crypto/CryptoStoreTest.kt`

### Step 1: Write transaction/recovery REDs

Cover:

- sealed binding round-trip with a 32-byte LAN secret;
- plaintext DataStore contains no LAN secret or unsealed binding JSON;
- `PeerRecord` contains only public `lanBindingId`;
- binding identity digest covers device ID, encryption key, signing key, and display name normalization;
- a secret written without public commit is unusable and removed by recovery;
- a public marker with missing/corrupt/mismatched binding disables LAN without deleting the relay pair;
- complete two-phase commit loads exactly once and survives process recreation;
- replacing a live binding is rejected unless it is an exact idempotent retry or authenticated upgrade;
- unpair clears binding before master/application key rotation;
- array getters and store inputs defensively copy.

### Step 2: Implement the sealed store

Use `WrappedKeys.seal/unseal`. Store ciphertext, IV, version, and binding ID in a dedicated DataStore. Encode the inner record with a closed-world binary or canonical JSON codec. Never expose the secret through `PeerRecord`, JS, logs, or status events.

Expose:

```kotlin
data class LanBinding(/* private fields with copying accessors */)

object LanPairStore {
    suspend fun prepare(context: Context, peer: PeerRecord, binding: LanBinding): PreparedLanBinding
    suspend fun commit(context: Context, prepared: PreparedLanBinding)
    suspend fun loadValidated(context: Context, peer: PeerRecord): LanBinding?
    suspend fun recover(context: Context, peer: PeerRecord?)
    suspend fun clear(context: Context)
}
```

`commit` writes the sealed record first, verifies it, then performs the single `PeerStore` edit that adds `lanBindingId`. Startup recovery must never erase a valid relay-only peer.

### Step 3: Run instrumentation and module tests

```bash
cd mobile/android
ANDROID_SERIAL=<serial> ./gradlew --no-daemon \
  :twinotify-core:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=co.twinotify.core.storage.LanPairStoreTest
./gradlew --no-daemon :twinotify-core:testDebugUnitTest :twinotify-core:lintDebug
```

### Step 4: Commit

```bash
git add mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/PeerStore.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/LanPairStore.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing/UnpairOps.kt \
  mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/storage/LanPairStoreTest.kt \
  mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/crypto/CryptoStoreTest.kt
git commit -m "feat(android): seal LAN peer binding"
```

## Task 4: Build the pure offline pairing state machine

**Create:**

- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing/lan/OfflinePairingCoordinator.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing/lan/OfflinePairingPort.kt`
- `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/pairing/lan/OfflinePairingCoordinatorTest.kt`

### Step 1: Write deterministic RED tests

Use a fake clock, fake port, and fake stores. Test both roles through:

```text
IDLE -> ADVERTISING/RESOLVING -> TLS_AUTHENTICATED -> VERIFY_CODE
     -> LOCAL_CONFIRMED -> MUTUALLY_SIGNED -> COMMITTED -> COMPLETE
```

Also prove:

- local confirmation alone never commits;
- peer signature before local confirmation is retained only in bounded provisional memory;
- transcript/key/pin/session mismatch aborts;
- duplicate frames are idempotent;
- timeout uses monotonic time;
- cancellation at every state closes the port and clears provisional state;
- process restart cannot resume an uncommitted session as trusted;
- existing relay pair upgrade requires exact application-key match;
- coordinator emits bounded state/error codes without secrets.

### Step 2: Implement the coordinator against narrow ports

Keep Android NSD/socket APIs out of the state machine. `OfflinePairingPort` transports bounded typed messages and exposes monotonic time. Inject identity, crypto, and persistence seams.

No callback may directly write `PeerStore`. The coordinator is the sole pairing commit owner.

### Step 3: Run focused and full JVM tests

```bash
cd mobile/android
./gradlew --no-daemon :twinotify-core:testDebugUnitTest \
  --tests '*OfflinePairingCoordinatorTest'
./gradlew --no-daemon :twinotify-core:testDebugUnitTest
```

### Step 4: Commit

```bash
git add mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing/lan/OfflinePairingCoordinator.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing/lan/OfflinePairingPort.kt \
  mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/pairing/lan/OfflinePairingCoordinatorTest.kt
git commit -m "feat(android): coordinate mutual offline pairing"
```

## Task 5: Add temporary NSD discovery and pinned pairing TLS

**Create:**

- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing/lan/PairingNsdAdapter.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing/lan/AndroidPairingNsdAdapter.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing/lan/OfflinePairingTransport.kt`
- `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/pairing/lan/OfflinePairingTransportTest.kt`
- `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/pairing/lan/OfflinePairingLoopbackTest.kt`

### Step 1: Write transport REDs

Tests must prove:

- service type is exactly `_twinotify-pair._tcp.` and TXT contains only opaque session/version data;
- only the QR-matching session is resolved;
- resolved address is used through the supplied Android `Network`;
- pairing works without `NET_CAPABILITY_INTERNET` or `VALIDATED`;
- TLS pin mismatch fails before any pairing message is read;
- length-prefixed frames reject zero, oversize, invalid UTF-8, unknown types, and trailing data;
- inbound/outbound byte budgets are bounded;
- accept/connect timeouts close sockets and unregister NSD;
- loopback completes through real JSSE contexts and persists no trust until mutual confirm.

### Step 2: Implement the Android adapter

Wrap every `NsdManager` listener in cancellable coroutine code. Keep listener instances so stop calls use the exact registered object. Bind resolution and sockets to the service `Network`. Acquire a scoped multicast lock only while required by platform behavior, with reference counting disabled and `finally` release.

The temporary listener binds an ephemeral port and advertises it. Never put IP addresses, device IDs, display names, keys, pins, or tokens into NSD records. Verify that the existing manifest declarations for `INTERNET`, `ACCESS_NETWORK_STATE`, `NEARBY_WIFI_DEVICES`, and `ACCESS_LOCAL_NETWORK` remain present; do not edit the manifest unless a test proves a missing declaration.

### Step 3: Run loopback and physical discovery smoke

```bash
cd mobile/android
ANDROID_SERIAL=<serial> ./gradlew --no-daemon \
  :twinotify-core:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=co.twinotify.core.pairing.lan.OfflinePairingLoopbackTest
```

Then install the debug test APK on both explicit physical serials and capture sanitized evidence that one advertises and the other resolves on Wi-Fi with internet disabled. This smoke is not allowed to claim complete pairing until Task 7.

### Step 4: Commit

```bash
git add mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing/lan/PairingNsdAdapter.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing/lan/AndroidPairingNsdAdapter.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing/lan/OfflinePairingTransport.kt \
  mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/pairing/lan/OfflinePairingTransportTest.kt \
  mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/pairing/lan/OfflinePairingLoopbackTest.kt
git commit -m "feat(android): discover offline pairing sessions"
```

## Task 6: Expose bounded native pairing APIs

**Modify:**

- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/TwinotifyCoreModule.kt`
- `mobile/modules/twinotify-core/src/TwinotifyCoreModule.ts`
- `mobile/modules/twinotify-core/src/TwinotifyCore.types.ts`
- `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/pairing/lan/OfflinePairingCoordinatorTest.kt`

### Step 1: Add compile-time RED contracts

Define TypeScript and Kotlin events for:

- `startOfflinePairing(displayName)` returning QR JSON;
- `joinOfflinePairing(qrJson, displayName)`;
- `confirmOfflinePairing(sessionId)`;
- `cancelOfflinePairing(sessionId)`;
- `getOfflinePairingStatus()`;
- `onOfflinePairingStatus` event.

Status exposes only role, phase, session ID, bounded error code, peer display name, six-digit SAS, and completion flag. It never exposes token, transcript, keys, pin, secret, IP, or port.

### Step 2: Implement single-session lifecycle

`TwinotifyCoreModule` owns one coordinator job under `moduleScope`. Starting a conflicting session fails with `pair_session_active`. Destroy/cancel closes transport. Promise completion and event emission must occur on safe Expo boundaries without blocking the main thread.

Preserve all current relay pairing APIs for compatibility.

### Step 3: Run Kotlin compile and TypeScript

```bash
cd mobile/android
./gradlew --no-daemon :twinotify-core:testDebugUnitTest :twinotify-core:compileDebugKotlin
cd ../..
npm run typecheck
```

### Step 4: Commit

```bash
git add mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/TwinotifyCoreModule.kt \
  mobile/modules/twinotify-core/src/TwinotifyCoreModule.ts \
  mobile/modules/twinotify-core/src/TwinotifyCore.types.ts \
  mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/pairing/lan/OfflinePairingCoordinatorTest.kt
git commit -m "feat(mobile): expose offline pairing state"
```

## Task 7: Integrate offline pairing and LAN upgrade UI

**Create:**

- `mobile/app/onboarding/connect.tsx`
- `mobile/app/pair/nearby.tsx`
- `mobile/app/pair/verify.tsx`
- `mobile/app/pair/__tests__/offlinePairingFlow.test.tsx`
- `mobile/jest.config.js`
- `mobile/jest.setup.js`

**Modify:**

- `mobile/app/onboarding/_layout.tsx`
- `mobile/app/onboarding/role.tsx`
- `mobile/app/onboarding/relay.tsx`
- `mobile/app/pair/_layout.tsx`
- `mobile/app/pair/qr.tsx`
- `mobile/app/pair/scan.tsx`
- `mobile/app/pair/fingerprint.tsx`
- `mobile/app/pair/success.tsx`
- `mobile/app/pair/fail.tsx`
- `mobile/app/settings/pair.tsx`
- `mobile/state/onboardingState.ts`
- `mobile/package.json`
- `mobile/package-lock.json`

### Step 1: Write UI behavior REDs

First add the missing component-test foundation with Expo-compatible versions of `jest-expo`, `@testing-library/react-native`, and `react-test-renderer`, plus a `test` script. Install through Expo/npm so the lockfile is authoritative. Configure Jest to mock the native Expo module and router explicitly; no test may call a real relay or NSD service.

Test:

- onboarding offers `Pair nearby without internet` and `Use a relay`;
- nearby initiator renders the QR returned by native code without logging it;
- joiner passes only scanned text to native validation;
- both roles show the same six-digit code and require explicit confirmation;
- back/cancel calls native cancellation;
- timeout, isolated Wi-Fi, permission, pin, identity, and peer rejection errors have distinct repair copy;
- completion marks onboarding only after native `COMPLETE`;
- an existing relay pair can enter `Enable nearby sync`, and an identity mismatch cannot replace it;
- controls have accessibility roles/labels, 48dp targets, readable contrast, no clipped content, and visible default state.

### Step 2: Implement using existing components

Reuse the repository design system and QR scanner. Do not add a new visual system, generic icon tiles, decorative pills, gradients, hover/lift behavior, or hidden entrance content. Remove obsolete phase comments from touched screens. Keep relay pairing available.

### Step 3: Verify TypeScript and real rendering

```bash
cd mobile
npm run typecheck
npm test -- --runInBand
npx expo-doctor
```

Build a debug APK, install on both physical phones, and inspect every touched screen in portrait with large font and dark/light system settings where supported. Capture screenshots without QR/token content.

Perform the mandatory anti-slop recheck point by point for every touched UI file. Record corrections in the task report.

### Step 4: Commit

```bash
git add mobile/app/onboarding/_layout.tsx mobile/app/onboarding/role.tsx \
  mobile/app/onboarding/relay.tsx mobile/app/onboarding/connect.tsx \
  mobile/app/pair/_layout.tsx mobile/app/pair/nearby.tsx mobile/app/pair/verify.tsx \
  mobile/app/pair/qr.tsx mobile/app/pair/scan.tsx mobile/app/pair/fingerprint.tsx \
  mobile/app/pair/success.tsx mobile/app/pair/fail.tsx \
  mobile/app/pair/__tests__/offlinePairingFlow.test.tsx \
  mobile/app/settings/pair.tsx mobile/state/onboardingState.ts \
  mobile/jest.config.js mobile/jest.setup.js mobile/package.json mobile/package-lock.json
git commit -m "feat(mobile): pair nearby without internet"
```

## Task 8: Prove offline pairing on two physical phones

**Modify:**

- `mobile/modules/twinotify-core/android/src/debug/java/co/twinotify/core/e2e/E2eControlReceiver.kt`
- `mobile/modules/twinotify-core/android/src/debug/java/co/twinotify/core/e2e/E2eStateProvider.kt`
- `docs/test-scenarios.md`
- `Makefile`

**Create:**

- `e2e/internal/scenario/offline_pairing.go`
- `e2e/internal/scenario/offline_pairing_test.go`
- `scripts/verify-offline-pairing-evidence.sh`

### Step 1: Extend only the secure debug control surface

Add allowlisted commands/status for starting, joining, confirming, cancelling, and querying offline pairing. Retain install-token validation, constant-time token checks, bounded JSON, component grammar validation, and release-manifest isolation. Do not expose QR token or raw transcript through the state provider.

### Step 2: Add deterministic host orchestration

The host scenario must target two explicit distinct hardware serials, disable mobile data, verify both remain on the same Wi-Fi network, start pairing, relay the QR payload only in process memory, require matching SAS, confirm both, and assert matching peer application identities plus LAN bindings. Artifacts contain hashes and state codes only.

### Step 3: Run complete gates

```bash
make proto-test
make relay-ci-test
ANDROID_HOME="$ANDROID_HOME" make mobile-verify
cd e2e && go test ./... -race -count=1 && go vet ./...
./scripts/verify-generated-clean.sh
git diff --check
```

### Step 4: Run the physical acceptance boundary

With both distinct phones connected:

```bash
adb devices -l
make e2e-offline-pairing \
  E2E_DEVICE_A=<serial-a> \
  E2E_DEVICE_B=<serial-b>
```

Before the run, remove/clear both app installations, stop any laptop relay, disable mobile data, and block internet uplink while retaining local Wi-Fi client connectivity. Prove pairing completes and persists across app process restart. Use packet/DNS evidence to show no public or relay endpoint was required.

### Step 5: Independent review and final commit

Request review of the exact Task 1-8 range, with special attention to transcript binding, TLS pinning, secret storage, crash recovery, debug-surface privacy, and release isolation. Fix all Critical/Important findings and rerun affected plus full gates.

```bash
git add mobile/modules/twinotify-core/android/src/debug/java/co/twinotify/core/e2e/E2eControlReceiver.kt \
  mobile/modules/twinotify-core/android/src/debug/java/co/twinotify/core/e2e/E2eStateProvider.kt \
  e2e/internal/scenario/offline_pairing.go e2e/internal/scenario/offline_pairing_test.go \
  docs/test-scenarios.md Makefile scripts/verify-offline-pairing-evidence.sh
git commit -m "feat(android): pair securely without internet"
```

Do not push until review is CLEAR/APPROVE and `git status --short` contains only preserved user-owned files or documented ignored evidence.

## Plan 1 completion evidence

This plan is complete only when:

- two fresh physical phones pair with no relay, laptop, or internet;
- both sides persist matching application identity, TLS pins, and sealed LAN bindings;
- process death before confirmation creates no usable peer;
- process restart after completion restores the pair;
- an existing relay pair upgrades only when keys match;
- unpair clears LAN trust and rotates identities in the reviewed order;
- all full repository gates pass;
- release manifests contain no debug control components;
- independent review has no Critical or Important finding.
