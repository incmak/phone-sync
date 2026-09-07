# Plan 031 — Native macOS receiver and three-device delivery

Status: IN PROGRESS. This replaces earlier macOS proposals. Source of intent:
user-approved replacement plan supplied 2026-09-07. No deployment, migration,
signing, or device acceptance is implied by this record.

## Outcome and constraints

Native Swift menu-bar receiver for macOS 26 / arm64, locally signed with an
explicit identity. Two active peer links per device enable A↔B, A↔Mac, B↔Mac.
Phone dismissals converge through the origin; Mac dismissals remain local.
Permission denial preserves pending reliable work. Native controls/system type,
Twinotify colors/icon, QR JSON paste and image import, launch at login off.
No Mac capture, reply/actions, call controls, LAN, Bluetooth, live QR camera,
Intel, public distribution, notarization, or updater. Preserve phone↔phone routes.
Work in the primary checkout and preserve pre-existing edits.

## Ordered implementation

0. **Executable contracts:** correct Room 11 guidance, conversation schema with
   Kotlin limits/nullability, distinct v2 cancellation documentation, receipt and
   malformed fixtures; dispatch by manifest scope/type. Shared box/signature,
   pairing-domain, fingerprint and nonce known-answer vectors in Swift/Android.
   Exercise exact-byte digests (order, whitespace, escapes) against live relay.
   Generated relay schemas stay uncommitted.
1. **A1 Foundation:** SwiftPM TwinotifyKit + TwinotifyMac + tests + reproducible
   bundle, pinned swift-sodium packaged macOS target and lockfile. Inject platform
   posting. Gate dependency build, signed permission/post/remove and relay lifetime
   beyond two heartbeat intervals. CI needs no personal signing credentials.
2. **A2 Persistence:** nonsynchronizing Keychain identity/content key; versioned
   SQLite for links, journals, desired state, retries, snapshots and sealed outbox.
   CryptoKit AEAD for content; metadata-only activity. Persist global 16-byte
   prefix + big-endian counter increment before sealing, starting at 1; reject
   overflow/storage inconsistency. Peer removal never resets identity/nonce.
   Durable bounded admission never evicts accepted or pending reliable work.
3. **A3 Protocol/pairing:** real four-step Device-B flow, signed notify, resumable
   state, expiry, fingerprint approval. Validate QR and URLs (debug loopback-only
   cleartext). Advertise only [2]. Fresh JWT jti per request/connection. Preserve
   exact envelope bytes; bounded escape-aware scanner rejects duplicate fields.
   Decode all inner types; authenticate selected link and matching inner/outer
   sender, decoded IDs and integer timestamps.
4. **A4 Receive:** journal (link generation,msg_id), reject changed digest;
   transactionally commit PENDING_PLATFORM + desired state, materialize latest
   sequence, persist terminal outcome/receipt, obtain receipt relay custody,
   persist custody, then ack original. Independently resume these stages after
   crash. Terminal duplicates replay logical receipt; pending duplicates resume.
   Permission-blocked work stays pending; recheck expiry/supersession. Preserve
   receipt reconstruction after outbound removal. No receipt-of-receipt; durable
   snapshot controls/digests direct-ack. Reject unsupported authenticated controls;
   dedicated unpair lifecycle. Invalid unauthenticated input cannot mutate state.
5. **A5 Platform/UI:** stable link-generation/canonical IDs and sequence userInfo;
   inspect delivered/pending requests before uncertain retry. Same-ID update,
   cancel pending+delivered; do not repost locally dismissed completed sequence.
   Secret/group filtering with visibility updates, payload text precedence,
   bounded images with text fallback. Ringing incoming calls only; clear on
   active/idle/expiry. Peer status, permission/storage/pending indicators,
   pairing/removal and metadata activity. Window close retains service, Quit
   stops transport; wake/network reconnect; permission checks activation/wake/post.
   Native light/dark, narrow-window, keyboard and accessibility verification.
