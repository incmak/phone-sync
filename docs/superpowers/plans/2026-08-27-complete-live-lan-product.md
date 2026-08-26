# Complete Live LAN Product

## TL;DR
> Summary:      Close the two remaining live-product gaps, then turn the direct-route claims into fail-closed, executable two-device scenarios. Pair completion starts LAN-only sync when no relay is configured, and local unpair gives the existing authenticated v2 `unpair` envelope a bounded chance to reach LAN or relay custody before teardown.
> Deliverables: - LAN-only startup from PairSuccess with nonfatal failure semantics; - one bounded, route-neutral local-unpair workflow with relay/LAN custody evidence; - production-backed debug controls and sanitized observations; - direct update, peer-dismiss, call, snapshot/receipt, burst/backpressure, and unpair-during-traffic scenarios; - aggregate CLI/Make gate; - truthful plan and test documentation
> Effort:       XL
> Risk:         High - unpair must preserve cancellation and graceful-call ordering while transport, credentials, and durable state cross a teardown boundary.

## Scope
### Must have
- Before execution, promote this plan verbatim to `docs/superpowers/plans/2026-08-27-complete-live-lan-product.md`; `.omo/plans/complete-live-lan-product.md` is the planner-owned staging path only.
- PairSuccess calls `startLanOnlySyncService()` when `OnboardingState.getRelayUrl()` is empty, after marking pairing complete, and treats either service-start rejection as nonfatal.
- Local user unpair runs this exact sequence: quiesce offline pairing; complete the existing graceful call terminalization/config intent; reserve one message ID; persist exactly one existing encrypted durable v2 `unpair` row; wait at most 5 seconds for matching authenticated LAN or relay custody; quiesce/join the live service; revoke the relay pair when policy requires it; wipe local state in `NonCancellable`.
- A matching `LanTransportEvent.PeerAccepted` or `TransportEvent.RelayAccepted` is the custody success oracle. Peer receipt/application is not required because `unpair` is already a non-receipt control row.
- Timeout, no live route, and non-cancellation delivery errors are fail-open for local security: record a bounded outcome and continue stop/revoke/wipe. An outer `CancellationException` remains the same object and stops before revoke/wipe.
- Concurrent/repeated local requests coalesce onto one in-flight operation and one message ID. Peer-initiated unpair retains its current transport-callback-safe shutdown path.
- Debug-only controls exercise production paths for user mirror dismissal, snapshot emission, and local unpair; all commands remain token-authenticated and content-free.
- Two-device scenarios cover direct update, peer dismissal, synthetic call ringing/active/idle, snapshot/receipt, bounded burst/backpressure, and unpair during traffic. Every scenario proves LAN was authenticated at the stimulus and fails if any required observation is absent.
- A named aggregate plan and Make target run the complete direct-product suite with two explicit distinct serials and an explicit evidence directory.
- Existing direct-LAN plans and `docs/test-scenarios.md` distinguish landed automation from physical results; the physical two-phone runs remain pending until captured.

### Must NOT have (guardrails, anti-slop, scope boundaries)
- No Git worktree, push, device-data clear, Wi-Fi/mobile-data/radio mutation, relay dependency override, or systemic UI redesign.
- No new wire event, schema, Room entity/version, second unpair representation, unscoped mailbox path, or destructive migration.
- No waiting indefinitely for custody and no waiting for a peer receipt for `unpair`.
- No teardown before the unpair row is persisted and its bounded custody attempt completes; no key rotation before relay revocation.
- No swallowed outer cancellation, `runCatching` around cancellation-sensitive code, or conversion of caller cancellation into timeout/unavailable.
- No duplicate graceful-call terminalization, no reordering of call terminal events after `unpair`, and no regression to peer-initiated self-join handling.
- No E2E oracle based only on “mirror appeared,” health text, or an action return code. Route and state-change evidence must come from sanitized provider snapshots.
- No raw device IDs, notification text, package names, canon IDs, message IDs, peer keys, IPs, TLS pins, relay URLs, tokens, or secrets in retained evidence.
- No UI changes in this plan. The repository anti-slop design law therefore has no visual surface to redesign; final scope review must confirm that remains true.

## Verification strategy
> Zero human intervention - all verification is agent-executed.
- Test decision: TDD with Jest, Kotlin/JUnit/coroutines-test, Android instrumentation, and Go `testing` with `-race`; every task starts by observing its focused test fail for the intended reason.
- QA policy: every task has agent-executed scenarios; every implementation task also receives an independent read-only review whose report must end in `APPROVE` before commit.
- Evidence: `<attemptDir>/task-<N>-<slug>.<ext>` - under ulw-loop, `<attemptDir>` is the `currentAttemptDir` from `omo ulw-loop status --json` (`.omo/evidence/ulw/<session>/<goalId>/a<attempt>`); outside ulw-loop use `.omo/evidence/`

## Execution strategy
### Parallel execution waves
> Target 5-8 tasks per wave. This plan has a narrow, stateful critical path, so Wave 1 contains the only safely independent product fixes; later work deliberately serializes the shared debug/executor surfaces.

Wave 1 (no dependencies):
- Task 1: start LAN-only service after offline pairing
- Task 2: deliver local unpair before teardown

Wave 2 (after Wave 1):
- Task 3: expose secure production-backed E2E controls and observations; depends [2]

Wave 3 (after Wave 2):
- Task 4: implement direct update, peer-dismiss, call, and snapshot/receipt scenarios; depends [3]

Wave 4 (after Wave 3):
- Task 5: implement burst/backpressure, unpair-during-traffic, aggregate, verifier, and Make gate; depends [4]

Wave 5 (after Wave 4):
- Task 6: reconcile documentation and plan status; depends [1, 2, 3, 4, 5]

