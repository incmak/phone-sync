# Test Scenarios

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
