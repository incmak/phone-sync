# macOS and three-device acceptance — 2026-09-07

This record covers local QA in the primary checkout. It does not authorize or
claim a production migration, deployment, or physical-phone release acceptance.
The installations were two disposable API 37 Android emulators and the real
signed `TwinotifyE2E.app`, using an isolated local relay and private Mac state.
Both ordinary and E2E Mac bundles were signed with an external local identity.

## Results

| Check | Observed result |
| --- | --- |
| Three reciprocal links | Real authenticated A↔B, A↔Mac and B↔Mac pairing; original phone link retained |
| Post and update | Both recipients materialize the same origin sequence; updates retain canonical identity |
| Phone dismissal | Tested both directions separately; the origin authors a newer cancellation and all three converge |
| Mac dismissal | Local removal survives authoritative repair without dismissing either phone's copy |
| Offline Mac | Phones continue delivering; Mac catches up after relay reconnection |
| Concurrent origins | Simultaneous A/B posts and updates retain both canonical entries |
| Removal with queued traffic | A updates while Mac is disconnected, then A↔Mac is removed; A↔B and B↔Mac still deliver |
| Last-link removal | Mac identity and nonce prefix survive; counter stays at 55, then the same identity is re-paired |
| Real OS permission denial | Received work remains pending, unmaterialized and undisplayed while transport stays connected |
| Process stop and permission recovery | Forced stop preserves pending work and identity; restoring OS permission delivers sequence 1 and drains pending work |
| Interrupted snapshot | Twelve fixtures present; process stopped with two snapshot rows staged; reopen completes the snapshot and retains all twelve entries |
| Empty snapshot | Real authoritative snapshot advances the commit count and leaves zero active/delivered mirrors |
| Call transitions | Ringing sequence 1 displays; active sequence 2 clears; idle sequence 3 stays cleared |
| Native UI | Real QR JSON input, read-code and fingerprint confirmation complete pairing; both full fingerprints verified against handshake keys; test notification buttons and Quit work |
| Appearance | Native paired and one-peer settings inspected; 480-point light/dark layouts readable; Android two-peer cap and full fingerprint display inspected |

Keyboard foreground/focus verification remains open: accessibility actions work,
but activation could not be verified and the computer-use service timed out. The
settings action uses the native `SettingsLink`; a custom `openSettings` button
was removed after it failed to open the normal app window. Actual host sleep/wake
and physical-phone direct-route
acceptance also remain manual gates. Launch-at-login registration was not enabled
on the user's host during isolated QA.

An unknown platform result across a crash can repeat a banner. Stable canonical
entries and eventual convergence do not establish exactly-once alerts.

## Reproduce the automated gates

```sh
make proto-test
make relay-verify
make mobile-verify
make host-verify
make macos-verify
make deployment-test
macos/scripts/test-relay.sh
```

The full mobile gate passed after four required Expo patch updates. Final
JavaScript validation passed 305 tests plus lint/typecheck. Android JVM,
instrumentation compilation, native lint and debug assembly passed; focused
migration, multi-peer removal, nonce and LAN instrumented checks passed on the
disposable emulator. Relay and all eight host Go packages passed with `-race`.
The real Swift relay test passed across two heartbeat intervals. Final Swift
tests, signed bundles, deployment script regressions and Compose checks pass.

For the integrated matrix, follow [the E2E setup](../../e2e/README.md) and run:

```sh
cd e2e
go run ./cmd/twinotify-three-device \
  --a emulator-5556 --b emulator-5558 \
  --mac-directory /private/tmp/YOUR-PRIVATE-UUID \
  --android-relay http://127.0.0.1:18080 \
  --mac-relay http://127.0.0.1:18080 --pair
```

Use `--pair-mac` for a reciprocal existing phone pair and an unpaired Mac, or
omit pairing flags for an existing full triangle. The matrix removes A↔Mac at
the end. It spaces revisions outside Android's intentional repeat-protection
window; a fast third revision must not be mistaken for delivery failure.

