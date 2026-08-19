# Plan 001: Let offline-only pairs unpair safely

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving on. If a
> STOP condition occurs, stop and report. Work in the primary checkout only;
> do not create a Git worktree. Update the status row in
> `advisor-plans/README.md` when done.
>
> **Drift check (run first)**:
> `git diff --stat f667dd1..HEAD -- mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/TwinotifyCoreModule.kt mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/pairing`
> If an in-scope file changed, compare the excerpts below against live code.

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: MED
- **Depends on**: none
- **Category**: bug
- **Planned at**: commit `f667dd1`, 2026-08-19

## Why this matters

Offline pairing commits a peer and LAN binding without a relay URL. The current
unpair implementation requires a relay URL for every peer before it reaches the
local wipe. That leaves offline-only users permanently paired unless they clear
application data. The fix must allow a genuinely offline-only pair to skip an
inapplicable remote revocation while preserving fail-closed revocation for every
relay-capable pair.

## Current state

- `TwinotifyCoreModule.kt:459-493` owns the public `unpair` function. It persists
  a revocation marker before deciding whether revocation is applicable, and its
  revoke block contains:

  ```kotlin
  if (peer != null) {
      val relayUrl = config.relayUrl ?: error("paired device has no relay URL")
      // mint JWT and revoke on relay
  }
  ```

- `storage/PeerStore.kt:15-20` initially exposed only `lanBindingId`. A configured
  relay URL is not proof of relay authorization because `SyncService` persists
  it before attempting a socket. The implementation therefore needs a durable
  tri-state `relayRevocationRequired` fact: `false` for a fresh offline pair,
  `true` for the relay pairing flow, and `null` for legacy unknown state.
- `pairing/UnpairWorkflow.kt:8-18` is the reviewed order boundary: stop and await,
  revoke, then wipe in `NonCancellable`. Preserve this order.
- `pairing/RevocationPolicy.kt` already classifies HTTP outcomes. Do not weaken
  its 401/revocation-marker rules.
- `UnpairWorkflowTest.kt` verifies ordering but not whether remote revocation is
  applicable.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Focused tests | `cd mobile/android && ./gradlew --no-daemon :twinotify-core:testDebugUnitTest --tests '*Unpair*' --tests '*RevocationPolicyTest'` | exit 0, all selected tests pass |
| Full native unit | `cd mobile/android && ./gradlew --no-daemon :twinotify-core:testDebugUnitTest :twinotify-core:compileDebugKotlin` | exit 0 |
| Diff hygiene | `git diff --check` | no output, exit 0 |

Use the repository's configured `ANDROID_HOME` if Gradle cannot find the SDK.

## Scope

**In scope**:

- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/TwinotifyCoreModule.kt`
- create `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing/UnpairRevocationDecision.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/PeerStore.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/LanPairStore.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing/lan/AndroidOfflinePairingRuntimeFactory.kt`
- create `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/pairing/UnpairRevocationDecisionTest.kt`
- modify `UnpairWorkflowTest.kt` only if an integration-order assertion is needed
- `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/storage/LanPairStoreTest.kt`
- `advisor-plans/README.md` status only

**Out of scope**:

- changing relay revocation HTTP semantics;
- allowing a relay-capable pair to wipe locally after a failed revoke;
- changing cryptographic wipe order;
- changing UI design or direct-LAN transport;
- any worktree creation.

## Git workflow

- Stay in `/Users/mak/Documents/projects/personal/phone-sync` and verify it with
  `pwd -P` before editing.
- Commit convention is Conventional Commits, for example
  `fix(android): unpair offline-only peers`.
- Do not push unless the operator explicitly asks.

## Steps

### Step 1: Add a pure RED decision test

Create a closed decision type such as:

```kotlin
sealed interface UnpairRevocationDecision {
    data object NoPeer : UnpairRevocationDecision
    data object OfflineOnly : UnpairRevocationDecision
    data class Relay(val relayUrl: String) : UnpairRevocationDecision
}
```

The policy input is `peerPresent`, tri-state `relayRevocationRequired`,
`lanBindingId`, and the persisted relay URL.
Write tests for exactly these cases:

1. no peer -> no remote revoke;
2. peer plus nonblank relay URL -> relay revoke with trimmed/canonical URL;
3. explicit `false` plus LAN binding -> offline-only even when a relay URL was
   configured but never authorized;
4. explicit `true` plus relay URL -> authenticated revoke;
5. explicit `true` without relay URL -> bounded failure;
6. legacy unknown plus relay URL -> attempt authenticated revoke;
7. legacy unknown plus LAN binding and no relay URL -> offline-only;
8. peer with neither LAN binding nor relay URL -> bounded failure
   `missing_relay_url`, because silently wiping a legacy relay authorization is
   unsafe;
9. blank relay URL is treated as absent.

Run the focused command and record the expected compile/test RED before adding
production policy code.

### Step 2: Use the decision before marking remote revocation

Persist `relayRevocationRequired=true` when the existing relay-specific
`storePeerPubkeys` call stores its peer. Persist `false` only when the production
offline committer creates a fresh peer. Preserve the current tri-state value
when a relay-first peer gains a LAN binding. Missing legacy data stays `null`.

In `TwinotifyCoreModule.unpair`, compute the decision from the loaded peer/config.
Move `setRevocationRequestedAt` into the `Relay` branch so an offline-only unpair
never creates a false relay-revocation marker. Keep
`UnpairWorkflow.execute` as the sole ordering boundary:

- `stopAndAwait`: quiesce offline pairing and active sync service;
- `revokePeer`: no-op for `NoPeer`/`OfflineOnly`, existing authenticated revoke
  for `Relay`;
- `wipeLocal`: unchanged full wipe and peer-unpaired notification.

Do not catch relay revocation failure and continue to wipe.

**Verify**: focused tests pass.

### Step 3: Add the production-order regression

Add a test using the real decision plus `UnpairWorkflow` lambdas to prove an
offline-only decision produces `stop-and-await, wipe` and never `revoke`, while
a relay decision remains `stop-and-await, revoke, wipe`. This may live in
`UnpairWorkflowTest.kt` if it can call the new production policy directly.

**Verify**: focused tests and full native unit command pass.

## Test plan

- Decision matrix: five cases listed in Step 1.
- Workflow integration: offline-only skip and relay-required order.
- Existing 204/401 revocation classification tests remain unchanged and green.
- No test may mock a UI-only flag as evidence of the native decision.

## Done criteria

- [ ] Offline-only peer plus missing relay URL reaches the local wipe.
- [ ] Relay-capable peer still requires authenticated remote revocation before wipe.
- [ ] Legacy/inconsistent peer with neither route fails with a bounded code.
- [ ] Revocation intent marker is created only for an actual relay revoke.
- [ ] Focused and full native unit commands exit 0.
- [ ] `git diff --check` exits 0.
- [ ] No file outside Scope is modified except generated/ignored test output.
- [ ] `advisor-plans/README.md` row is updated.

## STOP conditions

- Offline-created peers no longer have a non-null `lanBindingId` at runtime.
- A new durable field explicitly records relay enrollment and contradicts the
  proposed `lanBindingId`/URL decision.
- The fix appears to require wiping after a failed relay revoke.
- Any test requires exposing secret LAN binding material to JavaScript.

## Maintenance notes

When direct LAN transport lands, offline-only remains a valid unpair mode. If
relay enrollment is later added to an offline pair, the durable enrollment
state, not UI pairing mode, should become the decision source.
