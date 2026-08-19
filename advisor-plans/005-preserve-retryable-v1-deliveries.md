# Plan 005: Preserve retryable legacy v1 deliveries

> **Executor instructions**: Follow this plan step by step in the primary checkout.
> Do not create a Git worktree. Run every verification command and confirm the
> expected result before moving on. Use test-driven development: first add the
> regression tests and capture their failure against the current production
> ordering, then implement the smallest correction. If a STOP condition occurs,
> stop and report instead of broadening scope. Update the status row for this plan
> in `advisor-plans/README.md` when finished.
>
> **Drift check (run first)**:
> `git diff --stat 2cd9d84..HEAD -- mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/InboundDispatcher.kt mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReplayGuard.kt mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service`
> The current branch already contains release-only commits after `2cd9d84`; none
> should touch these Android inbound paths. If an in-scope path did change, compare
> the live code with the excerpts below and stop on a semantic mismatch.

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: MED
- **Depends on**: none
- **Category**: bug
- **Planned at**: commit `2cd9d84`, 2026-08-19

## Why this matters

The legacy v1 receive path persists a message ID in the 48-hour replay guard
before it knows whether a peer exists, local keys are available, decryption
succeeds, or the authenticated plaintext is valid JSON. A transient local state
or keystore failure therefore turns relay redelivery into a silent drop for 48
hours. Preserve replay defense for authenticated v1 messages while leaving
pre-authentication/local-read failures retryable.

## Current state

- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/InboundDispatcher.kt`
  owns v1 and v2 inbound routing. At lines 43-69, v1 currently does:

  ```kotlin
  if (env.type != "enc") return
  // Replay check BEFORE decrypt — cheap rejection path
  if (ReplayGuard.seenOrMark(ctx, env.msgId)) return
  val peer = PeerStore.load(ctx) ?: return
  val plaintext = try { Encrypter.decrypt(...) } catch (...) { return }
  val inner = try { JSONObject(...) } catch (...) { return }
  ```

- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReplayGuard.kt`
  is explicitly a deprecated v1-only 48-hour DataStore adapter. Do not migrate v2
  or replace the v2 Room journal in this plan.
- `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service`
  contains JVM tests built around small injected ports/stores, for example
  `NotificationMaterializerTest.kt` and `OutboxRepositoryTest.kt`. Follow that
  instance-local fake pattern. Do not add mutable global test hooks.
- v1 posting uses stable notification tag/ID, but unpair is destructive. The safe
  boundary for this fix is: load peer and local crypto, decrypt, parse the inner
  JSON, then atomically consult/mark the legacy replay guard immediately before
  dispatching the authenticated inner event. Do not move the marker after Android
  side effects, which could permit destructive duplicate processing.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Focused tests | `cd mobile/android && ANDROID_HOME=/Users/mak/Library/Android/sdk ./gradlew --no-daemon :twinotify-core:testDebugUnitTest --tests '*LegacyInbound*'` | exit 0; all new tests pass |
| Full native JVM + compile + lint | `cd mobile/android && ANDROID_HOME=/Users/mak/Library/Android/sdk ./gradlew --no-daemon :twinotify-core:testDebugUnitTest :twinotify-core:compileDebugAndroidTestKotlin :twinotify-core:lintDebug` | exit 0; BUILD SUCCESSFUL |
| Diff check | `git diff --check` | exit 0, no output |

## Scope