Critical path: Task 2 -> Task 3 -> Task 4 -> Task 5 -> Task 6

### Dependency matrix
| Task | Depends on | Blocks | Can parallelize with |
|------|------------|--------|----------------------|
| 1 | none | 6 | 2 |
| 2 | none | 3, 6 | 1 |
| 3 | 2 | 4, 6 | none |
| 4 | 3 | 5, 6 | none |
| 5 | 4 | 6 | none |
| 6 | 1, 2, 3, 4, 5 | final verification | none |

## Todos
> Implementation + Test = ONE task. Never separate.
> Every task MUST have: References + Acceptance Criteria + QA Scenarios + Commit.

- [ ] 1. Start LAN-only sync after offline PairSuccess

  What to do: In the PairSuccess effect, keep `markOnboardingComplete()` and `setVerifiedComplete(true)` ahead of service startup. Extend the existing nested nonfatal startup block so a stored relay URL calls `startSyncService(relayUrl)` and an absent URL calls `startLanOnlySyncService()`. Add Jest cases for successful LAN-only startup and rejected LAN-only startup, including assertions that relay startup is not called and the screen remains Twinned. Observe the new test fail before changing production. Obtain independent read-only review and resolve every finding before commit.
  Must NOT do: Do not move service startup into the outer pairing-failure boundary, retry in a render loop, change navigation/copy/layout, or alter the relay branch.

  Parallelization: Can parallel: YES | Wave 1 | Blocks: [6] | Blocked by: []

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `mobile/app/pair/success.tsx:15-42` - completion is committed before a nested, intentionally swallowed service-start failure; add the no-relay branch here.
  - Pattern:  `mobile/app/home.tsx:60-75` - established relay-or-LAN-only selection.
  - API/Type: `mobile/modules/twinotify-core/src/TwinotifyCoreModule.ts:81-83` - typed `startLanOnlySyncService` surface.
  - Test:     `mobile/app/pair/__tests__/offlinePairingFlow.test.tsx:350-465` - offline PairSuccess and nonfatal startup patterns.

  Acceptance criteria (agent-executable only):
  - [ ] `cd mobile && npm test -- --runInBand app/pair/__tests__/offlinePairingFlow.test.tsx` passes with assertions for LAN-only success, LAN-only rejection, and unchanged relay behavior.
  - [ ] `cd mobile && npm run typecheck` passes.
  - [ ] An independent reviewer confirms startup errors cannot reach the outer `setVerifiedComplete(false)` path; report ends `APPROVE` at `<attemptDir>/task-1-pair-success-review.md`.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: Offline pairing starts the LAN-only service
    Tool:     bash
    Steps:    cd mobile && npm test -- --runInBand app/pair/__tests__/offlinePairingFlow.test.tsx -t 'starts LAN-only sync after offline pairing' | tee <attemptDir>/task-1-pair-success.txt
    Expected: Jest exits 0; startLanOnlySyncService is called once, startSyncService is not called, and the Twinned state is rendered.
    Evidence: <attemptDir>/task-1-pair-success.txt

  Scenario: LAN-only startup failure is nonfatal
    Tool:     bash
    Steps:    cd mobile && npm test -- --runInBand app/pair/__tests__/offlinePairingFlow.test.tsx -t 'keeps offline pairing complete when LAN-only startup fails' | tee <attemptDir>/task-1-pair-success-error.txt
    Expected: Jest exits 0; the rejected startup promise does not render pairing failure and does not clear verified completion.
    Evidence: <attemptDir>/task-1-pair-success-error.txt
  ```

  Commit: YES | Message: `fix(mobile/pair): start LAN-only sync after pairing` | Files: [`mobile/app/pair/success.tsx`, `mobile/app/pair/__tests__/offlinePairingFlow.test.tsx`]

- [ ] 2. Deliver one durable local-unpair event before transport teardown

  What to do: First write RED unit tests for lifecycle ordering and custody outcomes. Refactor the production local-unpair body out of the Expo function into `LocalUnpairCoordinator`, whose dependencies are explicit suspending functions for prepare, persist, revoke, and wipe. Add `SyncService.prepareLocalUnpair(context): PreparedLocalUnpairService`: it uses the existing graceful call gate to terminalize active calls and persist `CallShutdownConfigIntent(disableCallCapture=false, disableService=true)` without stopping transport, reports whether an active transport job exists, and owns the later idempotent `quiesceAndAwait()` resource join. If no service/transport job exists, return an unavailable prepared handle whose finalizer still stops any residual service without waiting for custody. Add `UnpairCustodyTracker`, owned by the active service, backed by a synchronized map from message ID to one `CompletableDeferred<CustodyRoute>`; `reserve(msgId)` must happen before insertion, `accept(msgId, route)` ignores unknown IDs, and completion/cancellation/timeout always removes the exact entry. Feed matching `LanTransportEvent.PeerAccepted` and `TransportEvent.RelayAccepted` events from the existing `SyncService` callbacks, after their transport-level outbox transitions, into that tracker. Generate one UUID at coordinator entry; reserve it; change `persistUnpair` to accept and return that supplied ID; wait at most `5_000L`; then quiesce. Map unavailable transport, timeout, and non-cancellation failure to explicit content-free outcomes and continue revoke/wipe. Add `LocalUnpairRequestGate`, modeled on `CallCaptureStopRequestGate`, that launches one lazy `Deferred<LocalUnpairResult>` in `moduleScope`; concurrent callers await the same deferred/result, while a later call after completion sees the cleared peer and becomes a no-op rather than emitting another row. Await helpers must preserve an outer `CancellationException` object by identity. Keep peer-initiated unpair on the existing `fromRelayJob=true` shutdown path and prove it neither persists a response unpair nor enters the custody wait. Obtain independent review focused on ordering, cancellation, duplicate suppression, and self-join.
  Must NOT do: Do not add another packet/schema, wait for peer application/receipt, persist after transport stop, use polling against a row that custody deletes, catch `CancellationException` as delivery failure, alter `PendingPeerCancel` ordering, or weaken revoke-before-key-rotation.

  Parallelization: Can parallel: YES | Wave 1 | Blocks: [3, 6] | Blocked by: []

  References (executor has NO interview context - be exhaustive):
  - API/Type: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/TwinotifyCoreModule.kt:599-648` - current local-unpair orchestration and Expo boundary to extract/delegate.
  - Pattern:  `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing/UnpairWorkflow.kt:6-16` - stop, revoke, then `NonCancellable` wipe ordering.
  - Pattern:  `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing/UnpairOps.kt:16-48` - wipe order and old-key requirement.
  - API/Type: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener/DurableCapturePersister.kt:239-295` - existing encrypted v2 `unpair` row; retain `requiresPeerReceipt=false`.
  - API/Type: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/lan/LanTransport.kt:22-30,161-175` - authenticated LAN custody event emitted after route-neutral outbox transition.
  - API/Type: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/RelayTransport.kt:32-43,268-273` - authenticated relay custody event emitted after outbox transition.
  - Pattern:  `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncService.kt:201-225,407-430,652-679,734-769` - graceful-call phases, current stop boundary, resource joins, and both route hooks.
  - Pattern:  `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncService.kt:239-255` - lazy in-flight request coalescing.
  - API/Type: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReliableDeliveryDao.kt:119-123,552-578` - deterministic outbox order and custody deletion of non-receipt controls.
  - Test:     `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/pairing/UnpairWorkflowTest.kt:29-384` - ordering, terminalization failure, cancellation identity, self-join, and LAN identity coverage to preserve/extend.
  - Test:     `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/LiveTransportRoutesTest.kt:239-300` - relay hook/event-order pattern.
  - External: `https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/with-timeout-or-null.html` - inner timeout semantics; explicitly distinguish timeout from outer cancellation.

  Acceptance criteria (agent-executable only):
  - [ ] New focused tests prove exact order `graceful-call -> reserve -> persist-unpair -> custody/failure -> quiesce/join -> revoke -> wipe` for LAN, relay, timeout, unavailable route, and non-cancellation failure.
  - [ ] Tests prove call terminal rows precede the unpair row; terminalization/config failure never persists unpair; concurrent calls persist one unpair; stale/wrong-ID custody cannot release the waiter.
  - [ ] Tests prove the exact caller `CancellationException` instance escapes and neither revoke nor wipe runs; the inner 5-second timeout returns a timeout outcome and does not escape as cancellation.
  - [ ] Tests prove a prepared handle reports unavailable only when there is no active transport job, ignores custody for unknown/stale IDs, removes its waiter after every terminal path, and quiesces resources at most once.
  - [ ] A peer-initiated unpair regression test proves `fromRelayJob=true` remains self-join-safe and emits no second outbound unpair/custody wait.
  - [ ] Existing peer-unpair self-join and graceful-call shutdown tests remain green.
  - [ ] `cd mobile && npx expo prebuild --platform android --clean --no-install && cd android && ./gradlew --no-daemon testDebugUnitTest --tests 'co.twinotify.core.pairing.*' --tests 'co.twinotify.core.service.*' --tests 'co.twinotify.core.listener.*'` passes.
  - [ ] An independent reviewer report ends `APPROVE` at `<attemptDir>/task-2-unpair-review.md` and specifically confirms no duplicate, no indefinite wait, old credentials remain live through custody/revoke, and peer-initiated teardown is unchanged.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: LAN and relay custody release teardown exactly once
    Tool:     bash
    Steps:    cd mobile && npx expo prebuild --platform android --clean --no-install && cd android && ./gradlew --no-daemon testDebugUnitTest --tests 'co.twinotify.core.pairing.LocalUnpairCoordinatorTest.*Custody*' | tee <attemptDir>/task-2-unpair.txt
    Expected: Gradle exits 0; both route variants assert one supplied UUID, one persisted row, matching custody, then one stop/revoke/wipe sequence.
    Evidence: <attemptDir>/task-2-unpair.txt

  Scenario: Timeout, unavailable peer, duplicate request, and cancellation stay bounded
    Tool:     bash
    Steps:    cd mobile/android && ./gradlew --no-daemon testDebugUnitTest --tests 'co.twinotify.core.pairing.LocalUnpairCoordinatorTest.*Timeout*' --tests 'co.twinotify.core.pairing.LocalUnpairCoordinatorTest.*Unavailable*' --tests 'co.twinotify.core.pairing.LocalUnpairCoordinatorTest.*Concurrent*' --tests 'co.twinotify.core.pairing.LocalUnpairCoordinatorTest.*Cancellation*' | tee <attemptDir>/task-2-unpair-error.txt
    Expected: Gradle exits 0; non-cancellation failures proceed to wipe once, concurrent requests insert once, and cancellation preserves object identity and skips revoke/wipe.
    Evidence: <attemptDir>/task-2-unpair-error.txt
  ```

  Commit: YES | Message: `fix(android): deliver unpair before secure teardown` | Files: [`mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/TwinotifyCoreModule.kt`, `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing/LocalUnpairCoordinator.kt`, `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing/UnpairWorkflow.kt`, `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener/DurableCapturePersister.kt`, `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncService.kt`, `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/UnpairCustodyTracker.kt`, `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/pairing/LocalUnpairCoordinatorTest.kt`, `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/pairing/UnpairWorkflowTest.kt`, `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/listener/DurableCapturePersisterTest.kt`, `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/UnpairCustodyTrackerTest.kt`, `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/LiveTransportRoutesTest.kt`]

- [ ] 3. Complete authenticated v2 unpair handling, then add secure production-backed E2E stimuli and sanitized observations

  What to do: First add RED focused JVM regressions proving an authenticated v2 `unpair` control dispatches the existing peer-unpair workflow instead of `unsupported_event`, and proving LAN cannot cancel its own acceptance write. Split only the final service-stop request from the existing peer-unpair sequence: authenticated shutdown and `NonCancellable` wipe must complete before custody is returned; the result carries a one-shot post-custody finalizer that requests service stop. `LanTransport` must attempt `LanFrame.Accepted` first and then run that finalizer exactly once even if the write fails or its coroutine is cancelled. The relay dispatch wrapper runs the same finalizer immediately after dispatch because relay custody already exists before `relay.deliver`. Keep legacy v1's immediate unpair-and-stop path unchanged. Add RED Android security/instrumentation tests for token-authenticated debug-only commands that (a) cancel the newest E2E-created mirror by its persisted local tag/ID without adding `PendingPeerCancel`, so the real notification-listener removal path emits user dismissal; (b) ask the active `SyncService` to emit its production snapshot digest; and (c) invoke the exact production local-unpair entrypoint. Extract one process-wide local-unpair request gate and one production orchestration implementation used by both the Expo AsyncFunction and authenticated debug command; callers may inject their own offline-pairing quiesce callback, but peer/config/reserve/persist/custody/revoke/wipe logic must not be duplicated. Concurrent UI/debug callers share one deferred, UUID, row, and result with the existing per-waiter cancellation leases. Feed the user-dismiss observation only from the real notification-listener removal path after both peer-cancel filters and a successful durable `RemoveCommand` submission; source-app removal, peer dismissal, suppressed/no-emit reasons, and failed submissions must not increment it. Keep command results bounded and content-free. Extend the state provider with explicit-presence, bounded observations needed by later oracles: paired boolean, custody counts by route/event type, peer-receipt count, snapshot digest/begin/end counts, user-dismiss count, authenticated v2 unpair inbound/outcome, active/peak queue count and bytes. Capture custody event type before a route transition can delete the outbox row; do not infer it by querying a deleted row or silently report zero. Derive from existing status/Room state or new in-memory debug counters fed by production transitions; do not persist secrets or add a Room migration. Update the Android command/state allowlists and Go observation parser. Missing, null, unknown, over-limit, or malformed required fields must return an error, never zero-value success. Extend the evidence sanitizer closed world. Obtain independent security/privacy review.
  Must NOT do: Do not acknowledge v2 `unpair` before authenticated shutdown/wipe completes, call `stopService` before the LAN acceptance write, create a crash gap by acknowledging before wipe, skip the final stop when the acceptance write fails, send a response unpair, alter the existing self-join-safe peer teardown, call `MirrorDismisser.dismiss` for simulated user dismissal because it intentionally plants the peer-cancel tombstone, mutate radios, clear app data, expose raw identifiers/content, or create E2E-only delivery semantics.

  Parallelization: Can parallel: NO | Wave 2 | Blocks: [4, 6] | Blocked by: [2]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `mobile/modules/twinotify-core/android/src/debug/java/co/twinotify/core/e2e/E2eControlReceiver.kt:80-130,291-359` - token-authenticated debug command allowlist and production service controls.
  - Pattern:  `mobile/modules/twinotify-core/android/src/debug/java/co/twinotify/core/e2e/E2eStateProvider.kt:51-122` - bounded SQL/status snapshot and hashed canonical/activity records.
  - Invariant: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener/TwinotifyNotificationListener.kt:126-178` - user mirror dismissal must avoid the peer tombstone so the real removal path emits `notif.cancel`.
  - Test:     `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/listener/UserDismissObservationTest.kt` - new focused production-listener observation regression; write it before adding the counter hook.
  - Invariant: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/MirrorDismisser.kt:10-35` - peer-driven cancel deliberately sets `PendingPeerCancel`; do not reuse it for a user-dismiss stimulus.
  - API/Type: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncService.kt:498-528,754-783` - production snapshot coordinator and existing digest triggers.
  - API/Type: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/TwinotifyCoreModule.kt:600-670` and `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing/LocalUnpairCoordinator.kt` - consolidate the UI and debug callers onto one production orchestration/gate without weakening Task 2 cancellation leases.
  - Test:     `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/pairing/LocalUnpairCoordinatorTest.kt` - extend with cross-caller shared-operation/UUID/row/result and cancellation regressions.
  - API/Type: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/InboundDispatcher.kt:93-190,387-405` - legacy unpair already reaches the self-join-safe workflow, while v2 control routing must explicitly delegate to the same handler and return authenticated custody.
  - Test:     `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/InboundDispatcherControlTest.kt` - new focused v2 unpair routing/custody regression; write it before production changes.
  - API/Type: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/lan/LanTransport.kt:126-158` - direct custody frame is written only after dispatch; the v2 unpair post-custody finalizer must run after this write attempt, never before it.
  - Test:     `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/lan/LanTransportTest.kt` - add exact ordering, write-failure, cancellation, and one-shot finalizer regressions.
  - API/Type: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/RelayTransport.kt:248-275,360-376` - correlate accepted IDs to the event type captured before `relay.put`; the accepted transition may delete non-receipt controls.
  - Test:     `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/RelayTransportTest.kt` - add typed accepted-event regressions for retained and deleted rows plus unknown/stale IDs.
  - Test:     `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/e2e/E2eControlSecurityTest.kt:111-180` - closed command/state surface and authentication tests.
  - API/Type: `e2e/internal/scenario/scenario.go:174-267` - host observation contract and parser.
  - Pattern:  `e2e/internal/scenario/evidence.go:108-245` - retained-evidence allowlist and sanitizer.
  - Test:     `e2e/internal/scenario/route_evidence_test.go:1-180` - fail-closed evidence tests.

  Acceptance criteria (agent-executable only):
  - [ ] Instrumentation tests prove unauthenticated/unknown commands fail, forbidden parameters are rejected, result files contain no raw content, and all new commands call production seams.
  - [ ] Focused JVM tests prove authenticated v2 `unpair` invokes shutdown/wipe exactly once before returning custody, propagates cancellation without acknowledgement, leaves legacy v1 behavior unchanged, writes LAN acceptance before requesting final service stop, and runs the post-custody finalizer exactly once even on write failure/cancellation.
  - [ ] A focused relay-wrapper test proves the same post-custody finalizer runs after authenticated relay delivery dispatch without requiring a LAN frame and cannot run twice.
  - [ ] The mirror-dismiss test proves no peer-cancel tombstone is inserted and the real listener path captures one user dismissal.
  - [ ] Focused listener tests prove the user-dismiss counter increments only after a successful durable own-mirror remove submission, never for peer tombstones, source-app removals, suppressed/no-emit reasons, or failed submissions.
  - [ ] Route tests prove custody counters retain the exact event type even when the accepted transition deletes the outbox row; unpair and peer-receipt custody cannot disappear into a null post-transition lookup.
  - [ ] Focused tests prove concurrent Expo/debug callers use one process gate and production orchestration, persist exactly one UUID/row, share one result, and retain the Task 2 per-waiter cancellation behavior.
  - [ ] `go test ./internal/scenario -race -count=1` rejects each missing/unknown/malformed new observation independently and the sanitizer rejects every new unapproved key.
  - [ ] `cd mobile/android && ./gradlew --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=co.twinotify.core.e2e.E2eControlSecurityTest` passes on one connected API-compatible device without clearing data or changing radios.
  - [ ] Independent review ends `APPROVE` at `<attemptDir>/task-3-e2e-control-review.md`, covering debug-only packaging, authentication, privacy, and production-path fidelity.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: Authenticated debug controls expose only bounded production observations
    Tool:     bash
    Steps:    cd mobile/android && ./gradlew --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=co.twinotify.core.e2e.E2eControlSecurityTest | tee <attemptDir>/task-3-e2e-control.txt
    Expected: Gradle exits 0; dismiss, snapshot, and unpair commands pass authentication and closed-world payload assertions.
    Evidence: <attemptDir>/task-3-e2e-control.txt

  Scenario: Missing observation and secret-bearing payloads fail closed
    Tool:     bash
    Steps:    cd e2e && go test ./internal/scenario -race -count=1 -run 'TestParseObservation|TestEvidenceSanitizer' | tee <attemptDir>/task-3-e2e-control-error.txt
    Expected: Go exits 0; fixtures missing each required field or containing raw IDs/text/unknown keys are rejected.
    Evidence: <attemptDir>/task-3-e2e-control-error.txt
  ```

  Commit: YES | Message: `test(e2e): expose secure direct-route observations` | Files: [`mobile/modules/twinotify-core/android/src/debug/java/co/twinotify/core/e2e/E2eControlReceiver.kt`, `mobile/modules/twinotify-core/android/src/debug/java/co/twinotify/core/e2e/E2eStateProvider.kt`, `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/TwinotifyCoreModule.kt`, `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/lan/LanTransport.kt`, `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener/TwinotifyNotificationListener.kt`, `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing/LocalUnpairCoordinator.kt`, `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/InboundDispatcher.kt`, `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/RelayTransport.kt`, `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncService.kt`, `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/lan/LanTransportTest.kt`, `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/listener/UserDismissObservationTest.kt`, `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/pairing/LocalUnpairCoordinatorTest.kt`, `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/InboundDispatcherControlTest.kt`, `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/LiveTransportRoutesTest.kt`, `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/RelayTransportTest.kt`, `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/e2e/E2eControlSecurityTest.kt`, `e2e/internal/scenario/scenario.go`, `e2e/internal/scenario/scenario_test.go`, `e2e/internal/scenario/evidence.go`, `e2e/internal/scenario/route_evidence.go`, `e2e/internal/scenario/route_evidence_test.go`]

- [ ] 4. Implement fail-closed direct update, peer-dismiss, call, and snapshot/receipt scenarios

  What to do: Write RED Go plan/executor tests for four named scenarios: `lan-direct-update`, `lan-direct-peer-dismiss`, `lan-direct-call-state`, and `lan-direct-snapshot-receipt`. Add typed executor actions for the Task 3 controls. Every delivery-producing action records a contemporaneous authenticated-LAN generation; every predicate compares before/after counters or hashed canonical state, not static health. Update must observe sequence 3 on B and terminal receipt/custody on A. Peer dismissal must observe B user-dismiss capture, A exact source cancellation, no mirror resurrection, and both outboxes terminal. Refactor the existing call-state runner into the normal `ScenarioResult`/evidence path; assert LAN at each injected ringing/active/idle stimulus, B canonical sequence/state transitions 1/RINGING, 2/ACTIVE, 3/IDLE, and terminal receipt convergence. Because the durable row collapses ringing and active to `ACTIVE`, first add an Android instrumentation RED and expose a closed-world, content-free canonical `semantic_state` (`RINGING`, `ACTIVE`, or `IDLE` only for `call:` rows; absent for notification rows) from the debug-only state provider. Derive it locally from the non-exported canonical namespace and bounded call payload, reject malformed or unknown values, parse it fail-closed in the host observation contract, and never expose the raw canonical ID or call payload. The existing `EMIT_SNAPSHOT` command emits only a digest and correctly produces no begin/end when peers already match, so add a separate authenticated, parameter-free `FORCE_REPAIR_SNAPSHOT` debug command. It must call the production `SnapshotCoordinator.onDigest` forced-repair path with a locally derived, deliberately mismatched content-free digest; it must not write snapshot rows, enqueue protocol events itself, or accept caller data. Snapshot must then observe a new digest plus matching begin/end/application and peer receipt over LAN. Add negative tests removing each oracle observation and prove failure. Obtain independent review of oracle sufficiency, provider privacy, and production-path fidelity of the forced repair command.
  Must NOT do: Do not retain the unsupported UI actions, special-case call-state outside normal evidence, accept a route sampled only at scenario end, or pass on an action return code without observed state change.

  Parallelization: Can parallel: NO | Wave 3 | Blocks: [5, 6] | Blocked by: [3]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `e2e/internal/scenario/scenario.go:39-172` - named plans, aggregate shape, and unique-tag rewriting.
  - Pattern:  `e2e/internal/scenario/executor.go:18-167,216-325,355-473` - action validation, terminal snapshots, route evidence, action execution, and predicates.
  - API/Type: `e2e/internal/scenario/call_state.go:32-120` - existing typed synthetic ring/active/idle flow to integrate.
  - Pattern:  `e2e/cmd/twinotify-e2e/main.go:124-167` - current call-state special case and pre-ADB validation to remove/generalize.
  - Test:     `e2e/internal/scenario/scenario_test.go:291-481` - observed evidence, executor reuse, and terminal-snapshot failure tests.
  - Test:     `e2e/internal/scenario/call_state_test.go:1-140` - synthetic call bridge/state assertions.
  - Test:     `e2e/internal/scenario/route_predicate_test.go:72-110` - executable LAN plan contract.
  - API/Type: `mobile/modules/twinotify-core/android/src/debug/java/co/twinotify/core/e2e/E2eStateProvider.kt:98-117` - debug-only hashed canonical projection; add only the bounded call semantic enum, never raw IDs or payloads.
  - Test:     `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/e2e/E2eControlSecurityTest.kt` - fail-closed provider privacy and semantic-state projection tests.
  - API/Type: `mobile/modules/twinotify-core/android/src/debug/java/co/twinotify/core/e2e/E2eControlReceiver.kt` - authenticated closed-world debug command registry; add a parameter-free forced-repair trigger only.
  - API/Type: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncService.kt` - service-owned production snapshot coordinator; expose a bounded debug bridge that derives a guaranteed mismatch from `localDigest` and delegates to `onDigest(..., force=true)`.
  - Pattern:  `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SnapshotCoordinator.kt:30-80,300-360` - digest/begin/item/end protocol and convergence semantics.

  Acceptance criteria (agent-executable only):
  - [ ] `cd e2e && go test ./internal/scenario ./cmd/twinotify-e2e -race -count=1` passes and `ValidateExecutablePlan` accepts all four names with zero unsupported action.
  - [ ] Table tests delete or stale each required route, sequence, dismissal, snapshot, receipt, and terminal observation and assert the scenario fails with a stable content-free error code.
  - [ ] Call-state produces a standard sanitized `ScenarioResult` with route evidence rather than bypassing the executor/evidence pipeline.
  - [ ] Android provider tests prove call rows expose only `RINGING|ACTIVE|IDLE`, notification rows omit `semantic_state`, malformed/unknown call payloads fail closed, and no raw canonical ID or payload is present.
  - [ ] Android tests prove `FORCE_REPAIR_SNAPSHOT` is authenticated, rejects every parameter, and delegates only to the production forced-repair path; it never fabricates begin/end observations or writes snapshot state directly.
  - [ ] Independent reviewer report ends `APPROVE` at `<attemptDir>/task-4-direct-oracles-review.md` and confirms no scenario could pass entirely over relay or with a missing state transition.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: All four direct semantic plans are executable in the deterministic bridge
    Tool:     bash
    Steps:    cd e2e && go test ./internal/scenario ./cmd/twinotify-e2e -race -count=1 -run 'TestLanDirect(Update|PeerDismiss|CallState|SnapshotReceipt)' | tee <attemptDir>/task-4-direct-oracles.txt
    Expected: Go exits 0; each plan records authenticated LAN evidence plus its semantic and terminal observations.
    Evidence: <attemptDir>/task-4-direct-oracles.txt

  Scenario: Every absent semantic or route observation fails closed
    Tool:     bash
    Steps:    cd e2e && go test ./internal/scenario -race -count=1 -run 'TestLanDirect.*RejectsMissing' | tee <attemptDir>/task-4-direct-oracles-error.txt
    Expected: Go exits 0 because each deliberately incomplete fixture returns a stable failure rather than passed evidence.
    Evidence: <attemptDir>/task-4-direct-oracles-error.txt
  ```

  Commit: YES | Message: `test(e2e): prove direct product semantics` | Files: [`mobile/modules/twinotify-core/android/src/debug/java/co/twinotify/core/e2e/E2eStateProvider.kt`, `mobile/modules/twinotify-core/android/src/debug/java/co/twinotify/core/e2e/E2eControlReceiver.kt`, `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncService.kt`, `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/e2e/E2eControlSecurityTest.kt`, `e2e/internal/scenario/scenario.go`, `e2e/internal/scenario/executor.go`, `e2e/internal/scenario/call_state.go`, `e2e/internal/scenario/scenario_test.go`, `e2e/internal/scenario/call_state_test.go`, `e2e/internal/scenario/route_predicate_test.go`, `e2e/cmd/twinotify-e2e/main.go`, `e2e/cmd/twinotify-e2e/main_test.go`]

- [ ] 5. Add direct stress/unpair scenarios, aggregate evidence verifier, and Make gate

  What to do: Start with RED Go and shell tests. Add `lan-direct-burst-backpressure` using a fixed bounded count below the production 2,000-item cap (default 256, CLI-overridable only within 2..1,000): post unique tags without sleeps, observe peak queue count/bytes rise but remain within production caps, then require all unique canonical sequences, receipts, and terminal zero. Add `lan-direct-unpair-during-traffic`: start a bounded burst, invoke production local unpair on A while work is pending, observe A's content-free custody outcome, observe B's authenticated inbound unpair, and require `paired=false`, stopped service, zero active reliable state, and no post-wipe recreation on both devices. Create aggregate `lan-product-correctness` with unique tags and children for existing direct delivery/origin dismiss plus all Task 4/5 scenarios. Extend evidence so every child is retained separately with route/counter proof. Add a strict verifier script and a `make e2e-lan-product` target requiring distinct explicit serials and an evidence directory; it must only invoke the CLI and verifier, never change radios or clear data. Add Make/shell self-tests for missing/equal serials, missing evidence, failed child, missing observation, and full synthetic pass. Obtain independent review of bounds, cleanup, and Make safety.
  Must NOT do: Do not exceed mailbox caps, use unbounded goroutines, hide per-child evidence inside one aggregate status, continue after a failed child, clear device state, re-pair automatically after unpair, or mutate network/radio state.

  Parallelization: Can parallel: NO | Wave 4 | Blocks: [6] | Blocked by: [4]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `e2e/internal/scenario/scenario.go:141-153` - existing aggregate/child plan model and unique tags.
  - Pattern:  `e2e/internal/scenario/executor.go:336-353` - fail-fast aggregate executor.
  - Pattern:  `e2e/internal/scenario/evidence.go:12-96` - per-run result/evidence writer.
  - Test:     `e2e/internal/scenario/route_evidence_test.go:1-180` - bounded route evidence and sanitizer tests.
  - API/Type: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReliableDeliveryDao.kt:119-140` - ordered sendable rows and active queue metrics.
  - Invariant: `relay/internal/store/mailbox_store.go:1-120` - production per-recipient item/byte limits to mirror in host assertions; verify exact exported constants before naming them in code.
  - Pattern:  `Makefile:1-64` - PHONY registration, real tabs, explicit distinct-serial and evidence-directory guards.
  - Pattern:  `e2e/scripts/preflight_test.sh:1-220` - shell self-test conventions.
  - Pattern:  `scripts/verify-offline-pairing-evidence.sh:1-220` - fail-closed evidence verifier and `--self-test` convention.

  Acceptance criteria (agent-executable only):
  - [ ] Go tests prove the burst enforces 2..1,000 input bounds, records a nonzero peak, never exceeds production count/byte caps, observes all unique results, and terminates at zero.
  - [ ] Go tests prove unpair during nonzero traffic observes custody/inbound unpair before both unpaired terminal snapshots, and fails if either side recreates peer/outbox state.
  - [ ] `Plan("lan-product-correctness")` contains each required direct child once with unique tags; aggregate stops at first failed child and evidence retains every completed child.
  - [ ] `make e2e-lan-product` rejects missing/equal serials and missing evidence directory before contacting ADB; its recipe contains no radio, network, package-clear, or data-clear command.
  - [ ] `cd e2e && go test ./... -race -count=1 && go vet ./...`, the verifier self-test, and the Make target self-test all pass.
  - [ ] Independent reviewer report ends `APPROVE` at `<attemptDir>/task-5-lan-gate-review.md`, covering stress bounds, fail-fast cleanup, evidence completeness, and prohibited device mutations.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: Aggregate direct-product gate passes a complete deterministic run
    Tool:     bash
    Steps:    cd e2e && go test ./... -race -count=1 -run 'TestLanProductCorrectness|TestLanDirectBurst|TestLanDirectUnpairDuringTraffic' && cd .. && ./scripts/verify-lan-product-evidence.sh --self-test | tee <attemptDir>/task-5-lan-gate.txt
    Expected: Go and verifier exit 0; every required child has independent authenticated-LAN and semantic evidence and the burst/unpair bounds hold.
    Evidence: <attemptDir>/task-5-lan-gate.txt

  Scenario: Make preflight and evidence verification fail closed
    Tool:     bash
    Steps:    ./e2e/scripts/lan_product_target_test.sh | tee <attemptDir>/task-5-lan-gate-error.txt
    Expected: Script exits 0 after proving missing/equal serials, missing evidence, absent observations, and a failed child all make the target/verifier exit nonzero before a pass fixture is accepted.
    Evidence: <attemptDir>/task-5-lan-gate-error.txt
  ```

  Commit: YES | Message: `test(e2e): gate the complete direct LAN product` | Files: [`e2e/internal/scenario/scenario.go`, `e2e/internal/scenario/executor.go`, `e2e/internal/scenario/evidence.go`, `e2e/internal/scenario/scenario_test.go`, `e2e/internal/scenario/route_evidence_test.go`, `e2e/cmd/twinotify-e2e/main.go`, `e2e/cmd/twinotify-e2e/main_test.go`, `scripts/verify-lan-product-evidence.sh`, `e2e/scripts/lan_product_target_test.sh`, `Makefile`]

- [ ] 6. Reconcile direct-LAN documentation and verification status

  What to do: After Tasks 1-5 land, update the two active direct-LAN plans with checked implementation tasks and exact commit hashes, but leave physical handset acceptance unchecked until evidence exists. Rewrite stale manual scenarios to describe current v2 durable delivery, LAN-first/relay fallback, peer-notifying unpair, new scenario names, exact aggregate/Make invocation, evidence location, and safety prerequisites. State that automation is implemented and host-tested while the requested two-physical-phone run remains pending. Remove claims that LAN is absent or unpair cannot notify the peer. Run link/path/command checks and an independent documentation review.
  Must NOT do: Do not claim a physical pass, invent evidence, mark operator-only radio/no-uplink checks complete, rewrite historical superseded plans, or fold dependency override/UI redesign into this status.

  Parallelization: Can parallel: NO | Wave 5 | Blocks: [final verification] | Blocked by: [1, 2, 3, 4, 5]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `docs/superpowers/plans/2026-08-20-direct-lan-delivery.md:23-164` - stale unchecked implementation/task-9 physical acceptance status.
  - Pattern:  `docs/superpowers/plans/2026-08-26-live-direct-lan-service-integration.md:19-52` - landed service integration still shown unchecked; preserve physical-run distinction.
  - Pattern:  `docs/test-scenarios.md:18-225,233-405` - stale Phase 2/3 and no-peer-unpair claims plus current physical/E2E section.
  - Pattern:  `e2e/README.md:1-15` - currently documents only call-state usage.
  - API/Type: `Makefile:e2e-lan-product` - exact final invocation to document after Task 5.
  - Test:     `scripts/verify-host-workflows.sh:1-220` - documentation/path command consistency gate.

  Acceptance criteria (agent-executable only):
  - [ ] `rg -n "Unpair doesn't notify peer|doesn't push an explicit unpair|LAN transport does not|Phase 3 doesn't" docs/test-scenarios.md docs/superpowers/plans/2026-08-20-direct-lan-delivery.md docs/superpowers/plans/2026-08-26-live-direct-lan-service-integration.md` returns no stale live-state claim.
  - [ ] Every checked task cites a reachable commit containing its implementation; every physical acceptance item remains unchecked and says `pending physical two-phone run`.
  - [ ] `make host-verify` passes, including new LAN evidence/target self-tests.
  - [ ] Independent documentation reviewer report ends `APPROVE` at `<attemptDir>/task-6-docs-review.md` and confirms automation vs physical evidence is unambiguous.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: Documented direct-product commands and paths are executable
    Tool:     bash
    Steps:    make host-verify | tee <attemptDir>/task-6-docs.txt
    Expected: Make exits 0; documented scenario names, scripts, targets, and host tests resolve and pass.
    Evidence: <attemptDir>/task-6-docs.txt

  Scenario: Documentation cannot overstate physical verification
    Tool:     bash
    Steps:    ./scripts/verify-lan-product-evidence.sh --check-doc-status docs/test-scenarios.md docs/superpowers/plans/2026-08-20-direct-lan-delivery.md docs/superpowers/plans/2026-08-26-live-direct-lan-service-integration.md | tee <attemptDir>/task-6-docs-error.txt
    Expected: Verifier exits 0 only when physical items remain explicitly pending and no stale no-LAN/no-peer-unpair claim remains.
    Evidence: <attemptDir>/task-6-docs-error.txt
  ```

  Commit: YES | Message: `docs: report direct LAN readiness truthfully` | Files: [`docs/superpowers/plans/2026-08-20-direct-lan-delivery.md`, `docs/superpowers/plans/2026-08-26-live-direct-lan-service-integration.md`, `docs/test-scenarios.md`, `e2e/README.md`]

