# Two-emulator backlog verification

**Status:** Emulator scope complete
**Targets:** `emulator-5554` and `emulator-5558`, Android 17 / API 37
**Build:** local debug APK and local relay from the current checkout

## Goal and acceptance

Exercise every backlog acceptance condition that two API-compatible Android
emulators can prove, fix emulator-valid regressions at their root, and update
the backlog with reproducible evidence. The pass is accepted when each planned
row has a sanitized pass/fail artifact or an explicit emulator limitation, all
new regressions are covered red-first, relevant gates pass, and no result is
presented as physical/OEM evidence.

## Scope

- authenticated relay pairing between two distinct emulator identities;
- notification post, update, independent dismissal, action, history, receipt,
  and metric state through production Android paths and the debug-only closed
  fixture controls;
- foreground-notification self-filtering, navigation, and visible state;
- force-stop, app relaunch, in-place package update, and emulator reboot
  recovery while preserving paired state;
- paired Home, Settings, History, light/dark, large-text, and accessibility
  structure where the debug app can be driven with Metro.

## Non-goals and evidence limits

- MIUI/HyperOS launcher, shade, lock-screen, battery, auto-start, and task-kill
  behavior;
- real camera ergonomics, one-handed hardware use, or real TalkBack speech;
- real Wi-Fi multicast/mDNS, no-uplink topology, radio handoff, Doze timing,
  battery measurement, or physical latency claims;
- the owner decisions and production inputs blocking PB-005, PB-010, and
  PB-011;
- notification content, pairing secrets, network identifiers, keys, tokens,
  or protocol material in retained evidence.

## Testing strategy

The testing pyramid for this pass is:

1. **Unit and contract tests:** existing Jest, Kotlin JVM, Go, shell, manifest,
   migration, and asset contracts remain the broad base.
2. **Android integration tests:** focused instrumentation covers native
   notification, storage, lifecycle, and the real debug-control boundary.
3. **Two-emulator E2E:** the real relay, reciprocal pairing, notification
   listener, foreground service, Room state, receipts, and fixture app are the
   primary acceptance lane.
4. **Manual visual and interaction review:** only visible states that materially
   depend on Android rendering are inspected, with every displayed control
   actually exercised.

Coverage targets are all non-blocked emulator-reachable backlog rows and every
regression found during this run. Code-coverage percentages are not a release
signal for this pass; state transitions and product-visible outcomes are.

## Execution matrix

| Backlog | Emulator scenario | Test type | Pass condition | Emulator-only gap |
| --- | --- | --- | --- | --- |
| PB-001 | two identities; post, update, concurrent mirrors, local and peer dismissal | two-emulator E2E | exact canonical counts converge with no duplicate or cross-cancel | real WhatsApp presentation and OEM shade |
| PB-002 | paired service notification, tap, own-package non-mirroring, mirror-dismiss echo check | E2E plus visible Android inspection | truthful ongoing notification opens one Twinotify task and creates no peer mirror | OEM shade/lock screen |
| PB-003 | received notification enters grouped recent/history state and clear controls work | E2E plus paired UI | history is useful, private, retained, and explicitly clearable | none expected beyond OEM rendering |
| PB-004 | launcher and themed icon in supported emulator modes | visible Android inspection | mark is legible and unclipped | POCO/MIUI launchers |
| PB-006 | complete notification-action aggregate including secondary detail action | two-emulator fixture E2E | all action children terminate correctly and detail routing is non-empty | OEM action ordering/layout |
| PB-007 | paired Home, Settings, History, recovery, unpair, large text, dark mode, accessibility tree | paired UI journey | primary tasks are understandable and every available control works | real TalkBack speech, one-handed/OEM paths, PB-005 |
| PB-008 | force-stop/open, package replace, reboot, resumed delivery, paused preservation | lifecycle E2E | pair and enabled intent persist, one service returns, delivery resumes without duplicates | OEM background policy and signed production upgrade |
| PB-009 | authenticated post plus first peer receipt and repeated receipt deduplication | E2E plus state reconciliation | visible count matches verified first receipts and latency distinguishes absent evidence from zero | physical latency performance |
| PB-005 | not executed | owner-controlled | blocked pending approved hostname and policy | production decision |
| PB-010 | not executed | owner-controlled | blocked pending fallback security/UX decision | production decision |
| PB-011 | not executed | owner-controlled | blocked pending Android 17 privacy/UX decision | production decision |

## Evidence record

- Clean reciprocal relay pairing passed on both API 37 emulators with protocol
  floor 2, authenticated route state, zero pending local/peer custody, and
  recent peer evidence.
