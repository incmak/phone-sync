# Two-device E2E harness

The harness drives authenticated, debug-only product controls and observes only
content-free route, custody, receipt, queue, sequence, and materialization
state. It rejects phone, contact, notification content, network identifiers,
credentials, and raw protocol material.

## Mirrored notification actions gate

`notification-actions-correctness` runs the thirteen action, reply, expiry,
process-death, update, cancel, tap-routing, and auto-cancel scenarios in a fixed
fail-fast order. The dedicated fixture APK owns all notification content. The
host passes only closed operation enums; reply text is generated inside the
debug Android process and never enters ADB arguments or retained evidence.

On physical ROMs that block Android `run-as`, add
`-control-transport shell-broadcast` to the `twinotify-e2e` command. This
opt-in path targets the debug receiver explicitly and relies on Android's
`android.permission.DUMP` check for the ADB shell UID. It accepts only
`STATUS`, `NOTIFICATION_FIXTURE`, `NOTIFICATION_MIRROR`, and
`NOTIFICATION_ORIGIN`; results are bounded and returned in memory. It carries
no app token, private handle, notification text, or reply text. Pairing,
offline pairing, LAN scenarios, and every secret-bearing control remain on the
default `run-as` transport and are rejected in shell-broadcast mode. The
receiver is absent from release builds.

From the repository root, provide two distinct already-paired targets, the
freshly built fixture APK, and a private evidence directory:

```sh
E2E_DEVICE_A='<serial-a>' \
E2E_DEVICE_B='<serial-b>' \
E2E_NOTIFICATION_ACTION_FIXTURE_APK='mobile/android/notification-action-fixture/build/outputs/apk/debug/notification-action-fixture-debug.apk' \
E2E_NOTIFICATION_ACTION_EVIDENCE_DIR='/private/path/notification-actions' \
make e2e-notification-actions
```

The fallback-tap child temporarily uninstalls only the repository-owned fixture
from device B and reinstalls it even after a failed run. Offline children change
airplane mode. On physical phones, pause and obtain explicit operator approval
immediately before those radio changes. The harness never clears package data,
unpairs devices, or installs/uninstalls third-party apps.

## Complete direct-LAN product gate

`lan-product-correctness` runs eleven children in this exact order:
`lan-direct-delivery`, `lan-direct-reverse-delivery`, `lan-direct-dismiss`,
`lan-direct-update`, `lan-direct-peer-dismiss`, `lan-direct-call-state`,
`lan-direct-snapshot-receipt`, `lan-relay-fallback-return`,
`lan-restart-persistence`, `lan-direct-burst-backpressure`, and
`lan-direct-unpair-during-traffic`. It fails fast and retains four independent
artifacts for every completed child plus the failed child. The burst defaults
to 256 and is bounded to 2 through 1,000, below the production caps of 2,000
rows and 128 MiB.

Reverse delivery posts on B and requires exact materialization on A plus B-side
LAN custody and receipt evidence. Fallback uses the app-internal LAN
availability control on both phones, never an OS radio change, and separately
records the relay-carried and returned-LAN deliveries. Restart persistence uses
a bounded package force-stop followed by the typed launcher on A and B. It does
not clear package data.

From the repository root, use two explicit, distinct, already-paired hardware
serials and a private evidence directory:

```sh
E2E_DEVICE_A='<serial-a>' \
E2E_DEVICE_B='<serial-b>' \
E2E_LAN_PRODUCT_EVIDENCE_DIR='/private/path/lan-product' \
make e2e-lan-product
```

The Make target invokes `e2e/cmd/twinotify-e2e` and then
`scripts/verify-lan-product-evidence.sh`. It never clears package data, changes
Wi-Fi or mobile-data state, or pairs devices. Unpair runs last, so any later
re-pair is a manual operator action. Evidence lands at the exact directory in
`E2E_LAN_PRODUCT_EVIDENCE_DIR`, with four JSON artifacts at the root and under
each `children/NN-<scenario>/` directory.

The predecessor automation is implemented and host-tested through commit
`9c136cc`; this current change expands the host gate to eleven children. The
actual aggregate hardware execution is still pending physical two-phone run;
host fixtures are not physical acceptance.

## Direct Bluetooth route gate

`bluetooth-direct-route` is the only scenario allowed to claim a Bluetooth
route. It faults LAN and the relay on both phones through the app-internal
route control, never an OS radio change, waits for the coordinator to grant
Bluetooth, then delivers one small notification and one maximum-size fixture.
Each delivery must show digest-backed custody on the `bluetooth` route plus an
authenticated peer receipt. The plan then restores LAN and requires the
promotion to open a strictly later route generation, which is the host-side
proof that the coordinator closed Bluetooth before LAN drained the outbox: one
enum-valued route per generation cannot describe two concurrent drainers.

The debug control surface is four commands. Each is built by a constructor in
`e2e/internal/control`, so the host cannot express something the device would
reject:

