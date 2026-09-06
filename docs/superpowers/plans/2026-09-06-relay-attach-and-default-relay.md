# Relay Attach and Default Relay Implementation Plan

> **For agentic workers:** Execute this plan inline, task by task, using test-driven
> development and verification-before-completion. Work in the primary checkout. Pause for the
> user on the UI decisions marked **USER DECISION**; everything else is settled here.

**Goal:** Let an already-paired device pair add, change, or remove a relay from paired-device
settings — without unpairing, without re-verifying a fingerprint, and without losing the LAN or
Bluetooth bindings. Ship a default relay so nobody has to type a URL to get started.

**Reported by the user, 2026-09-06:** two phones on different networks (one Wi-Fi, one mobile
data) show "Queued on this phone" forever. Root cause is not a defect in delivery: the pair was
created with "Pair nearby without internet", so no relay record exists on either the phones or
the relay, and `LiveTransportRoutesFactory.create` hands `TransportCoordinator` a null relay
(`LiveTransportRoutes.kt:225`). Cross-network delivery is impossible by construction, and the
Settings row that would fix it is inert. `onboarding/connect.tsx:74` nevertheless promises "You
can add the other connection method later from the paired-device settings."

**Architecture:** Add one new inner control event, `relay.attach`, modeled exactly on the
existing `lan.bootstrap`. That precedent already carries "here is how to reach me on another
transport" as an authenticated, receipt-backed event inside the ciphertext, so `relay.attach` is
its mirror image: `lan.bootstrap` adds a direct path to a relay pair, `relay.attach` adds a relay
path to a direct pair. Because it is an inner event rather than a wire frame, it rides LAN and
Bluetooth unchanged and needs no edit to `LanFrameCodec`, `BluetoothFrameCodec`, or any
transport. The relay-side four-step `/pair/*` handshake is reused verbatim; the relay learns
nothing new and needs no endpoint change.

**Tech Stack:** Kotlin/coroutines/Room, Expo Router + React Native, Go relay (schema fixture
only), JSON Schema 2020-12.

## Why this needs no fingerprint screen

First-time relay pairing asks a human to compare fingerprints because neither device has met the
other. In attach mode both devices already hold a verified `PeerRecord`. Each side therefore
requires the identity that arrives over the relay handshake to be **byte-identical** to the peer
it already trusts (`deviceId`, `encPubkey`, `signPubkey`) and aborts the attach on any mismatch,
changing nothing. That is a strictly stronger check than human fingerprint comparison, and it is
why the user's verified fingerprint survives an attach unchanged.

## Global Constraints

- Work in the primary checkout; preserve unrelated changes. No worktree.
- No Room migration. No new dependency. No change to identity-key generation or rotation.
- No relay endpoint, handler, or store change. The only relay-repo edit is the generated schema
  contract plus its fixture.
- `relay/internal/server/schemas/` is generated — edit `proto/` and run `make sync-proto`.
- An attach must never clobber `lanBindingId` or the Bluetooth association. `storePeerPubkeys`
  (`TwinotifyCoreModule.kt:1094`) writes a fresh `PeerRecord` with those fields defaulted to
  null; attach must not reuse that path.
- Exactly one coordinator-granted outbox drainer. Attach sends through the granted session like
  any other event; it introduces no second drain path.
- A failed or aborted attach leaves the pair exactly as it was, including the working direct
  route. Partial state is not acceptable.

## Acceptance criteria

1. A nearby-paired pair can add a relay from paired-device settings, over its existing direct
   route, and then deliver across networks — with the same fingerprint, LAN binding, and
   Bluetooth association it had before.
2. An identity mismatch during attach aborts and changes nothing on either phone.
3. The relay URL field is pre-filled from build config; a custom relay is still reachable in the
   UI and still gated on a successful `/health` test.
4. Relay can be changed and removed as well as added, with removal revoking at the old relay and
   leaving direct routes intact.
5. Kotlin JVM tests, `cd e2e && go test ./... -race -count=1`, and `make relay-test` pass. The
   two-phone cross-network scenario is executed on hardware and recorded honestly.

---

### Task 1: Ship a default relay from build config

**Files:** `mobile/app.json`, `mobile/app/onboarding/relay.tsx`,
`mobile/app/onboarding/__tests__/`.

**Interface:** `expo.extra.defaultRelayUrl` supplies the pre-filled relay; a build can override
it without a code edit. `DEFAULT_RELAY` stops being an empty string constant.