- The first pairing attempt found a debug-harness regression: the receiver used
  release URL policy and the host supplied `10.0.2.2`. A real loopback-socket
  instrumentation regression failed with `operation_failed`, then passed after
  the debug receiver explicitly selected debug policy. The host contract also
  failed before ADB reverse plus `127.0.0.1` routing were added, then passed.
- A fresh paired relay run exposed an unbounded internal receipt queue: each
  `peer.probe`/`lan.bootstrap` was journaled relay-ACK-ready before its receipt
  reached custody, so the receipt could never terminalize. The new transaction
  test failed on the invalid state, then passed after receipt-backed controls
  remained `NONE` until receipt custody atomically deleted the receipt and
  advanced the original inbound row to `READY`. A repeated real relay run ended
  with `active_queue_count=0` and `active_queue_bytes=0` on both emulators.
- The host `post` oracle also counted internal receipt/probe rows as user
  deliveries. A regression with four permanent internal rows failed before
  `A.outbox.zero/nonzero` was based on the three user-custody counters, then
  passed. A standalone paired `post` scenario subsequently passed. Repeated
  aggregate post/cancel runs were not treated as clean evidence because API 37
  exposes shell notification post but no stable shell cancellation command and
  the persistent emulators retained fixed shell tags.
- The Android integration APK ran 79 tests successfully across
  `NotifPostBuilderAndroidTest`, `ForegroundNotificationTest`,
  `HistoryRepositoryTest`, `MirrorActionNotificationTest`,
  `TransportRecoveryPersistenceTest`,
  `ReliableDeliveryDaoMaterializationTest`, and
  `ReliableDeliveryTransactionTest`.
- Three independently identified source notifications remained as three
  independently materialized canonical notifications on the peer through
  service restart, package replacement, and reboot. Deterministic notification
  tests remain the dismissal oracle where the API 37 shell cannot cancel.
- The paired foreground notification visibly showed **Via relay** with the same
  custody explanation as Home. Android reported it private, ongoing,
  non-clearable, and backed by a real activity content intent. Repeated taps
  left one task containing one resumed `MainActivity`. Own-package capture
  remained fail-closed in instrumentation.
- Paired Home and History showed useful retained entries, app grouping, and
  clear controls. The clear-all confirmation truthfully said pairing and active
  mirrors would remain, and Cancel was exercised without deleting the test
  state.
- The clean-state `notification-actions-correctness` aggregate passed all 13
  children and 155 recorded steps: reply, mark-read, delayed offline delivery,
  expiry, duplicate suppression, origin/mirror process death, stale generation,
  cancel-before-invoke, installed/fallback taps, and auto/non-auto cancel. The
  secondary Twinotify detail action also remained covered by the eight focused
  `MirrorActionNotificationTest` cases in the integration APK.
- The onboarding welcome/slides/role/transport-choice path, paired Home,
  History, Settings, light/dark theme, and 130% font scale were exercised. At
  130% in dark mode, route copy, switch, paired-phone row, metrics, and history
  remained readable with named accessibility nodes and no overlap or clipping.
  Settings displayed the direct-only branch because debug pairing intentionally
  bypasses the TypeScript onboarding store; release tests separately cover both
  configured-relay and direct-only branches, so this fixture artifact was not
  treated as a production regression.
- Force-stop/open, an in-place debug package replacement, and emulator reboot
  all preserved pairing and enabled intent. Each showed an honest transient
  reconnecting state, returned automatically to authenticated relay/protocol
  floor 2, and drained to zero user-delivery rows. Reboot recovery occurred
  without opening the app or re-pairing after the test-only loopback tunnel was
  restored.
- One paired UI checkpoint reconciled six known source deliveries with
  **Mirrored 6**. Receipt retries and periodic internal controls did not leave
  active user-delivery rows after the receipt-lifecycle fix; JVM/Room coverage
  remains the deterministic first-receipt and latency oracle.
- PB-004 retains its existing API 37 light/dark themed-icon emulator pass.
  PB-005, PB-010, and PB-011 remain owner-decision-blocked and were not
  implemented or reclassified.
- Final verification passed with `make host-verify` (233 Jest tests, all
  race-enabled E2E Go packages, vet, shell/evidence/documentation contracts,
  and generated cleanliness), the full Android
  `lintDebug testDebugUnitTest compileDebugAndroidTestKotlin assembleDebug`
  gate (`BUILD SUCCESSFUL`, 918 tasks), and the focused emulator
  instrumentation run (`OK (79 tests)`).

Physical-only rows remain pending regardless of these emulator results: both
OEM shades/launchers/lock screens, signed production upgrade behavior, OEM
background restrictions, real TalkBack speech and one-handed use, real Wi-Fi
LAN/no-uplink behavior, and two-phone count/latency reconciliation.
