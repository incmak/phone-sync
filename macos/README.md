# Twinotify for macOS

Native macOS 26 / Apple Silicon notification receiver. The menu-bar app pairs
with up to two Android phones and receives end-to-end encrypted notifications
through the relay or directly over Wi-Fi. Dismissal on the Mac stays local.
The inbox supports Android-advertised notification actions and text replies.
Call controls, capture and Bluetooth remain unavailable on Mac.

The receiver includes durable encrypted storage, resumable pairing, per-link relay
sessions, permission-aware materialization, receipts, bounded snapshot repair and
scoped removal. The [local acceptance record](../docs/qa/macos-three-device-2026-09-07.md)
covers signed permission/crash recovery and the integrated three-device matrix.
Actual host sleep/wake and physical-phone acceptance remain
manual gates; a successful build is not production release acceptance.

## Pair a phone

Open the menu-bar inbox and click the **gear**. Enter the same secure relay address used
by your phone and choose **Show pairing QR code**. Scan it in Twinotify on Android,
compare both fingerprints, and confirm on each device. Codes expire after five
minutes; an interrupted attempt can resume from the Mac's saved pairing state.
The relay address is remembered for the next phone. Importing a phone's QR image
or pasting its JSON remains available under the alternate pairing disclosure.

## Menu-bar inbox

The number beside the bell is the current inbox count, not a lifetime or unread
count. Click it to view notifications, expand a row to read its full text, and
use the previous/next buttons for pages of eight. Scroll within a page when
needed. Each link/canonical notification has one entry; updates replace it and
phone cancellations remove it. Dismissing an OS notification locally leaves the
phone's active state in the app inbox. Use the **×** on a row or **Clear all** to
clear Mac copies from both the inbox and Notification Center. **Undo** restores
the last cleared batch to the inbox without another OS alert. Inbox dismissals
survive restart and unchanged updates or snapshots; changed content or a new
notification lifecycle can appear again. The phone is not dismissed.

Use the delivery menu at the bottom of the inbox, or **Settings → Notifications → Show notifications in**, to choose:

- **Notification Center + inbox** (default): new notifications use both surfaces.
- **Menu bar inbox only**: no OS banners or sounds, and no OS permission required.

The preference survives restart. Selecting inbox-only removes this app's
existing OS entries, and switching back does not replay already applied entries.
The inbox reads the existing encrypted desired state; no additional plaintext
history or database-format migration is introduced. Unchanged higher-sequence
updates and snapshots advance delivery state without another alert; changed
content and a new notification lifecycle still alert in Notification Center mode.

See the [inbox QA record](../docs/qa/macos-inbox-2026-09-08.md) for tested cases and limits.

## Direct connections and actions

After both devices exchange authenticated LAN bindings through the relay, the
Mac discovers the paired phone on the local network and authenticates it with
pinned mutual TLS and signed challenges. The status reads **Direct on Wi-Fi**
or **Via relay**. Relay delivery continues while discovery runs; a completed
handoff stops the old sender before the new sender drains queued messages.
Direct connection failure resumes relay delivery. The Android build must support
the `twinotify-lan/2` TLS exporter mode. Older builds continue through the relay.

Each notification shows the actions its Android app supplied. **Reply** opens
an inline editor; **Send** submits it explicitly. Replies are limited to 4096 UTF-8
bytes. **Sent to app** means Android dispatched the action, not that a recipient
read the reply. An uncertain result asks you to check the phone; it does not
automatically create a second invocation. Actions live in the menu-bar inbox.

Mac storage version 3 adds encrypted peer LAN bindings and durable action
attempts. Migration preserves pairing, installation identity, nonce counters and
pending delivery. The additional local TLS key lives in the login Keychain.
See the [direct/action verification record](../docs/qa/macos-direct-actions-2026-09-08.md)
for the remaining integrated and physical-device checks, including pre-fix journal
compatibility. These features have not replaced the user's previously verified
running bundle.

## Build

Requires Swift 6.2 or newer, macOS 26 and arm64. SwiftPM pins the packaged sodium
binary by revision and lockfile; no Homebrew sodium installation is needed.