Context: `DEFAULT_RELAY` was `wss://relay.twinotify.app` until commit `92560bb`, which blanked it
as a side effect of switching the Test button to `/health`. That domain is not owned. The user
chose `https://relay.twinotify.nuvaynlabs.com`, which is live (HTTP 200 in 0.65s, valid TLS).

- [x] Add `"defaultRelayUrl": "https://relay.twinotify.nuvaynlabs.com"` to `expo.extra` in
      `mobile/app.json`.
- [x] Read it in `relay.tsx` via `Constants.expoConfig?.extra?.defaultRelayUrl` (the screen's
      sibling `settings/index.tsx` already imports `expo-constants`), falling back to `''`.
      Read through a `defaultRelayUrl()` helper at render rather than a module-level constant,
      so build config is not frozen at import time.
- [x] Keep the existing `/health` Test gate exactly as it is: `canContinue` stays
      `testState === 'ok'`, so a pre-filled default is still proven reachable before use.
- [x] Tests: the field pre-fills from `extra`; Continue stays disabled until Test succeeds; a
      user-typed custom URL overrides the default; a missing `extra` value degrades to the
      current empty-field behaviour. `app/onboarding/__tests__/relayDefault.test.tsx`, 5 tests.

**USER DECISION — taken provisionally, confirm before Task 5.** Built as a plain pre-filled text
field, which changed no layout and needed no design pass. The "Recommended relay" choice
treatment remains open and is the better fit if Task 5's screen adopts the same pattern.

---

### Task 2: Define the `relay.attach` contract

**Files:** `proto/inner-event-v2.schema.json`,
`mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/protocol/ProtocolJson.kt`,
`mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReliableDeliveryDao.kt`,
`relay/internal/server/fixture_test.go`, plus the Kotlin protocol tests.

**Interface:** inner type `relay.attach`, payload `{ relay_url, pair_token }`, no `canon_id`, no
`sequence`, fixed TTL. Modeled line-for-line on the `lan.bootstrap` clauses.

The relay never validates inner events at runtime — `validator.innerV2` is referenced only from
`fixture_test.go`. The proto edit is a contract change for the two Android sides plus that
fixture; no relay handler or store code changes.

- [x] Add `"relay.attach"` to the `type` enum in `proto/inner-event-v2.schema.json`, to the
      shared no-`canon_id`/`sequence` clause, and a conditional subschema requiring `relay_url`
      (https/wss only) and `pair_token` (16..128), `additionalProperties: false`.
- [x] `make sync-proto`, then add fixtures and assert the relay's existing fixture test accepts
      the valid one and rejects the invalid ones. Observed `v2-valid/relay-attach-inner.json`
      fail against the pre-change enum before keeping the schema edit.
      Fixtures: `v2-valid/relay-attach-inner.json`, `v2-invalid/relay-attach-extra-field.json`,
      `v2-invalid/relay-attach-cleartext-url.json`, `v2-invalid/relay-attach-canon-id.json`.
- [x] Wire fixture type `relay_attach_inner` into **both** harnesses that read the shared
      manifest — `relay/internal/server/fixture_test.go` and Kotlin `ProtocolFixtureTest.kt`,
      including the latter's `observedFixtureCode` classifier.
