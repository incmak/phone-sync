# Physical in-place upgrade — 2026-09-08

Status: both Android updates and the normal Mac update are installed. Existing
identities and pairings were retained. Phone-to-phone synthetic delivery passes;
the existing Mac link remains intermittent because of incompatible older Android
journal records. This is **not** full physical acceptance or a completed journal
migration.

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

## Physical observations and limits

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

## Pending approval

The original phone-to-phone pairing and POCO-to-Mac pairing remain. The second
phone has not yet been paired to the Mac. Automatic approval review rejected
confirmation of that additional trusted relationship without explicit user
authorization. The expired, unconfirmed Mac ceremony was cancelled.

A proposed scoped repair is to remove and recreate the POCO-to-Mac link and add
the second phone-to-Mac link, comparing full fingerprints for each ceremony.
Removing the old link clears its queued deliveries and mirrored history, so that
operation also requires explicit confirmation. It must preserve the existing
phone-to-phone link and installation identities. No such removal was performed.