**In scope**:
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/InboundDispatcher.kt`
- One small new production file under the same service package only if needed to
  create a JVM-testable, instance-local v1 processing seam
- `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/LegacyInboundDispatcherTest.kt` (create)
- `advisor-plans/README.md`

**Out of scope**:
- v2 protocol, Room journal, outbox, receipt, or transport behavior
- Changing v1 wire formats or the 48-hour TTL
- Direct LAN transport/product plans
- UI files
- Physical devices, ADB, radios, EAS, signing, or release workflows
- Mutable global test overrides

## Git workflow

- Work directly on `main`; do not create a branch or worktree.
- Preserve unrelated user changes if the tree is dirty.
- Make one conventional commit after independent review, suggested subject:
  `fix(android): preserve retryable legacy deliveries`
- Do not push.

## Steps

### Step 1: Add ordering regressions and observe RED

Create `LegacyInboundDispatcherTest.kt` around a small instance-local processing
seam. Tests must prove:

1. missing peer returns without calling the replay marker; a second invocation
   after the fake peer becomes available reaches dispatch and marks once;
2. transient local crypto/decrypt failure returns without marking; retry with a
   successful decrypt dispatches and marks once;
3. malformed decrypted JSON returns without marking; corrected redelivery of the
   same message ID can dispatch;
4. a successfully decrypted and parsed inner event marks before dispatch;
5. an already-seen authenticated inner event does not dispatch;
6. the replay marker is not moved after dispatch: a dispatch exception must not
   make an authenticated destructive event eligible for replay.

The first focused run must fail for the current pre-decrypt marker ordering or
for the deliberately missing seam. Preserve the RED output in an ignored local
evidence path if the repository convention supports it; never commit logs.

**Verify**: focused test command above -> non-zero for the expected missing/wrong
ordering only.

### Step 2: Move the v1 replay decision to the authenticated boundary

Implement the smallest instance-local seam needed for the JVM tests and wire
`InboundDispatcher.dispatchV1` through it. Production order must be:

1. parse and structurally accept the encrypted envelope/type;
2. load peer and local key material;
3. decrypt ciphertext;
4. parse plaintext JSON;
5. call `ReplayGuard.seenOrMark(ctx, env.msgId)`;
6. if unseen, dispatch the inner event.

Keep bounded/redacted logging. Never log plaintext, ciphertext, keys, tokens, or
notification content. Preserve existing v1 message behavior and v2 code exactly.

**Verify**: focused test command -> exit 0.

### Step 3: Re-run full native gates and review the exact diff

Run the focused suite a second time with `--rerun-tasks`, then the full native
command. Inspect the diff for v2 changes, global test hooks, raw-data logging, or
marker-after-side-effect ordering. Request independent read-only review before
commit and address every Critical/Important finding with another RED/GREEN cycle.

**Verify**: full native command and `git diff --check` -> exit 0.

## Test plan

- New JVM suite: `LegacyInboundDispatcherTest.kt`, covering the six cases in Step
  1 with fake instance-local functions/ports and call-order assertions.
- Existing `ReplayGuardTest.kt` remains unchanged unless compilation requires a
  mechanical rename. Its TTL behavior is not under change.
- Run full module JVM tests so existing v1 envelope and v2 reliable-delivery tests
  remain green.

## Done criteria

- [ ] Missing peer, decrypt/local-crypto failure, and malformed plaintext do not mark.
- [ ] Corrected redelivery of the same message ID can be processed after each failure.
- [ ] Authenticated, parsed messages mark before any inner side effect.
- [ ] Already-seen authenticated messages never dispatch.
- [ ] v2 code and Room replay semantics are unchanged.
- [ ] Focused suite executes twice and passes.
- [ ] Full native JVM, Android-test compilation, and lint pass.
- [ ] Independent review has no Critical/Important findings.
- [ ] Only in-scope files are committed; no evidence logs are committed.
- [ ] `advisor-plans/README.md` marks Plan 005 DONE.

## STOP conditions

Stop and report if:

- Correctness appears to require marking after a destructive/Android side effect.
- The change requires a v1 wire-format change or v2/Room modification.
- A JVM-testable seam cannot be added without a mutable global override.
- Any in-scope production file has semantically drifted from the current-state excerpt.
- A verification gate fails twice for a reason outside this bounded change.

## Maintenance notes

- This is a legacy v1 reliability correction, not a substitute for the existing
  Android v2 reliable-delivery plan.
- Reviewers should scrutinize the exact replay-marker position and confirm every
  pre-authentication/local-read early return remains unmarked while all dispatched
  authenticated messages are marked first.
- The existing DataStore replay guard's cross-call atomicity is not redesigned
  here; v2 uses the transactional Room journal.