The separate platform checks used the same adapters and real OS state:

1. Deny only TwinotifyE2E notifications in System Settings. Post one synthetic
   emulator notification. Verify Mac desired sequence 1, materialized sequence 0,
   pending count 1 and no delivered request. Stop the identified E2E process,
   reopen the same bundle/private directory, and verify identity/pending work.
   Restore permission; require delivered sequence 1 and pending count 0.
2. Post twelve distinct synthetic sources on B and wait for display. Request
   `FORCE_REPAIR_SNAPSHOT` through the Android adapter. Observe only row counts
   in the private Mac `snapshot_stage` table; stop the E2E process after staging
   starts. Reopen, require an increased snapshot commit count and all twelve
   canonical entries. Cancel those exact fixtures and request another repair;
   require zero active or delivered entries.
3. Enable the debug synthetic call source and send `ringing`, `active`, `idle`
   through `CALL_STATE`, waiting for each exact materialized sequence and platform
   display result before advancing. No real phone call or caller data is used.

Local metadata and synthetic UI captures are retained under
`macos/dist/qa-evidence/2026-09-07/`. Pairing tokens, private keys and notification
content are excluded. Bundles and local evidence are QA artifacts, not protected
release candidates.

## Rollout boundary

The previous supported relay binary refuses migrated fixture storage. The deploy
script now stops after any candidate start/readiness/smoke/interruption failure
and records `restore_decision_required`; it never automatically starts the old
binary against potentially migrated storage. A failing-before regression covers
this boundary, including start, readiness, smoke and signal cases. A read-only
backup failure before candidate startup can still resume the untouched old binary.

Production host/database selection and a pre-migration backup remain outstanding.
Validate a copy of that actual database before rollout, then follow the
[migration recovery runbook](../relay-production-runbook.md). Restoring a backup
after new-format writes requires an explicit operator decision and a stopped
writer; it can discard newly accepted data.

## Connected-phone installation follow-up

At the user's request, the current standalone arm64 APK was installed in place on
both connected physical phones: POCO F1 (API 35) and M2012K11AI (API 36). Both
installed certificates matched the local build certificate. `adb install -r`
succeeded on both, followed by successful activity launch and running app
processes; no uninstall or app-data clear was performed. This verifies installation
and startup, not the remaining physical-device delivery matrix.

The APK was built with `./gradlew :app:assembleRelease
-PreactNativeArchitectures=arm64-v8a` and includes its JavaScript bundle. SHA-256:
`98d840f684b4f0c664fd44714558b0e745f2a0f2de23171f401153eddc8b8dcd`.

The normal Mac instance had remained running an earlier foundation build despite
the bundle on disk being replaced. It was quit and relaunched with the current
signed build. The native settings link now opens its window, and the normal app
reports notification permission **Allowed** after retrying the request. Only one
normal Mac instance remains. The macOS gate passes after restoring `SettingsLink`.


## Mac-displayed pairing QR follow-up

The Mac now implements the existing initiator handshake and displays its expiring
QR before showing both fingerprints for explicit confirmation. The phone's native
responder flow and relay protocol are unchanged. Legacy saved responder attempts
remain readable. Pairing errors appear beside the controls.

Validation: `make macos-verify` passed (24 executed tests, two live tests disabled);
`TWINOTIFY_TEST_RELAY=http://127.0.0.1:18080 swift test --package-path macos
--disable-automatic-resolution --filter liveMacInitiator` passed. The live regression
covers client recreation before and after fingerprint confirmation, refusal to
confirm before a phone announces itself, matching pair IDs, and cancelling a
waiting subscription without losing an existing link. It exposed and verified a
fix for cancellation arriving during the HTTP-to-WebSocket transition.