6. **B1 Relay selection:** device identity registry pins keys across memberships;
   two-link cap. Explicit (device,pair) sessions, query/revoke/JWT selectors agree.
   Old missing selectors resolve exactly one active pair; ambiguity fails.
   Keep handshake signature inputs unchanged.
7. **B2 Relay migration:** version membership/mailbox/order/status/expiry/sequence/
   capability indexes by generation. Validated backfill fails closed; quotas stay
   aggregate per device. Sockets/scheduling pair scoped; transactional auth and
   cancellation replacement retained. Revoke only selected pair; preserve shared
   identity. Test previous supported binary refuses new storage. Backup before
   migration; rollback after new writes requires stop and explicit restore decision.
8. **C1 Android storage:** stable peerLinkId/device/pair/lifecycle, per-link routes;
   idempotent DataStore import before service startup. Room 11→12 + schema, scope
   all inbound/outbound/receipt/snapshot/control records; preserve ciphertext,
   keys, sequences and custody. Bridge and Tw screens accept explicit peer links.
9. **C2 Fan-out:** one sequence, distinct immutable encrypted envelope per peer;
   one transaction commits canonical state and every recipient. All-or-nothing
   capacity with source reconciliation on rejection; link-scoped compaction and
   receipts. Per-link coordinator/drainer; phone direct routes preserved.
10. **C3 Convergence:** mirror dismissal goes only to origin; authenticate owner
    and stale sequence. Durably stage source cancel plus fresh per-peer cancels,
    release after platform success, sequence exceeds state/request, idempotent
    duplicate request. Preserve echo suppression. Never forward peer ciphertext.
    Per-link authoritative snapshots after drain/reconnect/expiry and every five
    minutes; bounded staging/count+digest, distinguish empty from unavailable,
    authenticated origin scope and newer live events win.
11. **C4 Removal:** persist removing, stop/join selected sessions, exclude fan-out,
    revoke selected relay/direct bindings, clear only its work/mirrors. Preserve
    source state, other links, identity and nonce even for last link. Offline revoke
    retains disabled cleanup/retry record. Incoming unpair scopes to sender link.

## Gates and rollout

Crypto vectors/decoders/JWT/expiry/malformed/digest tests; crash injection before
and after desired commit, post, receipt persist/custody and ack. Signed Mac grant/
denial/recovery, update/cancel/call, wake/reconnect/local dismissal. Stable entries
and convergent state are guaranteed; an unknown platform result across a crash
may repeat a banner (do not claim exactly-once alerts).

Relay migration with pending mail/status/floor/capabilities, wrong-pair read/ack/
revoke, removal isolation and Go -race. Android import interruption and Room
migration. Atomic fan-out, capacity rejection, compaction/receipt isolation.
Three-device post/update/cancel/dismiss with offline Mac, concurrent updates,
interrupted/empty snapshots. Removal during pending traffic/offline/reconnect.

Keep make verify Linux-compatible; macos-verify and separate macOS CI. Distinct
Android and Mac E2E adapters, two emulators plus real Mac first. Personal-phone
checks require authorized inspection and synthetic notifications; no screenshots.
Land contracts/single-peer Mac, validate relay migration on database copy, deploy
backward-compatible relay, upgrade phones and verify their original link, then
permit extra links only after isolation gates. Product completion requires the
entire three-device matrix, permission recovery and scoped removal evidence.

## Execution evidence

- Initial workspace: modified AGENTS.md and untracked docs/agent-guidance/;
  retained and edited only relevant stale architecture facts.
- Room source confirmed version 11. Swift 6.3.3 / macOS 26 arm64 available.
- Conversation schema and shared fixtures added; initial proto-test passed.
- Remaining tasks and acceptance gates above remain open until recorded here.

### Current checkpoint — 2026-09-07

