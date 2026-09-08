# Mac direct transport and notification actions — emulator acceptance

Status: source, native action UI, cross-platform TLS/delivery and the three-device
emulator matrix pass. Physical-device acceptance and pre-fix journal upgrade
compatibility remain open. The personal installation was not replaced, and no
personal pairing or notification state was reset.

## Passing evidence

- `make macos-verify`: 48 tests reported, 45 executed and three opt-in tests
  disabled; no failures. Local log: `/tmp/twinotify-direct-final-source-tests.log`.
  Coverage includes populated SQLite 2→3 migration, preserved identity/nonce and
  pending state, exact LAN framing, signed role/exporter binding, local TLS
  certificate trust/SPKI, custody versus receipts, action deduplication across
  concurrent clicks, stale targets, reply limits, timeouts and late results.
- The production `routeHandoff` helper is exercised with deliberately delayed
  relay cleanup. It does not release the direct candidate until cleanup finishes.
  Cancellation closes both the winning connection and a late candidate.
- Android core lint and all 1,030 JVM tests (121 suites) pass. The application APK
  and standalone instrumentation APK compile. Local logs:
  `/tmp/twinotify-direct-diagnostics-android2.log` and
  `/tmp/twinotify-direct-session-android-tests.log`.
- A real Android JSSE ↔ Apple Network.framework test passed pinned mutual TLS,
  negotiated exporter equality through signed challenges, heartbeat exchange and
  exact Unicode/escaped LAN frames. It used a temporary ADB forward to a loopback
  test listener, **not Bonjour or physical Wi-Fi**. Logs:
  `/tmp/twinotify-lan-mac-interop.log` and
  `/tmp/twinotify-lan-android-interop.log`.
- The extended interoperability test **passes** through the committed host runner
  `macos/scripts/test-android-lan.py`. It verifies encrypted notification delivery
  twice, exact custody digests, an authenticated encrypted receipt, receipt reuse,
  one notification-platform submission and one inbox record. Log:
  `/tmp/twinotify-approved-lan-interop.log`. Its platform is a test double; this
  does not establish physical Wi-Fi discovery or OS banner behavior over LAN.
- The latest isolated signed QA bundle builds. Fresh pairings verified both
  fingerprints and completed authenticated bootstrap exchanges; both Mac LAN
  bindings persisted and reliable queues drained.
- Real native Mac controls passed: Reply → keyboard input → Send dispatched once
  on Android; Mark as read dispatched once; Cancel discarded the draft with a
  zero dispatch count. Light and dark native surfaces were inspected, including
  keyboard focus and enabled Send. Screenshots:
  `/tmp/twinotify-approved-reply-editor.png` and
  `/tmp/twinotify-approved-reply-dark.png`.
- Restart preserved the Mac installation identity and both peer links, without
  changing the existing fixture dispatch counts. A further reply queued while
  transport was paused persisted before custody, survived process termination,
  and dispatched once after restart using its original invocation ID. The
  authenticated result became `dispatched` and the outbox emptied. Evidence:
  `/tmp/twinotify-approved-offline-action.json` and
  `/tmp/twinotify-approved-offline-reply-after.json`.
- All eight existing three-device matrix checks pass on fresh scratch profiles:
  post fan-out; update replacement; local Mac dismissal through snapshot repair;
  offline Mac catch-up without blocking phone delivery; phone mirror dismissal;
  second-phone post/cancel; concurrent origins; and removing one link with queued
  traffic while preserving both surviving links and installation identities.
  Log: `/tmp/twinotify-approved-three-device-matrix2.log`.
- Host-driver `go test ./... -race -count=1` and `go vet ./...` pass. Logs:
  `/tmp/twinotify-approved-host-go-tests.log` and
  `/tmp/twinotify-approved-host-go-vet.log`.

## Regression found during integration

Android's relay codec previously parsed and re-encoded an incoming envelope before
passing it to authentication. Mac JSON ordering can differ from Android's. This
changed the bytes used for journal/receipt digests and broke exact relay custody.
A failing regression test reproduced the error. The corrected parser validates
the complete frame and extracts the original envelope spelling, rejecting duplicate
keys and trailing input. Outgoing frames preserve immutable envelope bytes too.

The old synthetic three-device run contains journal rows written with those
incorrect digests. After the fix, an old replay correctly produces `id_conflict`.
Refreshing capabilities restored route negotiation, but those old rows still
prevented the integration backlog from draining. The test state was preserved;
digest matching was not weakened and no journal rows were deleted to obtain a pass.
Upgrading an installation containing such pre-fix rows remains unverified and
must not be presented as a seamless migration.

The matrix also exposed an ADB fixture-driver error: shell arguments were not
quoted, so multiword first/updated messages both arrived as `Synthetic`. The Mac
correctly treated those as unchanged presentation and suppressed another alert.
A regression test emulates the remote shell and demonstrates that spaces, quotes
and metacharacters must survive literally. Post/cancel now quote their arguments;
the regression, host suite and corrected full matrix pass.

## Remaining acceptance

1. Physical Bonjour discovery, Wi-Fi → relay fallback, actual host sleep/wake and
   the user's OEM phones. Emulator TLS forwarding cannot establish these results.
2. Resolve the pre-fix journal upgrade case without relaxing exact-digest checks
   or silently deleting paired state before replacing the user's working build.

Tests used separate scratch AVD profiles and a fresh isolated Mac identity. Earlier
profiles and their incompatible pre-fix test journals were preserved. The
three-device driver removed only a link created for this acceptance run.

## Reproduce the opt-in LAN test

Using an isolated emulator and its temporary test forwarding:

```sh
make macos-verify
cd mobile/android
./gradlew :twinotify-core:assembleDebugAndroidTest
cd ../..
python3 macos/scripts/test-android-lan.py --serial emulator-5554
```

Keep Android platform-tools on `PATH`. This test installs only the standalone
core test package. It exchanges ephemeral public test identities and sends fixed
synthetic notification content. It requires no notification-listener privilege.

The account/device-pool deliverable is a
[design proposal](../implementation/2026-09-08-device-pool-design.md), not an account
service implementation or deployment.
