# Test Scenarios

> Release note: the emulator procedures below are development checks. They do
> not constitute physical-device release evidence. A release candidate must
> satisfy the `PHY-*` protocol at the end of this document and pass
> `make release-audit RELEASE_EVIDENCE_DIR=<private-directory>`.

## Phase 1 — Smoke Test

_(Phase 1 smoke test procedure documented separately.)_

## Phase 2 — Crypto + Pairing Smoke

**Setup:** Two Android emulators (or one emulator + one physical device on same LAN). Relay running locally on the host (`cd relay && go run ./cmd/relay`, or `docker compose up relay`).

**Prereq:** Expo dev client installed on both devices (`npx expo run:android` after `npx expo prebuild --platform android`).

Note: there is NO pairing UI yet — Phase 2 exposes methods but the UI to drive them is Phase 3+. To smoke-test Phase 2 end-to-end, use the React Native dev-menu JS console OR write a throwaway scratch screen that calls the methods in sequence. Example JS:

```js
import * as core from 'phone-sync-core';

async function smokePhase2() {
  const relay = 'ws://10.0.2.2:8080/ws';
  const relayHttp = 'http://10.0.2.2:8080';

  // On Device A:
  const aId = await core.getDeviceId();
  const aKeys = await core.getPublicKeys();
  const qrJson = await core.startPairInitiator(relayHttp);
  console.log('Device A QR:', qrJson);
  // User physically copies qrJson to Device B somehow (shared pair-token over LAN, etc.)

  // On Device B (given qrJson from A):
  const payload = JSON.parse(qrJson);
  const fp = await core.computeFingerprint(payload.enc_pubkey, payload.sign_pubkey);
  console.log('A fingerprint as seen on B:', fp);
  // User confirms by visual comparison with A's fingerprint (computed on A via computeFingerprint using A's own pubkeys)
  // On A, show fingerprint of B's pubkeys (after receiving them from B out-of-band).

  // Back on A: sign confirmation with B's pubkeys received OOB:
  const sig = await core.deviceASignConfirmation(payload.pair_token, bEncB64, bSignB64);
  // Transport sig to B.

  // On B with the sig:
  await core.deviceBCompletePairing(relayHttp, payload.pair_token, sig);

  // Both devices: store peer's pubkeys for future encrypt/decrypt
  await core.storePeerPubkeys(payload.enc_pubkey, payload.sign_pubkey, payload.device_id); // on B
  // A stores B's pubkeys with the same call.

  // Authed ping:
  const res = await core.pingRelay(relay, true);
  console.log('authed ping:', res);

  // Encrypt test:
  const b64plain = btoa('hello from A');
  const { ciphertext, nonce } = await core.encryptToPeer(b64plain);
  // Transport ciphertext+nonce over ws to B; B calls decryptFromPeer.
  const plain = await core.decryptFromPeer(ciphertext, nonce);
  console.log('decrypted:', atob(plain));
}
```