| Command | Host names | Device returns |
| --- | --- | --- |
| `ROUTE_FAULT` | `route` (`LAN`, `BLUETOOTH`, `RELAY`), `enabled` | `route`, `enabled` |
| `AWAIT_ROUTE` | `route`, `phase`, `timeout_ms` | `route`, `phase`, `status`, `elapsed_ms` |
| `ENQUEUE_FIXTURE` | `bytes` (1 to 1,048,576) | `bytes`, `status`, `elapsed_ms` |
| `AWAIT_PEER_RECEIPT` | `timeout_ms` | `status`, `awaiting_peer_count`, `elapsed_ms` |

Commands are uppercase snake case because that is the receiver's existing
allowlist convention, not the lowercase JSON the plan sketched. Blocking
commands are bounded at 10,000 ms rather than the plan's 15,000 ms: Android
delivers this process's broadcasts one at a time, so a command that waits must
release the queue first and must not hold it longer than an already-proven
command. The host retries instead of asking for one longer wait.

No Bluetooth address, device name, SSID, association identifier, service UUID,
peer key, envelope, or notification content is reachable from any of these
responses, and the evidence writer rejects them if one ever appears.
`ENQUEUE_FIXTURE` reports the envelope byte count this device actually
persisted, read back from the durable outbox, so an undershoot is visible
rather than assumed.

From the repository root, provide two distinct already-paired and
Bluetooth-associated targets and a private evidence directory:

```sh
E2E_DEVICE_A='<serial-a>' \
E2E_DEVICE_B='<serial-b>' \
E2E_BLUETOOTH_EVIDENCE_DIR='/private/path/bluetooth-route' \
make e2e-bluetooth-route
```

Status: host-verified against the deterministic fake bridge only. Emulator
instances have no usable Bluetooth between them, so this scenario has never
been executed on hardware. It cannot satisfy `PHY-BLUETOOTH-01`; the physical
matrix in `docs/test-scenarios.md` and the record contract in
`docs/evidence/bluetooth-route/README.md` remain pending a two-phone run.

## Stock call control gate

`call-control-correctness` runs three children in this exact order:
`call-control-answer`, `call-control-decline`, and `call-control-duplicate`.
Device A runs a debug-only fixture that posts a real local
`Notification.CallStyle` with test answer, decline, and hang-up
`PendingIntent`s and injects the matching telephony state; device B renders
the mirrored native controls. The host names only closed enums (`ringing`,
`active`, `idle`; `answer`, `decline`, `hang_up`, `replay`) and reads back
`kind`, `count`, `status`, and timing. Control UUIDs, invocation IDs, session
IDs, caller data, intents, and package names never cross ADB or reach evidence,
and the artifact writer rejects them if they do.

Status: passes on two API 37 emulators over the relay route (2026-09-03). Emulators
cannot reach each other on a LAN, so keep Wi-Fi off during the run; a stale LAN
binding otherwise adds a 10 s direct-connection timeout to every send. Re-pair from
a clean install and a fresh relay database after any identity change, because a
peer that receives envelopes for a previous identity backs off into a reconnect
loop. The gate is an emulator regression aid; the physical matrix below is still
the release evidence.

The answer child proves `ringing -> answer -> active -> hang_up -> idle` with
exactly one fixture dispatch per control. The decline child proves
`ringing -> decline -> idle` with no answer dispatch. The duplicate child
re-sends the last tapped mirror `PendingIntent` and proves the origin still
reports one dispatch, because `invocation_id` equals the one-use `control_id`.
Dispatch counts are exact and must hold across three consecutive samples; the
STATUS observation carries `call_controls_enabled`, `canonical_call_controls`,
and `call_control_dispatches` as closed-world fields. The scenarios run over
whatever route is authenticated and do not assert a direct LAN route.

From the repository root, provide two distinct already-paired targets and a
private evidence directory:

```sh
E2E_DEVICE_A='<serial-a>' \
E2E_DEVICE_B='<serial-b>' \
E2E_CALL_CONTROL_EVIDENCE_DIR='/private/path/call-control' \
make e2e-call-control
```

This gate is a synthetic emulator and debug-build regression aid. It requires
the default `run-as` transport and is rejected in shell-broadcast mode. It does
not exercise a real dialer, a cellular call, a lock screen, or an OEM
notification shade, so it cannot satisfy `PHY-CALL-CONTROL-01`. The physical
stock-dialer matrix in `docs/test-scenarios.md` and the record contract in
`docs/evidence/call-control/README.md` remain pending until a two-phone run
produces them.

## Real call release evidence

Call-sync acceptance uses the protected physical evidence pipeline, not the
obsolete synthetic `call-state` scenario. Follow `PHY-CALL-01` in
`docs/test-scenarios.md`, assemble the private release evidence directory, then
run the fail-closed release audit:

```sh
make release-audit RELEASE_EVIDENCE_DIR=/private/path/twinotify-release
```

The verifier requires an explicit `PHY-CALL-01: pass`. Host fixtures and the
debug call-state injection path are regression aids only and cannot satisfy it.
