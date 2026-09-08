# Physical in-place upgrade — 2026-09-08

Status: both Android updates and the normal Mac update are installed. After the
user approved a scoped pairing repair, the POCO–Mac link was recreated and the
second phone was paired with the Mac. Fresh synthetic delivery and dismissal
pass across all three devices on direct Wi-Fi. The existing phone-to-phone link
and installation identities remain. Forced network fallback, physical replies,
sleep/wake and a general migration of incompatible old journals remain unverified.

## Installed builds and preserved state

- Both connected phones (POCO F1 and M2012K11AI) had the same previous standalone
  APK. Its signing certificate matches the new APK. Both `adb install -r`
  operations succeeded; neither application was uninstalled or cleared.
- Android `:app:assembleRelease` passed. Installed APK SHA-256:
  `3e36bf912696f5fc113491c8f62ebae54662654364a06bb04b2f67d694117d2e`.
  This remains a local QA build, not a protected distribution release.
- The ordinary Mac bundle was rebuilt and signed with its existing local
  signing identity. It runs from `macos/dist/Twinotify.app`.
- A consistent encrypted SQLite backup was taken before the Mac update.
  Comparison confirms identical installation identity and nonce prefix, a
  nondecreasing nonce counter, and the original active peer link. Storage
  migrated from version 2 to 3 without clearing data.
- The reported identity-storage warning belonged to `TwinotifyE2E.app`, which
  had been launched without its required isolated directory. The ordinary app
  was loading its saved identity. The extra QA instance was closed; only the
  normal app remains running.

## Repairs verified

An authenticated receipt with a wrong target digest previously threw out the
whole Mac session. The Mac now records that receipt as rejected, retaining the
original outbound envelope unchanged. It acknowledges only the receipt's own
exact envelope digest; it does not accept the claimed delivery, normalize a
digest, or send a receipt-of-receipt. Replays retain the original journal digest
check. A later, separate valid receipt can still retire the original command.

The regression failed with `digestConflict` before the change. The final
`make macos-verify` passed: 49 tests reported, 46 executed, three opt-in tests
disabled. The signed bundle build passed. Evidence:

- `/tmp/twinotify-invalid-receipt-red.log`
- `/tmp/twinotify-physical-final-verify.log`
- `/tmp/twinotify-physical-final-build.log`

The alternate pairing disclosure did not expose an accessibility action. It now
uses an ordinary button with an expanded/collapsed value and the existing
chevron appearance. Native activation expands it, its QR editor remains a text
area, setting the editor's value enables Read pairing code, and parsing a
locally captured phone QR reaches fingerprint confirmation. Busy controls stay
disabled. No new trusted pairing was confirmed.

## Physical observations before pairing repair and limits

- Both existing phone links and the existing Mac link reported Direct on Wi-Fi
  on the actual local network, exercising Bonjour and pinned direct negotiation.
- A single synthetic notification from each phone produced the exact expected
  mirror identifier on the other phone. Evidence:
  `/tmp/twinotify-physical-once-results.json`.
- An earlier POCO synthetic update and its cancellation reached the Mac and
  were durably marked applied/materialized. A later new-notification check did
  not reach the Mac promptly. Connected labels alone are not acceptance.
- The POCO still reports `relay_inbound_rejected:id_conflict` for the existing
  Mac connection. The Mac receipt repair does not rewrite those incompatible
  Android journals or establish a safe general migration for them.
- A temporary app-only preference change was restored. Forced loss of Wi-Fi,
  host sleep/wake, physical reply dispatch and complete three-device acceptance
  were not established in this run.
- All synthetic source notifications, exact mirror identifiers and their
  repeat-protection notices were checked absent at cleanup. Only their individual
  Undo controls were used; unrelated repeat-protection settings were preserved.
  Evidence: `/tmp/twinotify-physical-cleanup-result.json`.
- One earlier cleanup swipe used a stale row position and missed the test item.
  It is not possible to rule out dismissal of another notification. Subsequent
  cleanup located each exact synthetic row immediately before acting.

## Approved pairing repair and final physical smoke

The user explicitly approved recreating the POCO–Mac connection and adding the
second phone–Mac connection. The app controls removed only the old Mac link on
both endpoints, including its scoped backlog/history. No installation identity
was reset and the existing phone-to-phone connection was preserved.

Both pairing ceremonies compared all 64 fingerprint characters in both
directions. The second phone’s first ceremony expired and was cancelled before
renewing it. Final Mac state has two active peers and zero pending ceremonies.
Each phone’s Paired devices screen shows its other phone and Mac, both using
Direct on Wi-Fi. The Mac fingerprint remains unchanged.

- A fresh synthetic notification from each phone reached the other phone’s exact
  mirror identifier and the Mac’s materialized desired record.
- Both synthetic source rows were located afresh immediately before their
  dismissal. Both exact Mac `notif.cancel` records were applied and materialized;
  both phone mirrors disappeared.
- All synthetic sources, mirrors and corresponding repeat-protection notice IDs
  were checked absent on both phones. Phones were returned to Twinotify Home.
- An initial test command split a multiword title in Android’s remote shell,
  creating the known shell tag `repair` instead of the intended test tag. The
  check was corrected to that exact tag and a single-token title; those same
  synthetic items were used for the passing check and removed. No broad clear
  or unrelated repeat-protection reset was used.
- Evidence: `/tmp/twinotify-repair-smoke-result.json`, plus the final paired-device
  screen captures `/tmp/twinotify-peer-a-final.xml` and
  `/tmp/twinotify-peer-b-final.xml`. These contain test results or Twinotify UI,
  not notification content.

The earlier stale-row cleanup uncertainty remains documented above. This repair
establishes fresh direct-network delivery/dismissal for these installed devices;
it does not establish seamless migration of all old journals or the remaining
physical fallback/reply/sleep tests. No application code changed in this follow-up;
the existing build and test evidence remains applicable.
