# Bluetooth standalone delivery verification — 2026-09-05

Scope: existing encrypted Twinotify notification and incoming-call control events over Bluetooth, with LAN and relay unavailable. This is emulator evidence, not `PHY-BLUETOOTH-01` or proof of cellular audio.

Implementation:

- Standalone Bluetooth uses the coordinator's direct-route retry and LAN promotion path even without a relay. One drainer remains granted; promotion closes/joins the old session first.
- Bluetooth frame I/O allows continuous progress beyond ten seconds, with an idle deadline per chunk and a two-minute absolute frame deadline. Handshake bounds remain intact.
- Direct delivery reads ahead by at most one bounded frame while ordered processing waits on a response write, preventing the deterministic duplex credit stall.
- Locally captured call states are already applied on their source device. Durable capture now records that sequence, and startup materialization can finish older ended-call rows without attempting to cancel a nonexistent source notification.
- Shared LAN/Bluetooth frame encoding removes optional JSON slash escapes. This fixes the reproduced Android nested-envelope frame overflow without raising frame or memory limits; encrypted envelope bytes remain unchanged.
- Debug custody observations include persisted Bluetooth rows and call-control invoke/result counts. New host gates require Bluetooth isolation, custody, call-state receipts, duplicate suppression, and terminal convergence.

Environment: two API 37 ARM64 Android emulators. Paired application identities and the system Companion Device Manager association were established on both. No physical phones were modified. The instrumentation APK was signed with the existing development key in a temporary copy to match the app's debug signature permission. No signing credentials or application secrets are included here.

Verified on the final implementation:

| Check | Result |
| --- | --- |
| Kotlin JVM suite | 962 passed, zero failures/skips |
| Android lint and debug app/instrumentation build | Passed |
| Go E2E harness, `go test ./... -race -count=1` | All five packages passed |
| TypeScript typecheck and Jest | Passed; 262 tests across 36 suites |
| Stream/control-security instrumentation | 41 passed |
| Signed BLE/L2CAP 1 MiB round trip | Both emulator roles passed; about 112 seconds |
| Bluetooth answer/hang-up | Passed; invoke/result custody and call-state peer receipts verified |
| Bluetooth decline | Passed |
| Bluetooth duplicate-command suppression | Passed; one dispatch, terminal convergence |
| Terminal state after all three calling scenarios | Both devices: zero active outbox and pending materialization |
| Standalone encrypted small/near-limit notification delivery | Passed: 5,448 and 1,041,362 envelope bytes; Bluetooth custody, peer receipts, zero terminal queues |

The initial call gate failed terminal convergence; the same gate passes after
the durable capture and old-row materialization fixes. Repeated standalone shell-post runs also revealed stale-tag updates and ambiguous
startup-reconciliation sequence tracking. The standalone gate now uses fresh
synthetic capture fixtures (8 KiB and 1 MiB target sizes); actual encoded sizes
undershoot those targets due to framing and a 2% allowance for Android JSON
escaping of Base64 slashes. An Android-specific regression failed with the old
calculation and passes with the corrected one. The standalone gate waits for authentication and receipts under the host
deadline instead of the device control command's ten-second ceiling. The exact 1 MiB
claim is confined to the byte-equality radio test. Earlier maximum-stream
runs failed due to whole-frame deadlines and test-side blocking/shutdown; the
final concurrent reader/writer test passes with exact payload equality both ways.
The smaller 1 KiB and 64 KiB signed radio round trips also passed.

 All three calling children passed again on the final compact-encoder build in
`/tmp/twinotify-full-evidence/bluetooth-calls-verified`. Both devices ended with
zero active outbox and pending materialization. Route faults were restored by
the harness and emulator Wi-Fi was re-enabled after verification.

The passing standalone scenario is `/tmp/twinotify-full-evidence/bluetooth-delivery-compact`. Numeric fixture sizes were read through a metadata-only on-device query.

Raw emulator logs and sanitized host artifacts are under `/tmp/twinotify-full-evidence` and `/tmp/twinotify-full-*.log` on the test host; these temporary paths are not durable release evidence.

Boundaries:

- Incoming answer, decline and hang-up depend on a source app exposing the corresponding usable Android PendingIntent. The emulator fixtures exercise the actual capability dispatcher, not an OEM cellular dialer or a live SIM call.
- Audio stays on the originating phone. This change adds no HFP/LE Audio implementation, cellular audio capture, arbitrary dialing, or default-dialer replacement. Android restricts cellular voice capture to privileged apps: [Android audio input documentation](https://developer.android.com/media/platform/sharing-audio-input). The Bluetooth HFP client class is a system API: [Android source](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/framework/java/android/bluetooth/BluetoothHeadsetClient.java).
- Physical range, background/OEM behavior, power use, real dialer capabilities and recovery under real radio loss remain unverified.
- Generic notification post/update passed; the generic dismiss-origin smoke could not execute because the API 37 notification shell lacks the `cancel` command used by that scenario. This is not reported as a passing full notification suite.

Working tree: changes are based on `23a6281` (including `0c0435c`), left
uncommitted for review. Final debug APK installed on both emulators; no physical
phones or release builds were changed. Wi-Fi remains preferred when available;
Bluetooth is a complete standalone carrier for the existing data/control events,
not a new cellular audio profile. Standalone LAN promotion and retry/lease
ordering passed deterministic Kotlin tests; physical promotion remains pending.