## Final verification wave (MANDATORY - after all implementation tasks)
> Runs in PARALLEL. ALL must APPROVE. Surface results to the caller and wait for an explicit "okay" before declaring complete.
- [ ] F1. Plan compliance audit - every task done, every acceptance criterion met; verify commit/file scope and evidence inventory against this plan.
- [ ] F2. Code quality review - diagnostics clean, coroutine cancellation/ownership idioms preserved, no dead code, and `make host-verify` plus `cd mobile/android && ./gradlew --no-daemon lintDebug testDebugUnitTest compileDebugAndroidTestKotlin assembleDebug` pass.
- [ ] F3. Real manual QA - execute every QA scenario above with evidence captured; when two devices are connected, run `E2E_DEVICE_A='<serial-a>' E2E_DEVICE_B='<serial-b>' E2E_LAN_EVIDENCE_DIR='<attemptDir>/physical-lan' make e2e-lan-product` without clearing data or changing radios. If physical devices are unavailable, F3 must report `PENDING`, never approve or imply a pass.
- [ ] F4. Scope fidelity - nothing extra shipped beyond Must-Have; no dependency override, systemic UI work, radio/data-clear behavior, schema/database migration, raw evidence, or anti-slop visual regression was introduced.

## Commit strategy
- One logical change per commit. Conventional Commits (`<type>(<scope>): <subject>` body + footer).
- Atomic: every commit builds and passes tests on its own.
- No "WIP" / "fix typo squash later" commits on the final branch - clean up before merge.
- Promote this staging plan verbatim before Task 1, commit the promoted copy with Task 1, and reference it in every implementation commit footer as `Plan: docs/superpowers/plans/2026-08-27-complete-live-lan-product.md`; do not commit the `.omo/` staging copy.
- Execute in the primary checkout only. Never create a worktree or push. Do not commit `.omo/evidence/`, generated `mobile/android/`, secrets, device serials, or physical-run artifacts containing unsanitized state.

## Success criteria
- All Must-Have shipped; all automated QA scenarios pass with captured evidence; F1, F2, and F4 approve; F3 approves only after the physical two-phone aggregate succeeds, otherwise remains explicitly pending; commit history is clean; the caller sees all four final-review results and says `okay` before completion is declared.