Implementation now includes the native Swift receiver, durable encrypted storage,
pairing and transport, platform adapter and native UI; relay pair membership and
migration; Android Room 12 multi-peer storage, atomic fan-out, per-link transport,
origin-authored dismissal, authoritative snapshots and scoped removal. These are
implemented paths, with the acceptance limitations below still open.

Verified evidence:

- Relay protocol fixtures, migration and isolation tests pass under `-race`.
  The previous supported relay binary was run against migrated fixture storage
  and refused it. The fixture preserves pending mailbox/status/index/capability,
  protocol-floor and acceptance-counter state. The relay Docker build passes.
- Android: 1,026 JVM tests passed before the final Expo patch rebuild. On the
  disposable API 37 emulator, Room migration, multi-peer storage/removal, nonce
  isolation and LAN binding tests passed. Latest focused LAN/lint run passed.
- JavaScript: all 305 tests, lint and typecheck passed after correcting native
  fingerprint separators in the displayed 64-character value. The full mobile gate found four
  required Expo patch updates; those dependencies and the lockfile were updated,
  and the full mobile gate now passes, including native lint, JVM tests,
  instrumentation compilation and all-architecture debug APK assembly.
- Swift: 23 tests pass, covering protocol/crypto, durable recovery, receipts,
  permission-state recovery through an injected platform, snapshots and removal.
  `macos-verify` passes. The opt-in real relay test also passed in 115.537
  seconds, exercising signed pairing, exact-byte custody and two heartbeat
  intervals. Ordinary and isolated E2E bundles both build and verify with an external
  local development signing identity.
- Host: all eight Go packages pass with `-race`, including separate Android/Mac
  adapters, pairing failure propagation and updated peer/platform status parsing.
- Two newly created disposable emulator installations completed real authenticated
  pairing through an isolated local relay and negotiated a direct LAN route.
  Their notification-delivery scenario passed with source/recipient sequences,
  peer receipt and drained user work; evidence is metadata-only.

Open acceptance and rollout gates:

- The isolated Mac bundle is signed with an external local development identity.
  Real first launch and restart preserve its identity using the local login
  Keychain; the initial Data Protection Keychain entitlement failure was fixed.
  Real OS denial kept a received notification durably pending and undisplayed;
  a forced process stop preserved it and the identity. Restoring OS permission
  delivered that same revision and drained pending work. The native menu and
  paired settings show current permission and connected peers; both test
  notification buttons and Quit work. Native QR input/fingerprint confirmation
  completed pairing; narrow light/dark views are readable. Keyboard activation,
  actual host sleep/wake and physical-phone acceptance remain open.
- The mixed driver completed both real phone↔Mac pairings. Its first delivery
  run exposed a relay capability response encoding an absent peer feature set as
  null; this is corrected to an empty array with a failing-before wire regression.
  The corrected relay passes the integrated post/update/cancel, Mac-local
  dismissal across repair, offline catch-up, phone dismissal, concurrent origins
  and scoped-removal matrix, including queued traffic during offline removal.
  Both phone-dismissal directions and signed call transitions also pass.
  A twelve-item real snapshot was interrupted after two rows were
  durably staged; reopening completed the snapshot with all entries and the
  same identity. An empty authoritative snapshot then converged to no active
  or delivered mirrors. Last-link removal preserved the Mac identity and nonce
  prefix/counter and left the original phone pair intact.
- The complete host gate now passes across the initial dependency/JavaScript
  checks and the corrected Go/scenario and remaining script checks. Local APKs
  and unsigned bundles are QA artifacts, not release candidates.
- No production database has been migrated and no relay has been deployed.
  Backup/restore decisions and production rollout remain pending; physical-phone
  acceptance has not been claimed from emulator or injected-platform tests.

The consolidated [acceptance record](../docs/qa/macos-three-device-2026-09-07.md)
contains reproduction commands, separate platform evidence and remaining manual
gates. Deployment failure handling now stops after possible migration and requires
an explicit restore decision; pre-start backup failure alone may resume the old
binary. Its failing-before regression and startup/readiness/smoke/signal checks pass.
