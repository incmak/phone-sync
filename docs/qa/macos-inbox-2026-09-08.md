# macOS inbox and unchanged-update alerts — 2026-09-08

## Scope

The menu-bar icon shows the current mirrored inbox count. Its popup lists one
entry per peer/canonical notification, with expandable text and pages of eight.
Settings selects Notification Center plus inbox, or inbox only. The preference
persists across restart. Inbox-only delivery needs no OS notification permission;
changing back does not replay already-applied entries. Phone cancellations remove
inbox entries, while local Notification Center dismissal leaves phone state intact.

The inbox uses existing encrypted desired state. SQLite remains at version 2;
there is no relay, wire-protocol, Android or identity migration. Mac LAN/Bluetooth
transport and account/device groups remain outside this change.

## Repeated-alert regression

A new Android sequence with unchanged visible content previously called the OS
notification platform again. The regression reproduced an additional alert and
resurrection after local dismissal, including after recreating the receiver.

The reducer now reuses a fully materialized presentation when title, subtitle,
body, image and lifecycle match. It advances sequencing and receipt state without
another platform post. Snapshot reconciliation uses the same comparison. Changed
content, unfinished permission-blocked work and a post following cancellation do
not get suppressed.

The failing regression was recorded in
`/tmp/twinotify-repeated-alert-regression.log`. Final `make macos-verify` passed:
34 tests reported, including two disabled opt-in relay tests (32 executed), plus
compilation and bundle-script syntax validation. Evidence:
`/tmp/twinotify-inbox-final-tests.log`.

Focused tests cover higher-sequence duplicates, snapshot repeats, real content
changes, cancellation/repost, receipts with inbox-only delivery, permission backlog,
mode changes, encrypted inbox recovery, peer isolation and pagination boundaries.
An uncertain OS submission followed by a crash still has the previously documented
unknown-result boundary; this does not establish exactly-once banners for every
possible failure or diagnose all personally observed repetitions.

## Signed app and live relay checks

Used the existing isolated QA Mac state and Android emulator, preserving the
normal app's identity. Both Mac bundles were signed using the previously selected
local identity; no certificate or identity value is committed.

- The first UI inspection exposed a zero-height scroll area. An explicit bounded
  height fixed it; singular count wording was also corrected.
- The native icon count, notification list, expandable long text, and light/dark
  appearance were inspected. The full long-message ending was accessible.
- Twelve distinct synthetic notifications arrived through the existing test relay
  in inbox-only mode. The count reached 14 including earlier QA entries; zero OS
  entries and zero pending/outbound work remained.
- The next-page control displayed entries 9–14. Keyboard activation with Space
  also advanced the page; Next was disabled on the last page.
- The destination picker worked. Restart preserved inbox-only mode and saved
  inbox state. Switching to Notification Center plus inbox did not replay the
  existing 14 entries; a new test notification raised the count to 15 and produced
  exactly one corresponding delivered OS entry, with queues drained.
- All 14 notifications created during this QA were removed from the emulator.
  Its pre-existing inbox entry remained, with no pending work. The QA app's
  original destination setting was restored and the QA process was closed.

Evidence on the development Mac includes:
`/tmp/twinotify-inbox-page-one.png`, `/tmp/twinotify-inbox-light.png`,
`/tmp/twinotify-inbox-expanded-ax.txt`,
`/tmp/twinotify-inbox-qa-center-before.json`,
`/tmp/twinotify-inbox-qa-center-after.json`, and
`/tmp/twinotify-inbox-cleanup-state.json`.

The ordinary signed build passed (`/tmp/twinotify-inbox-normal-build.log`). The
old normal app was quit through its menu and the updated bundle reopened. Only
one Twinotify process remained. Its original peer remained active, the menu showed
Connected, and the icon exposed the current notification count. Personal
notification contents were not read for verification. Physical Android devices
were not attached for a new personal phone-to-Mac notification test.


## Popup redesign and local clearing

The popup now uses a compact 360-point native layout, restrained separators,
readable previews, expandable full text, source-app metadata when available,
compact timestamps, a settings gear and an inline delivery menu. The list sizes
to its content up to 360 points, with eight entries per page. Empty state is compact.
Rows have a local clear button; Clear all acts on the current inbox and Undo
restores the last batch without OS alerts. Dismissal metadata stays in existing
encrypted desired records. No database or wire-format migration was introduced.

`make macos-verify` passed: 36 reported tests, including two disabled live-relay
tests (34 executed). New and extended cases cover local clearing across restart,
unchanged higher sequences and snapshots, stale UI actions, changed content,
cancellation, Undo without dirty work, and compatibility with old saved records.
Log: `/tmp/twinotify-popup-clear-tests.log`.

Signed QA app checks used ten synthetic entries across two test links. Clearing
one reduced the count to nine; Undo restored ten. Clear all produced the empty
state; Undo restored ten. Keyboard Space on Next displayed entries 9–10 and
disabled Next. Expanding the long fixture exposed its final “End of preview.”
text. Light/dark appearances and populated/empty states were visually inspected.
The delivery menu changed modes and the settings gear opened settings. A new
synthetic notification in Notification Center mode produced one OS entry;
clearing its row reduced the inbox by one and left zero OS entries.

Evidence: `/tmp/twinotify-popup-final-light.png`,
`/tmp/twinotify-popup-final-dark.png`, `/tmp/twinotify-popup-final-empty.png`,
`/tmp/twinotify-popup-{one,all}-cleared.json`, and
`/tmp/twinotify-popup-center-clear-{before,after}.json`.
All nine fixtures created for this pass were cancelled on the test emulator.
The original test entry remained and both links had zero pending/outbound work
(`/tmp/twinotify-popup-cleanup-state.json`). The original delivery preference was
restored and the test app closed. Signed QA and normal builds passed; logs are
`/tmp/twinotify-popup-clear-e2e-build.log` and
`/tmp/twinotify-popup-final-normal-build.log`. Physical phones were not used in
this pass; no personal notifications were cleared during verification.