- [x] `ProtocolJson.kt`: add `relay.attach` to `innerTypes`, add `RELAY_ATTACH_TTL_MS` = 300_000
      (matching the relay's five-minute pair-token lifetime), and add
      `validateRelayAttachPayload` rejecting `canon_id`/`sequence` and any non-TLS URL.
- [x] Kotlin tests first: encode/decode round trip, TTL enforcement, rejection of a cleartext
      relay URL, rejection of `canon_id`/`sequence`. Observed
      `unsupported inner event type relay.attach` before implementing.

**Moved to Task 4 —** `RECEIPT_BACKED_CONTROL_TYPES` was originally listed here. It must not
change until the responder exists: `InboundDispatcher` gates on that set at line 870 and then
switches on `inner.type` to pick a processor, so adding `relay.attach` to the set without its
processor branch leaves a well-formed peer event hitting an unhandled branch. The contract and
the behaviour have to land together.

---

### Task 3: Attach initiator

**Files:** new
`mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing/RelayAttachCoordinator.kt`,
`TwinotifyCoreModule.kt`, tests alongside.

**Interface:** `RelayAttachCoordinator.attach(relayUrl): AttachResult`, exposed as one
`AsyncFunction("attachRelay")`.

- [x] Validate `relayUrl` through `RelayUrlPolicy.parse(debug = false)` before anything else, so
      a cleartext or malformed URL fails without touching the relay or the peer. `debug = false`
      is deliberate: the policy's loopback exception is a local development affordance, and a
      relay the *peer* must also reach is never loopback.
- [x] Order the handshake init → announce → await → verify → sign → commit, with the announcement
      between init and wait, since the peer cannot answer a handshake it has not been told about.
- [x] Require the peer identity returned by the handshake to be byte-identical to the stored
      `PeerRecord`. On mismatch, abort before signing and leave every store untouched.
- [x] Bounded rejection vocabulary: `relay_url_invalid`, `not_paired`, `no_direct_route`,
      `relay_unreachable`, `peer_timeout`, `peer_identity_mismatch`, `store_failed`. No code
      carries a URL or a key.
- [x] `PeerControlOutbox.enqueueRelayAttach` seals the event receipt-backed at 300_000 ms,
      validating through `ProtocolJson.encodeInner` before sealing so a cleartext or malformed
      relay cannot reach the wire. Not generation-deduplicated: each attach carries its own
      single-use pair token, so a retry is a new offer rather than a repeat.
- [x] Tests: happy path and call ordering; identity mismatch (device id, enc key, sign key) with
      nothing committed; unpaired; relay failure at each of the three stages; cleartext refused
      at both the coordinator and the outbox. `RelayAttachCoordinatorTest` (5) and two added to
      `PeerControlOutboxTest`.

Remaining for this task, all of it production wiring rather than logic:

- [x] Production `LiveRelayAttachRelayClient` backed by `PairProtocol.initiate` /
      `PairNotifyClient.awaitAuthenticatedFrame` / `PairProtocol.sendConfirmationSig`, using the
      **existing** identity from `CryptoStore.loadOrGenerate` and `DeviceIdentity.getOrCreate`.
      Waits 90 s rather than the pairing screen's five minutes; `awaitPeerHello` takes the
      identity so the client stays stateless.
- [x] `commit`: persists via `ServiceConfigStore.setRelayUrl` and calls
      `SyncService.notifyRelayConfigChanged`, which restarts the coordinator through the same
      serialized owner a preference toggle uses. Does **not** route through `storePeerPubkeys`,
      so `lanBindingId`, `displayName` and the Bluetooth association survive.
- [x] `AsyncFunction("attachRelay")` in `TwinotifyCoreModule.kt`, plus the `RelayAttachOutcome`
      TS union in both `types/twinotify.d.ts` and `modules/twinotify-core/src/`.

- [ ] Still open: the JS-side `OnboardingState.setRelayUrl` write. `home.tsx:53` reads it to
      choose `startSyncService` over `startLanOnlySyncService`, so the Task 5 caller must set it
      when `attachRelay` resolves `attached`, or a later mirror toggle drops back to LAN-only.

Accepted limitation, matching the existing pairing flow: the initiator commits its relay URL
after sending the confirmation signature, without waiting for the responder's `/pair/complete`.
If the responder never completes, the initiator holds a relay URL the relay will not authenticate
and simply keeps using its direct route. That is recoverable and non-destructive, and it is the
same property first-time pairing already has, where A stores the peer before B completes.

---

### Task 4: Attach responder

**Files:** new
`mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/RelayAttachProcessor.kt`,
`SyncService.kt` wiring, tests alongside.

**Interface:** `RelayAttachProcessor.process(payload): RelayAttachResult`, mirroring
`LanBootstrapProcessor` (`LanBootstrapProcessor.kt:23`) including its `Applied`/`Rejected` result
shape and bounded rejection codes.

- [x] The incoming relay URL needs no check in the dispatcher: `decodeInner` already refuses any
      `relay_url` that is not https or wss before the event is authenticated. A second copy would
      only drift from the contract, so the dispatcher carries none and a test forges the decrypted
      bytes to prove that boundary holds.
- [x] Run `PairProtocol.sendPeerHello` then `deviceBCompletePair` against that relay with the
      existing identity, via `LiveRelayAttachResponderClient`.
- [x] Verify the initiator's confirmation signature against the **stored** peer signing key, and
      complete with the **stored** peer public keys — never anything the relay supplied. Reject
      with `peer_identity_mismatch` on failure, before completing or committing.
- [x] Persist the relay URL and restart the transport generation, preserving all direct bindings.
- [x] Add `"relay.attach"` to `RECEIPT_BACKED_CONTROL_TYPES` in the **two** places it is
      duplicated — `ReliableDeliveryDao.kt` and `InboundDispatcher.kt` — in the same change as the
      processor branch. `DIRECT_ACK_CONTROL_TYPES` is duplicated the same way; deduplicating both
      is a worthwhile follow-up but is out of scope here.
- [x] Wire into the inbound control path next to the `lan.bootstrap` handler, **after** the
      receipt commits rather than inside the journal lambda. That lambda runs inside the Room
      write transaction and the handshake blocks until the initiator signs, so applying it there
      would hold the write transaction open for up to ninety seconds and stall every delivery.
- [x] Tests: applied; rejected on signature mismatch; rejected on cleartext URL at the decode
      boundary; unpaired; relay failure at each stage commits nothing; completion uses the stored
      keys. `RelayAttachResponderTest` (7) plus two in `InboundDispatcherControlTest`.

**USER DECISION — settled 2026-09-06: auto-apply.** The receiving phone applies a `relay.attach`
from its trusted peer without prompting. The byte-equality identity check proves the event came
from the phone the user already verified, and the contract refuses non-TLS relays, so no trust
decision is left for a human to make. A prompt can be added later without a protocol change.

---

### Task 5: Relay management in settings — add, change, remove

**Files:** `mobile/app/settings/index.tsx`, `mobile/app/settings/pair.tsx`, a new relay screen
under `mobile/app/settings/`, tests alongside.

Today the relay row is inert (`settings/index.tsx:277`) and `settings/pair.tsx:309` offers only
the reverse direction, "Add a direct Wi-Fi path without replacing this relay pair."

- [x] Relay management lives on the paired-device screen beside nearby Wi-Fi and Bluetooth, since
      all three are the same kind of action. It reuses that screen's existing shape: an offer is
      a card, a configured route is a row group.
- [x] Add: relay address pre-filled per Task 1, `/health` test gate, then `attachRelay`. The
      caller writes `OnboardingState.setRelayUrl` on success, closing the Task 3 loose end.
- [x] Change: opens the same screen in change mode. The new relay is attached before anything is
      revoked, so a failed change leaves the old relay in place.
- [x] Remove: `detachRelay` revokes at the relay, clears the endpoint and restarts direct-only.
      Not an unpair: peer record, LAN binding, Bluetooth association and fingerprint all survive.
      A relay that cannot be reached clears locally anyway and the copy says so.
- [x] Copy states plainly that contents stay end to end encrypted, and every failure promises
      nothing changed.
- [x] `onboarding/connect.tsx` promise is now true in both directions, and the Settings row leads
      to management instead of stating a dead end.
- [x] Tests: all three flows, the confirm step, the unrevoked case, the test gate withdrawing on
      edit, cleartext refusal, each bounded failure code, and a contrast floor.
      `app/settings/__tests__/relayManagement.test.tsx` (11) plus an added
      `settingsHandoffTrace` case.

**USER DECISIONS — settled 2026-09-06.** Placement: the paired-device screen. Default relay
presentation: a plain pre-filled field, matching onboarding, so both screens read the same.

**Two defects found during the design re-check, both fixed:**
- `ink4` measures 3.22:1 on the light background, below the 4.5:1 that small text needs. The
  field label and placeholder moved to `ink3` (5.24:1 light, 7.11:1 dark), here and in
  `onboarding/relay.tsx`, which had the same shortfall already. A regression test measures it.
- A `TwRow` whose `onPress` is dropped while busy renders as a plain view: identical to look at,
  but it answers nothing. Removal now names its own progress and the change row is hidden while
  it runs, rather than sitting there inert.

### Task 6: Verification

**Files:** `docs/test-scenarios.md`, `docs/evidence/`, this plan.

- [ ] Kotlin JVM tests, `npm run typecheck`, `npm run lint`.
- [ ] `make relay-test` and `cd e2e && go test ./... -race -count=1`.
- [ ] Hardware matrix on the two phones: attach over LAN; attach over Bluetooth with Wi-Fi off;
      cross-network delivery afterwards; change; remove; identity-mismatch abort.
- [ ] Record what was proven on hardware and what remains simulator-only, honestly. A passing
      emulator run is not evidence of physical radio behaviour.

## Known baseline risk

The working tree carries uncommitted Bluetooth full-delivery work (plan
`2026-09-05-bluetooth-full-delivery.md`, Tasks 1-4 complete) touching `SyncService.kt`,
`DirectDelivery.kt`, `TransportCoordinator.kt`, and both frame codecs. Task 4 of this plan also
edits `SyncService.kt`. Commit the Bluetooth work first so these two efforts stay bisectable and
this plan starts from a clean base.