**Procedure (manual):**
1. Start relay: `cd relay && go run ./cmd/relay`.
2. On both emulators/devices: launch the Expo dev client. Open dev menu → Debug JS Remotely (or use Reactotron, Flipper, or log.warn for output).
3. Execute the smoke script above (in steps — some require swapping data between devices manually).
4. Observe:
   - `/pair/init` returns 200 on device A.
   - Fingerprint strings match on both devices (A's fingerprint of its own keys == B's fingerprint of A's keys).
   - `/pair/complete` returns 200 with a `pair_id`.
   - Authed `pingRelay(url, true)` echoes the envelope (JWT accepted).
   - Authed `pingRelay(url, false)` fails with 401.
   - `encryptToPeer` + transport + `decryptFromPeer` round-trips the plaintext correctly.
   - Relay logs contain only ciphertext (no plaintext); `jti` replay within 60s is rejected with 401.

**Known gaps (documented in plan):**
- No UI yet — Phase 2 is headless. Phase 3+ ships pairing UI (design checkpoint before frontend work).
- Device A's confirmation_sig is transported out-of-band (user copy-pastes or scans QR-2). Phase 3 adds WebSocket-push of sig from A to B.
- Only Device A's signature is verified by the relay (`sig_A`). Device B's independent consent is implicit in the UX gate (B chooses to call complete after seeing A's fingerprint). Phase 3 adds Device B's signature path.

**Pass:** ☐  Date: __________ Devices: __________

---

## Phase 3 — Listener + First Mirror + Tier-1 UI

- [ ] Legacy Phase 3 handset smoke suite - pending physical two-phone run.

**Setup:**

- Two physical Android phones running API 34+.
- Both phones have Expo dev client installed with Phase 3 build: `npx expo prebuild --clean && npx expo run:android` on each phone (or distribute via EAS Build dev profile).
- Relay running — either `cd relay && go run ./cmd/relay` on a laptop on the same LAN with firewall open to 8080, or `docker compose -f deploy/docker-compose.yml up relay` + access via host IP from phones (e.g. `ws://192.168.1.10:8080/ws`).
- Both phones uninstalled + reinstalled clean to clear Phase 2 paired state (Task 0 renamed DataStore keys).

### Scenario 1 — Fresh onboarding + pair

1. Launch app on Phone A → should route to `/onboarding/welcome`.
2. Tap **Continue** → ScreenHow; swipe through 3 slides → **Get started**.
3. ScreenRole → tap **"This is my first phone"** (role A) → ScreenRelay.
4. ScreenRelay → enter your relay URL (e.g. `ws://192.168.1.10:8080/ws`) → **Test** → expect success indicator → **Continue**.
5. ScreenPerms:
   - Tap **Grant** next to "Post notifications" → Android 13 runtime dialog → Allow.
   - Tap **Grant** next to "Notification access" → system NLS settings → toggle Twinotify on → back.
   - Return to the app; both cards show check marks.
6. ScreenOEM → tap **Skip for now** (or Done).
7. ScreenReady → tap **Show my code** → `/pair/qr`.
8. `/pair/qr` shows a QR code + "Waiting for Phone B..." + 5-min countdown.
9. Repeat steps 1–6 on Phone B; at ScreenRole tap **"I have a code already"** (role B) → Relay → Perms → OEM → Ready → **Scan code**.
10. `/pair/scan` requests camera permission → Grant → scan QR from Phone A.
11. Both phones route to `/pair/fingerprint` with matching fingerprints.
12. Phone A: tap **They match**. The pairing protocol sends the signed confirmation through the pairing channel.
13. Phone B: tap **They match** after independently comparing the fingerprint.
14. Wait for the two-sided pairing flow to complete without copying keys, signatures, or tokens between apps.
15. Phone B: sees "Twinned." success screen, then routes to `/home` (CONNECTED state).
16. Phone A: tap **Finish** on its success screen → `/home`.

**Pass criteria:**

- Both phones land on `/home` with `TwStatusDot` green + "Direct / Relay / Encrypted" label.
- Settings → Paired device shows a short UUID + fingerprint matching what was displayed during pairing.

### Scenario 2 — First mirror (A → B)

**Prereq:** Scenario 1 complete; both phones on `/home` with service running.

1. On Phone A, trigger a notification — easiest is receiving a real Signal or WhatsApp message.
2. Within ~3 s, Phone B shows a mirrored notification under channel "Mirrored notifications" (IMPORTANCE_DEFAULT). Lock screen preview is hidden (VISIBILITY_PRIVATE).
3. Pull down the notification shade on Phone B → title + text should match the origin.
4. Content comes from the `notif.post` packet's title/text (Phase 3 skips MessagingStyle reconstruction).

**Pass criteria:**

- Mirror appears in ≤ 3 s on a good Wi-Fi.
- Icons (if fetched) show up either as the app's small icon or Twinotify fallback icon (Phase 3: fallback system icon is acceptable).

### Scenario 3 — Dismiss sync (swipe on B cancels on A)

1. With the Scenario 2 mirror still present on Phone B, swipe it away.
2. Within ~2 s the original notification on Phone A should also disappear.

**Pass criteria:**

- Phone A's original notification is removed; no duplicate cancels or infinite loops observable.

### Scenario 4 — Dismiss sync (dismiss on A cancels on B)

1. Trigger another notification on A.
2. Swipe it on A.
3. Within ~2 s the mirror on B disappears.

**Pass criteria:** Same as Scenario 3, reverse direction.

### Scenario 5 — Offline queue drain

1. On Phone A, toggle airplane mode ON.
2. Trigger 3 notifications on Phone A.
3. `/home` on A shows state `OFFLINE_QUEUED` with queued count.
4. Toggle airplane mode OFF.
5. Within ~10 s, all 3 mirrors appear on Phone B.

**Pass criteria:**

- Queue count increments while offline, decrements + reaches 0 after reconnect.
- All 3 mirrors show on B (FIFO order preserved).

### Scenario 6 — Denylist suppression

1. On Phone A, trigger a notification from an app in the default denylist — for example, Authy (`com.authy.authy`) or Google Authenticator (`com.google.android.apps.authenticator2`). If you don't have one installed, use the adb shell: `adb shell am broadcast -a com.example.TEST` isn't straightforward; easier to temporarily install Authy.
2. No mirror should appear on Phone B.
3. Nothing in the Phone B notification tray for this event.

**Pass criteria:** Denylisted apps do not mirror. Service logs may show the filter result via `adb logcat | grep Twinotify`.

### Scenario 7 — Tampered denylist aborts module init

1. Build a release APK: `npx expo prebuild && cd android && ./gradlew assembleDebug`.
2. Pull the APK: `adb pull /data/app/com.twinotify.app-.../base.apk tampered.apk`.
3. Unzip the APK, modify `assets/default-denylist.json` (add a character), re-zip, re-sign with a debug key.
4. Install the tampered APK on Phone A (`adb install -r tampered.apk`).
5. Launch the app → expect an immediate crash with `SecurityException: denylist integrity check failed`.

**Pass criteria:** Module init throws SecurityException when the SHA-256 of `default-denylist.json` does not match the compiled-in constant. The app does not start the NLS or SyncService.

### Scenario 8 — Unpair + re-pair

1. On Phone A, navigate Settings → Paired device → **Unpair** → confirm.
2. App routes to `/onboarding/role`.
3. Phone A persists exactly one durable v2 `unpair` event and gives the authenticated LAN or relay route a bounded custody window before secure local teardown.
4. Phone B receives the peer unpair, stops its service, wipes the paired state, and returns to onboarding without emitting a second unpair.
5. Re-run Scenario 1 to re-pair. Re-pairing is always an explicit operator action.

**Pass criteria:** Unpair rotates keys on both devices; re-pair works cleanly from scratch.

### Scenario 9 — Dark mode + hue toggle

1. Switch the system into dark mode.
2. All screens should render in dark theme (warm near-black backgrounds, light text).
3. Settings → (scroll) — tap the hue cycle control if surfaced (Phase 3 may not expose it yet — if not, skip).

**Pass criteria:** No hard-coded colors leaking; dark/light switches respect the theme tokens.

### Scenario 10 — POST_NOTIFICATIONS deny regression

1. Uninstall + reinstall the app on Phone B.
2. During onboarding Perms screen, tap **Grant** on "Post notifications" but DENY the runtime dialog.
3. Tap **Grant** again — should open App Settings as a fallback (Phase 3 falls back to `openAppSettings` if expo-notifications returns `!granted`).
4. Manually enable the permission in system settings → return to the app → card shows as granted.

**Pass criteria:** Denying the runtime dialog doesn't softlock the flow; app-settings fallback works.

---

### Current implementation notes

- **Pairing confirmation is transported by the pairing protocol.** Operators compare the fingerprint on both phones; they never copy a signing payload manually.
- **Authenticated direct LAN is live.** The running service selects authenticated LAN first, falls back to relay when configured, and reports direct, relay, reconnecting, or queued state from the public route status.
- **Local unpair notifies the peer.** One durable v2 unpair row gets a bounded LAN-or-relay custody attempt before local teardown. Peer-initiated teardown does not echo a second unpair.
- **Always-Connected FGS only.** No lazy-FGS / Doze-aware wake. Phase 9 adds this (requires FCM from Phase 5).
- **No icon cache / hash-elide.** Every mirror inlines full PNG bytes (base64). Phase 7 adds hash-elide.
- **No reply bridge.** Typing a reply on the mirror doesn't reach the origin. Phase 6 adds this.
- **No MessagingStyle reconstruction.** Mirrors show only title + text. BigTextStyle + MessagingStyle land in Phase 18.

---

**Phase 3 Pass:** ☐  Date: __________ Devices: __________

---

## Physical release evidence protocol

### OFFLINE-PAIR-01 - two-phone pairing with no relay or uplink

- [ ] Status - pending physical two-phone run.

This acceptance run requires two explicit, distinct, unlocked Android hardware
serials and an operator-controlled Wi-Fi network whose local client traffic
remains available while internet uplink is blocked. Remove both app installs,
install the same fresh debug APK, and confirm no relay or laptop service is
running before starting. The harness disables mobile data only. It must never
disable Wi-Fi or airplane-mode ADB connectivity.

Capture packet and DNS observations outside the harness, sanitize them without
putting raw SSIDs or phone IP addresses in the Task 8 directory, and calculate
their SHA-256 values. A hash is an evidence hook, not proof by itself: do not set
the variables unless the corresponding physical observation exists. Then run:

```bash
adb devices -l
make e2e-offline-pairing \
  E2E_DEVICE_A=<serial-a> \
  E2E_DEVICE_B=<serial-b> \
  E2E_PACKET_EVIDENCE_SHA256=<sha256> \
  E2E_DNS_EVIDENCE_SHA256=<sha256> \
  E2E_OFFLINE_PAIRING_EVIDENCE_DIR=/private/path/offline-pairing
```

The host rejects emulators, equal serials, stale completed/provisional state,
different Wi-Fi network hashes, absent internet-isolation evidence, mismatched
six-digit SAS values, non-reciprocal application identity hashes, missing sealed
LAN bindings, mismatched TLS-pin hashes, and failed process-restart recovery.
The QR payload, session handle, transcript, SAS, install token, SSID and phone IP
stay out of command arguments, normal control results, logs and artifacts. The
QR and confirmation handles cross ADB only through bounded app-private one-time
files fed over stdin. The output contains state codes and SHA-256 values only.

If two unlocked phones and the controlled no-uplink topology are not available,
record OFFLINE-PAIR-01 as pending. Unit tests and verifier self-tests do not
constitute physical acceptance or packet evidence.

These scenarios are run on two real Android 14+ phones: one Pixel and one
Samsung. Use a fresh install for `PHY-PAIR-01`, record only scenario IDs,
states, timestamps, measurements, and stable error codes, and keep the
evidence directory private. Never collect notification titles/text, extras,
payloads, encryption keys, JWTs, nonces, phone numbers, contacts, or unrelated
logcat.

For every scenario, capture:

1. a sanitized state snapshot before and after each stimulus;
2. an event timeline with monotonic timestamps and no notification content;
3. the expected durable-state assertion and Android-visible assertion;
4. cleanup confirmation (service stopped, test notifications removed, and
   temporary pairing state cleared where the scenario requires it).

### PHY-PAIR-01 - pairing and restart recovery

- [ ] Status - pending physical two-phone run.

On both fresh phones, grant notification and notification-listener access,
pair using the real QR/fingerprint flow, and verify reciprocal device IDs and
protocol floor 2. Post one test notification from a separate source app,
confirm one mirror, force-stop and relaunch both apps, then verify the pair and
pending state recover without a duplicate mirror. Save the pairing result,
restart result, and sanitized timeline.

### PHY-DOZE-01 - locked-screen delivery

- [ ] Status - pending physical two-phone run.

With both phones paired, lock the screens and leave each phone idle long enough
for Doze to engage. Introduce a post and an update on the Pixel, then wake the
Samsung. Verify durable sequence/order, one final mirror, and no duplicate
delivery after the wake/reconnect. Record the Doze/awake timestamps and the
sanitized notification-state assertions only.

### PHY-OEM-01 - Samsung background restrictions

- [ ] Status - pending physical two-phone run.

On Samsung, record the selected battery/background policy (without account or
device identifiers), apply the documented unrestricted/optimized states in
turn, reboot, and verify the listener rebinds and the relay connection returns
to protocol floor 2. Post while backgrounded and verify queue convergence after
resume. Capture the policy result, reboot/rebind state, and timeline.

### PHY-NET-01 - network handoff and relay restart

- [ ] Status - pending physical two-phone run.

Move the pair between Wi-Fi and mobile data (or a controlled equivalent), then
restart the relay using the same durable database. Post during the outage and
after reconnect. Verify outbox/inbound/materialization counters converge to
zero, sequence numbers do not regress, and no duplicate mirror remains.

### PHY-BATTERY-01 - 24-hour battery protocol

- [ ] Status - pending physical two-phone run.

Reset batterystats, start both services, and run for 24 hours at approximately
100 notifications per day (including updates and dismissals). Record start/end
charge and the final `dumpsys batterystats` output for each phone. The release
target is below 1.5% per 24 hours on both devices under this protocol. Store
only the two batterystats files and aggregate measurements; redact unrelated
package and account data.

### PHY-RELIABILITY-01 - recovery matrix

- [ ] Status - pending physical two-phone run.

Run permission revoke/restore, app update, explicit user stop, process
force-stop/restart, and reboot in separate bounded rounds. For each round,
verify that accepted notifications are delivered exactly once, queued work is
replayed after reconnect, and dismiss/update state does not resurrect. Record
the round ID, injected fault, durable state, and cleanup result.

### PHY-CALL-01 - real two-phone call-state product

- [ ] Status - pending physical two-phone run.

Use two Android 14+ phones with cellular calling available and an approved,
protected candidate installed. Run both directions, with each phone acting once
as the phone receiving the cellular call and once as the remote mirror.

1. From Settings, deny `READ_PHONE_STATE`; verify `Mirror call state` remains
   off. Deny permanently, use the single Android-settings recovery action,
   grant permission, enable capture, restart the process, and verify the durable
   switch remains on. Revoke permission and verify capture is disabled until
   the user explicitly recovers it. Never request this permission from
   onboarding or before the Settings action.
2. Complete one relay round and one authenticated direct-LAN round. Across the
   rounds cover both call directions and a real incoming sequence of
   `ringing -> active -> idle`, including one screen-off observation and one
   process restart while the call is active.
3. Verify one stable remote tag/id, channel `mirrored_call_state_v1` at actual
   manager importance HIGH, private action-free presentation, terminal removal,
   durable custody, a peer receipt, and no duplicate or resurrection.
4. Explicitly disable call capture and separately stop the sync service. Verify
   graceful terminalization and that no later callback recreates the mirror.

Retain only state enums, bounded health codes, route (`lan` or `relay`),
timestamps, counts, hashes, and pass/fail. Never retain phone numbers, contacts,
SIM data, call audio, call-log rows, raw notification content, device IDs, IPs,
SSIDs, keys, or tokens. If two suitable phones or the protected candidate is
unavailable, leave this scenario pending. Synthetic injection and emulator runs
must not be recorded as `PHY-CALL-01`.

### Release evidence layout and audit

Place the APK, sanitized E2E result, sanitized timeline, operator notes, and
Pixel/Samsung batterystats under one private directory. Write `manifest.json`
using the contract in [`docs/release-evidence/README.md`](release-evidence/README.md),
then run:

```bash
./scripts/verify-release-evidence.sh --self-test
make release-audit RELEASE_EVIDENCE_DIR=/private/path/twinotify-release
```

The self-test is fixture-only. It proves verifier behavior and must never be
used as physical evidence. The audit fails when either device capture is
missing, any hash or commit does not match the current release, a required
scenario is not explicitly `pass`, or a required artifact is absent.

## Direct LAN delivery

These scenarios prove the notification arrived **and** that the direct route
carried it. Asserting only the mirror would pass just as well over the relay, so
each one asserts the observed route as well.

| Scenario | Proves |
| --- | --- |
| `lan-direct-delivery` | A post crosses authenticated LAN, mirrors once, reaches LAN custody and peer receipt, and drains |
| `lan-direct-reverse-delivery` | B post crosses authenticated LAN, materializes exactly once on A, reaches B-side LAN custody and peer receipt, and drains |
| `lan-direct-dismiss` | A cancel converges over authenticated LAN without resurrection |
| `lan-direct-update` | Three semantic versions converge to sequence 3 with two update custody transitions |
| `lan-direct-peer-dismiss` | A user dismissal on the mirror returns one cancel without resurrection |
| `lan-direct-call-state` | Synthetic ringing, active, and idle states converge with LAN custody and receipts |
| `lan-direct-snapshot-receipt` | Digest, begin, item, end, commit, and receipt evidence converge |
| `lan-relay-fallback-return` | App-internal LAN availability is disabled on both peers, one tagged delivery proves relay custody, LAN is restored, and a second tagged delivery proves LAN custody |
| `lan-restart-persistence` | Durable work survives A force-stop and typed-launcher restart, then B is restarted the same way and a second LAN delivery converges without clearing data |
| `lan-direct-burst-backpressure` | A bounded unique burst stays below 2,000 rows and 128 MiB, then reaches terminal zero |
| `lan-direct-unpair-during-traffic` | A nonzero producer is joined, one unpair reaches LAN custody, both peers wipe, and state does not recreate |
| `lan-product-correctness` | Runs the eleven scenarios above in order, fails fast, and retains every completed child plus the failed child's evidence independently |

Automation is implemented and host-tested through commit `9c136cc`. The requested hardware execution remains pending:

- [ ] Aggregate direct-LAN product acceptance - pending physical two-phone run.

Prerequisites and safety rules:

1. Use two explicit, distinct unlocked hardware serials already paired with the same current debug build on the same operator-controlled Wi-Fi.
2. Keep the private evidence directory outside the repository. The verifier retains only sanitized route, counter, timestamp, stable-code, and hashed state evidence.
3. Do not clear package data, uninstall, toggle Wi-Fi or mobile data, use airplane mode, or run any radio/network mutation. The target contains no such commands.
4. Do not auto-pair or auto-re-pair. The aggregate intentionally runs unpair last, so both phones finish unpaired and any later pairing is a manual operator action.
5. Use the default burst count of 256, or set `E2E_LAN_BURST_COUNT` to an integer from 2 through 1,000.

Run the exact aggregate target from the repository root:

```bash
E2E_DEVICE_A='<serial-a>' \
E2E_DEVICE_B='<serial-b>' \
E2E_LAN_PRODUCT_EVIDENCE_DIR='/private/path/lan-product' \
make e2e-lan-product
```

The target invokes `e2e/cmd/twinotify-e2e` with `-scenario lan-product-correctness`, writes the aggregate artifacts under the supplied directory, and then runs `scripts/verify-lan-product-evidence.sh`. The root and each `children/NN-<scenario>/` directory must contain `scenario-result.json`, `state.json`, `timeline.json`, and `metrics.json`. A missing, failed, unsafe, secret-bearing, or semantically incomplete artifact fails the run.

The fallback child changes only the app-internal route availability preference;
it never mutates an OS radio. The restart child issues separate, bounded
force-stop and typed-launcher actions for each package and never clears app
data. Ordered content-free route events retain both fallback deliveries, while
the final route block describes the last delivery only.

The route predicates (`A.route.lan`, `B.route.relay`, `A.route.queued`) read the
device's public route status only. They require `phase == authenticated`, so a
route that is merely connecting never satisfies them.

### Route evidence

Every scenario result may carry a `route` block:

| Field | Meaning |
| --- | --- |
| `route` | `lan`, `relay`, or `none` |
| `phase` | `idle`, `connecting`, `authenticated`, or `reconnecting` |
| `route_generation` | How many times the route has been re-established |
| `queued_count` / `queued_bytes` | Durable work still awaiting delivery |
| `receipt_at_ms` | When the peer receipt landed |
| `error_code` | A stable code, never free-form text |

Writing evidence fails closed if the block is half-filled, if a counter is
negative, if an error code is not a stable code, or if any field anywhere in the
result carries a network identifier or secret material (addresses, URLs, MAC
addresses, SSIDs, key or token fields, private-key blocks, or long base64 runs).
A 64-character hex digest is allowed, because it is bounded and not reversible.
A scenario that observed no route at all omits the block rather than inventing
one.

### Still operator-driven

The host harness implements bounded burst and unpair-during-traffic scenarios,
but their physical handset execution remains pending. These additional topology
and lifecycle checks also remain operator-driven:

- **LAN loss with relay fallback, and the return to LAN.** Turning a device's
  network off removes the direct route and the relay together, so it cannot
  isolate the two. A device control that disables only the direct route is
  needed before this can be a host scenario.
- **Process restart while direct traffic is pending.** This needs two live
  handsets and operator observation of the restarted app and notification tray.
- **The controlled no-uplink run.** This needs a network with no internet path
  plus packet and DNS observation, per
  [`scripts/verify-offline-pairing-evidence.sh`](../scripts/verify-offline-pairing-evidence.sh).
  Nothing in the automated suite may be presented as this evidence.

- [ ] LAN-loss, restart, burst, unpair, and no-uplink operator matrix - pending physical two-phone run.