In the signed isolated app, the native Show pairing QR code button generated a
code visible in a 480×680 light window. Core Image decoded the screenshot to the
exact wire payload. Android emulator 5556 accepted that payload through PAIR_JOIN;
the Mac displayed both full fingerprints. QA controls completed confirmation
through the real native handshake, and both sides reported their new link while
preserving the unrelated phone links. Evidence is local under
`/tmp/twinotify-mac-qr-{light.png,final-state.json,android-final-state.json}`.
The temporary QR payload is not committed.

Physical camera scanning, keyboard field entry, and dark appearance of this new
surface were not verified. Native accessibility value setting did not update the
SwiftUI text binding, so the isolated relay setting was seeded through the QA
control before testing the native QR button. The confirmation window did not
remain consistently accessible to automation, so final confirmation used the QA
control; the live tests verify the same fingerprint gate. This does not close the
existing keyboard/window activation acceptance gap.

The final normal bundle was rebuilt, signed, and relaunched. The QA process was
closed; only the normal app remains. No phone APK or production relay change was
needed for this follow-up.

## Physical scan regression repair

A physical scan exposed two client defects: the Mac regenerated its QR image on
every countdown render from JSON without stable key ordering; Android re-enabled
barcode callbacks immediately after a failed relay request. The Mac now sorts QR
JSON keys and retains one image per pending session. Android keeps failed relay
scans latched until explicit Try again, and explains the missing relay endpoint.
The existing configured production URL is now also the Mac's first-use default.

The production `/pair/session` endpoint returned HTTP 404; the current local relay
returned HTTP 401 without authentication, confirming that only the current build
recognizes the route. The production health version remained
`relay-manual-30b99c6f3ea6`. No production database migration or deployment was done.
The safety check that resolves existing phone membership before adding another
peer remains intact.

Validation: the scanner suite passed 39 tests, including 20 repeated camera events
after a simulated `pair/session HTTP 404` producing only one request until explicit
retry. Typecheck and lint passed. The macOS gate passed with a regression asserting
identical QR bytes over 100 persisted-model reloads. The signed normal app was
rebuilt and relaunched; only one normal process remained. The new standalone
arm64 APK was installed in place on both physical phones and both activities
started successfully. APK SHA-256:
`0000060824e6a3547e4f8a718b04a3a35293a7574f746f34e345c96e948ccef9`.
Physical camera performance after this update remains to be observed, and adding
the Mac through production remains blocked until the relay is upgraded.

## Post-pairing heartbeat crash repair

A physical pairing completed, but the normal Mac process subsequently crashed.
The 2026-09-07 21:22 local crash report identifies an `EXC_BREAKPOINT` in
`CheckedContinuation.resume(throwing:)`, called from the WebSocket ping callback
in `RelaySession.swift`. The callback bridge did not guard against a late/duplicate
completion when the socket closed.

The ping wait now uses a mutex-protected completion state: the first pong, error,
or task cancellation settles the continuation and later callbacks are ignored.
Cancellation also settles the wait without depending on a socket callback.
Regression tests cover a pong followed by a close error, duplicate errors,
concurrent callbacks, and cancellation without a callback. The macOS gate passed
(27 executed tests, two opt-in live tests disabled), and the normal app was rebuilt,
signed, and relaunched with existing state. No pairing or identity reset occurred.

After relaunch, the real Mac menu reported Connected and notifications Allowed.
Read-only metadata showed one active peer, no unfinished pairing, no pending
display work, and no outbound receipts. Notification contents were not inspected.
The physical phones were not connected over USB during this repair, so their
queue indicators were not directly verified. Evidence:
`/tmp/twinotify-ping-crash-tests.log`, `/tmp/twinotify-ping-crash-build.log`, and
`/tmp/twinotify-ping-crash-live-test.log`.

The real-relay exact-byte/two-heartbeat regression passed in 116.156 seconds
with the fixed callback bridge. The normal paired Mac process also remained
running throughout that interval. This verifies the repaired path and observed
reconnection; it is not an assertion that all future platform failures are absent.