```sh
make macos-verify
macos/scripts/bundle.sh
macos/scripts/bundle.sh --sign 'YOUR EXPLICIT LOCAL SIGNING IDENTITY'
```

The ordinary bundle is `macos/dist/Twinotify.app`, identifier `co.twinotify.mac`.
The script verifies a requested signature and uses the existing Twinotify icon.
No certificate or personal signing identity is committed. Notarization, Intel,
public distribution, updates and automatic login enablement are outside scope.

`make macos-verify` generates schema resources from `proto/`, builds and runs the
Swift tests. Run `macos/scripts/sync-schemas.py` before invoking SwiftPM directly
on a fresh checkout. CI runs separately from the Linux-compatible `make verify`.

## Pair and use

Choose relay pairing and display a code on the phone. In **Open Twinotify…**, paste
its QR JSON or import an image containing one QR code. Compare both fingerprints
with the phone before confirming. Keep the relay URL reachable from both devices.
Only debug/E2E builds allow cleartext, and the Mac restricts it to loopback.

For **Notification Center + inbox**, allow notifications in the settings window.
Permission denial leaves reliable work pending; restoring permission resumes
eligible work without showing expired notifications. **Menu bar inbox only**
does not require Notification Center permission. Closing the settings window keeps reception running. Quit stops
sessions. Launch at login starts off and changes only through its explicit toggle.
Removing one connection preserves the other connection and installation identity.
Unavailable relay cleanup stays disabled and retries later.

Normal app state is under `~/Library/Application Support/co.twinotify.mac/`.
Identity and the content-encryption key live in the local login Keychain,
protected by the signed app’s access control entry. This fixed backend does not
synchronize and needs no provisioning profile. The Data Protection Keychain is
not used: a signature alone does not provide its required provisioned entitlements.
See [Apple’s Keychain implementation guidance](https://developer.apple.com/documentation/technotes/tn3137-on-mac-keychains).
Notification content and pending ceremony material are encrypted in SQLite;
activity history contains delivery metadata. The installation-wide nonce prefix
and counter survive peer removal. Do not delete either storage component as a
routine repair: missing identity or nonce state deliberately fails closed.

## Verification

Tests cover shared Android crypto/protocol fixtures, exact-byte envelope framing,
JWT selectors, storage and nonce failures, pairing cancellation, receiver recovery,
permission deferral, local dismissal, filtering/call expiry, snapshots and removal
isolation. The opt-in live relay test exercises real pairing, exact-byte custody
and a connection lasting beyond two heartbeat intervals:

```sh
macos/scripts/test-relay.sh
```

For platform acceptance, use the signed bundle to verify permission grant,
denial/restoration, update/cancel/call display, local dismissal, wake/reconnect,
window close, quit, keyboard navigation and light/dark/narrow-window behavior.
An unknown platform result across a crash may repeat a banner; stable entries and
convergent state do not imply exactly-once alerts.

## Isolated three-device E2E bundle

```sh
macos/scripts/bundle.sh --e2e --sign 'YOUR EXPLICIT LOCAL SIGNING IDENTITY'
```

This builds `macos/dist/TwinotifyE2E.app` with a separate bundle identifier and a
compile-gated local control endpoint. Ordinary bundles contain no endpoint. The
E2E app requires `TWINOTIFY_E2E_DIRECTORY`: an existing mode-0700 directory owned
by the current user, named with a UUID, with no symlinks. Its SQLite database and
Keychain service use that synthetic run identity, preserving state across restarts.
Requests are bounded files inside that directory; response metadata excludes
notification text. A claimed request whose process dies has an unknown result and
must be resolved by querying state, never blindly replayed.

Use `e2e/cmd/twinotify-three-device` with two fresh Android emulator installations
and that Mac directory. Android and Mac have distinct adapters; only Android can
post a source notification or dismiss a phone mirror. The driver verifies pairing
fingerprints before confirming synthetic ceremonies and refuses personal-phone
serials. It does not reset existing device state. See `e2e/README.md` for invocation.

For appearance QA only, the E2E `appearance` operation accepts `light`, `dark` or
`system`. It changes only that running app, without modifying system appearance.
