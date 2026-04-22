# Twinotify — Session Memory

> Self-contained handoff for a fresh Claude session. Load this, then work the project. Updated: 2026-04-21 post-Phase-3 merge.

---

## 1 · What the project is

**Twinotify** — end-to-end-encrypted Android ↔ Android notification mirroring. Tap/swipe on one phone dismisses on the other. Reply on one phone actually sends the message from the other. Tauri desktop receiver in later phases. Inspired by KDE Connect's protocol but Android-native and internet-capable (not LAN-only).

**Scope:** Personal-use first (architect for future public launch). Two phones, one user. Roadmap is 19 build phases split into 14 implementation-plan documents.

**Domains/namespaces user intends to grab:** `twinotify.com`, `twinotify.app`, `twinotify.io`, `github.com/twinotify`, `npmjs.com/~twinotify`, `com.twinotify.app` Play package.

---

## 2 · Repo layout

```
/Users/moaieen.kirmani/Documents/projects/Learn/phone-sync
├── relay/                        Go 1.23 relay (WebSocket + BoltDB)
│   ├── cmd/relay/main.go
│   ├── internal/server/          HTTP + /ws + /pair/* + JWT middleware + validator
│   ├── internal/store/           bbolt wrapper + PairStore
│   ├── Dockerfile                multi-stage distroless
│   ├── README.md                 Phase 1 security posture (DO NOT EXPOSE PUBLICLY)
│   └── go.mod                    go 1.23, chi, gorilla/websocket, bbolt, jsonschema/v6, golang-jwt/v5, google/uuid
├── mobile/                       Expo SDK 54 RN app
│   ├── app/                      Expo Router screens
│   │   ├── index.tsx             router: onboarding | home
│   │   ├── onboarding/           welcome, how, role, relay, perms, oem, ready
│   │   ├── pair/                 qr, scan, fingerprint, success, fail
│   │   ├── home.tsx              HOM-01: connection status + mirror toggle + recent
│   │   ├── settings/             index (groups) + pair (detail) + unpair confirm
│   │   └── filter.tsx            FIL-01: per-app allow/deny toggles (cosmetic in Phase 3)
│   ├── components/               tokens.ts, Theme.tsx, useFonts, primitives/*.tsx (15)
│   ├── hooks/                    useTwinotifyCore, useSyncStatus
│   ├── state/onboardingState.ts  AsyncStorage helper
│   ├── types/                    twinotify.d.ts, culori.d.ts
│   ├── modules/twinotify-core/   custom Expo Native Module (Kotlin)
│   │   └── android/src/main/java/co/twinotify/core/
│   │       ├── TwinotifyCoreModule.kt  # 21 AsyncFunctions exposed to JS
│   │       ├── crypto/                 # KeystoreMaster, WrappedKeys, CryptoStore, NonceSource, Encrypter
│   │       ├── auth/JwtMinter.kt
│   │       ├── pairing/                # Fingerprint, PairPayload, PairProtocol, PairNotifyClient (new)
│   │       ├── storage/                # DeviceIdentity, PeerStore, ReplayGuard,
│   │       │                           # NotificationDb (Room v2), MirroredFromPeer, LocalIdToCanonId,
│   │       │                           # NotificationMapDao, OutboundEvent, LocalIdTagPair
│   │       ├── listener/               # TwinotifyNotificationListener, ReasonCodeFilter,
│   │       │                           # CanonIdBuilder, NotifPostBuilder, PendingPeerCancel, OutboundSink
│   │       ├── service/                # SyncService (FGS), OutboundQueue, MirrorPoster,
│   │       │                           # MirrorDismisser, InboundDispatcher, QueuingOutboundSink,
│   │       │                           # NotifChannelSetup, BootReceiver, MirrorTapReceiver,
│   │       │                           # SyncServiceStatus, EncryptedEnvelope
│   │       └── filter/DenylistLoader.kt
│   └── eas.json                  dev client profile, APK build
├── proto/                        JSON Schema (v1) — packet, notif-post, notif-cancel, ping, pair-init, pair-complete
├── deploy/                       docker-compose + Caddy (TLS reverse proxy)
├── Makefile                      sync-proto (canonical single source of truth; schemas gitignored into relay embed)
├── .github/workflows/            relay (test + docker + smoke), mobile (tsc + expo-doctor)
└── docs/
    ├── design/SCREEN_INVENTORY.md    ~95 surfaces listed, handed to design AI
    ├── superpowers/
    │   ├── specs/2026-04-20-phone-sync-design.md   (v10; authoritative technical spec)
    │   └── plans/
    │       ├── 2026-04-20-phase-1-relay-proto-scaffold.md       (executed, merged)
    │       ├── 2026-04-21-phase-2-crypto-pairing.md             (executed, merged)
    │       └── 2026-04-21-phase-3-listener-first-mirror.md      (executed, merged 2026-04-21)
    ├── design/assets/tier-1-design-bundle/    (handoff from design AI — tokens/primitives/screens)
    └── test-scenarios.md         Phase 1 + Phase 2 + Phase 3 manual smoke procedures
```

---

## 3 · What's done (merged to main)

### Phase 1 — Relay + Proto + Mobile Scaffold

- Relay: chi router, /health, /ws WebSocket (1MB SetReadLimit, ping/pong keepalive, sync.Mutex writes, `CheckOrigin: true` with `TODO(phase-2)`), graceful shutdown on SIGTERM.
- Envelope validator: `jsonschema/v6`, schemas embedded via `go:embed`, resource URIs match `$id` (`https://phone-sync.local/schemas/`).
- bbolt KV wrapper (Put/Get/Delete).
- Dockerfile (distroless nonroot), docker-compose with port 8080 mapped.
- Proto: packet envelope + notif.post + notif.cancel + ping schemas. Envelope `type` enum trimmed to Phase 1 subset.
- Mobile: Expo app (Router), custom Native Module with `ping(url, authed)` — OkHttp WebSocket, AtomicBoolean settle guard, Handler.removeCallbacks on resolve.
- CI: `relay` (go test + docker build + smoke), `mobile` (tsc + expo-doctor).

### Phase 2 — Crypto + Pairing (headless; no UI yet)

**Relay-side:**

- `PairStore` (pending + confirmed + device→pair index) on bbolt.
- `POST /pair/init` stores pending record, 5-min expiry.
- `POST /pair/complete` enforces **Ed25519 `confirmation_sig` over 5-field message**: `pair_token || A_enc || A_sign || B_enc || B_sign`. Rejects unknown token (400), expired token (400), bad sig (400).
- JWT auth middleware on `/ws`: Ed25519 signed, verified against paired device's stored `sign_pubkey`.
- `JTICache` with **2×TTL retention** to close replay window between GC and JWT exp (prevents a narrow replay gap that 1×TTL allowed).

**Android-side (all in `phone-sync-core` native module):**

- `KeystoreMaster` — AES-256-GCM hardware-backed, StrongBox → TEE fallback.
- `WrappedKeys` — libsodium X25519 (Box) + Ed25519 (Sign) keypairs; secret bytes AES-GCM sealed; **libsodium JNA uses snake_case** (`sodium.crypto_box_keypair`, `crypto_box_easy`, `crypto_sign_detached`, etc.; NOT camelCase).
- `CryptoStore` — DataStore+Tink persistence (NOT deprecated EncryptedSharedPreferences).
- `NonceSource` — **16 random bytes + 8 counter** (counter atomically bumped + fsync'd before every encrypt, in DataStore). Prevents backup-restore nonce reuse that pure-random would allow.
- `Encrypter` — `crypto_box_easy`/`_open_easy` wrappers. **Typed `DecryptError.AuthFailed` / `.SizeMismatch`** — don't promote MAC failure to an unchecked exception.
- `ReplayGuard` — msg_id dedup, 48h TTL, opportunistic GC, DataStore-backed.
- `JwtMinter` — hand-rolled EdDSA JWT (`base64url(header).base64url(payload).base64url(sig)`); requires 64-byte libsodium sign key.
- `PairPayload` (QR JSON), `Fingerprint` (SHA-256 of `enc_pubkey||sign_pubkey` → 16 groups of 4 uppercase hex).
- `PairProtocol` — role-split: `initiate()` (Device A), `deviceASignConfirmation()` (Device A produces sig), `deviceBCompletePair()` (Device B POSTs with the sig).
- `DeviceIdentity` — persisted per-install UUID (replaces hardcoded "mobile").
- `PeerStore` — peer's pubkeys + device_id persisted.
- Native module exposes 12 AsyncFunctions: `getDeviceId`, `getPublicKeys`, `startPairInitiator`, `computeFingerprint`, `deviceASignConfirmation`, `deviceBCompletePairing`, `storePeerPubkeys`, `mintAuthJwt`, `encryptToPeer`, `decryptFromPeer`, `unpair`, `ping(url, authed)`.

**Smoke:** manual procedure documented in `docs/test-scenarios.md`; emulator ping confirmed working in Phase 1. Phase 2 requires two devices + JS console to drive pairing (no UI).

### Phase 3 — Listener + First Mirror + Tier-1 UI (merged 2026-04-21 as `6cb9a1b`)

**Task 0 — atomic rebrand `phonesync` → `twinotify`:** Kotlin package `co.twinotify.core` (was `expo.modules.phonesynccore`), module dir `mobile/modules/twinotify-core/`, Gradle namespace, Go module `github.com/twinotify/relay`, schema `$id` `https://twinotify.app/schemas/*` + validator `schemaBaseURL` matched byte-for-byte, DataStore keys `twinotify_*`, Keystore alias `twinotify.master`, Android package `com.twinotify.app`, scheme `twinotify://`, `.gitattributes` added locking denylist JSON to LF.

**Proto (Task 1):** `envelope-encrypted.schema.json` (type="enc" wrapper, nonce length locked to 32 base64 chars = 24 raw bytes), `ack.schema.json`, `pair-sig.schema.json` (Task 7). Validator gained `ValidateEncEnvelope` + `ValidateAck` methods alongside existing `ValidateEnvelope`.

**Storage (Task 2):** Room 2.7.1 via KSP. `NotificationDb` v2 with `MirroredFromPeer` (canonId PK) + `LocalIdToCanonId` (unique index on localId+localTag, FK CASCADE to canonId) + `OutboundEvent` (autoGen PK, 1000-row cap). DAO uses `@Transaction` methods on abstract class; `OnConflictStrategy.ABORT` on inserts — duplicate local_id collisions fail-fast. Migration(1,2) idempotent `CREATE TABLE IF NOT EXISTS`. FK enforcement enabled via `PRAGMA foreign_keys = ON` in `onOpen`.

**Listener (Task 3):** `TwinotifyNotificationListener` with self-package filter + reason-code truth table (`ReasonCodeFilter` pure object; exhaustive 18-case JVM test). `NotifPostBuilder` applies §4.7.3 privacy filters (VISIBILITY_SECRET, Android Auto `gearhead` package, car categories by literal string, denylist). Bitmap decode explicitly recycles to avoid GC pressure during bursts. `PendingPeerCancel` ConcurrentHashMap 30s TTL tombstone (`consume` removes; `contains` has documented side-effect eviction). `OutboundSink` interface — listener resolves via `TwinotifyNotificationListener.installedSink` companion set by SyncService.

**Service (Task 4):** `SyncService` FGS **Always-Connected only** (lazy-FGS deferred to Phase 9). OkHttp WebSocket `pingInterval(30s)` + `readTimeout(0)`, exp-backoff reconnect 1s→60s, JWT minted per-connect. `OutboundQueue` with atomic `enqueueCapped(@Transaction)` — count/drop-oldest/insert in single SQLite write. `flushQueue` guarded by `Mutex` — `onOpen` and `flushIfConnected` serialised, no duplicate sends. `InboundDispatcher` does ReplayGuard.checkAndAdd BEFORE decrypt. `MirrorPoster` (VISIBILITY_PRIVATE, IMPORTANCE_DEFAULT, auto-cancel, PendingIntent→`MirrorTapReceiver`). `MirrorDismisser` adds tombstone BEFORE `NotificationManagerCompat.cancel`. `BootReceiver` restarts service on boot if paired + relayUrl persisted. All Phase 3 perms (`BIND_NLS`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_REMOTE_MESSAGING`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`, `NEARBY_WIFI_DEVICES`, `ACCESS_LOCAL_NETWORK`, `INTERNET`) + `<service>` + receivers declared in **module manifest** (auto-merges — app manifest is gitignored Expo prebuild output).

**Native bridge (Task 5):** 9 new AsyncFunctions: `startSyncService`, `stopSyncService` (uses `ctx.stopService` not `startService(ACTION_STOP)`), `isNotificationListenerGranted`, `openListenerSettings`, `isPostNotificationsGranted`, `openAppSettings`, `getSyncStatus`, `getPairStatus`, `awaitPairSig` (Task 7). `OnCreate` fires denylist integrity check — `SecurityException` re-thrown to abort module init on tamper; other errors logged. `OnDestroy` cancels the shared `moduleScope` (SupervisorJob + Dispatchers.IO). All coroutine launches migrated from rootless `CoroutineScope(Dispatchers.IO).launch` to `moduleScope.launch`. `Events("onSyncStatus")` forwards `StateFlow<SyncState>` + queuedCount to JS.

**Relay sig push (Task 7):** New `PairHub` (subs map, identity-guarded `Unsubscribe(pairToken, ch)` prevents double-close panic on re-subscription race). New `GET /pair/notify?token=...` — unauthenticated (pre-pair), 404 on unknown token, 5-min timeout matching pair token TTL. `/pair/complete` success path pushes `{v,type:"pair.sig",pair_token,confirmation_sig}` via hub; non-blocking (drop on full). Mobile `PairNotifyClient` uses OkHttpClient with `readTimeout(0)` + `pingInterval(30s)` so the 5-min wait survives NAT timeouts. `handleWebSocket` (Phase 1) untouched — the new endpoint lives in its own `pair_notify.go` handler.

**Denylist integrity (Task 8):** `DenylistLoader.load(ctx)` reads `default-denylist.json` asset, computes SHA-256, compares to compiled `EXPECTED_SHA256_BYTES` via `MessageDigest.isEqual` (constant-time). SHA mismatch throws `SecurityException` with only expected hex (actual stripped to avoid crash-reporter leak). `@VisibleForTesting parseAndVerify(bytes)` exposed for unit tests. `.gitattributes` locks LF. 15 packages: Authy/Google/Microsoft/Duo authenticators, Chase/Amex/BoA/Wells/Discover/PayPal/Venmo banking, LastPass/1Password/Bitwarden/Dashlane password managers.

**UI (Tasks 9–12):** React Native port of the Tier-1 design bundle.
- `mobile/components/tokens.ts` — oklch→srgb via culori at theme build time (RN StyleSheet doesn't accept oklch). `hexWithAlpha(hex, opacity)` utility replaces web's `color-mix`/8-char-hex (fails silently on Android ≤27).
- `ThemeProvider` — hue + darkOverride persisted via AsyncStorage; `ready` gate prevents hydration flash. `useTheme()` / `useThemeControls()`.
- 15 primitives — `TwLogo`/`TwWordmark`/`TwStatusDot`/`TwDeviceChip`/`TwAppChip`+`TW_APPS` registry/`TwFingerprint`/`TwQR` (real `react-native-qrcode-svg` with mirror glyph overlay)/`TwButton`/`TwCard`/`TwSwitch`/`TwRow`/`TwBanner`/`TwEmpty`/`TwIcon`/`TwSpinner`.
- Fonts: `Inter` + `JetBrains Mono` via `@expo-google-fonts/*`.
- Onboarding (Task 10): `welcome`/`how`/`role`/`relay` (URL input + `pingRelay` test)/`perms` (NLS + POST_NOTIFICATIONS via `expo-notifications.requestPermissionsAsync` with app-settings fallback; Nearby/LAN deferred to Phase 4)/`oem` (generic battery/auto-start tips — manufacturer-specific copy deferred)/`ready`.
- Pairing (Task 11): `qr` (calls `startPairInitiator` + `awaitPairSig` in parallel — when push arrives, routes to fingerprint) / `scan` (`expo-camera` `CameraView` barcode mode; parses QR payload) / `fingerprint` (compute both via `computeFingerprint`; role A displays sig as selectable base64 code; role B pastes sig — **Phase 3 interim manual copy-paste**, labeled in UI) / `success` (calls `storePeerPubkeys` + `startSyncService`, marks onboarding complete) / `fail`.
- Main (Task 12): `home` (useSyncStatus; CONNECTED→relay/CONNECTING→pairing/OFFLINE_QUEUED→offline; mirror toggle wires start/stopSyncService; metrics = `—` placeholders; Recent = TwEmpty) / `settings/index` (Pairing/Sync/Privacy/About groups; Always-Connected toggle locked ON) / `settings/pair` (short UUID + TwFingerprint + Unpair Alert confirm → `unpair()` + `OnboardingState.reset()` → back to onboarding/role) / `filter` (hardcoded `TW_APPS` list; enforcement cosmetic in Phase 3 — banner states "Phase 4 adds enforcement").
- `.gitattributes` locks `default-denylist.json` to LF so SHA-256 is stable across OS checkouts.

**Smoke (Task 13):** 10 scenarios in `docs/test-scenarios.md` — fresh onboarding + pair, first mirror A→B, dismiss sync both directions, offline queue drain, denylist suppression, tampered APK aborts init, unpair+re-pair, dark mode, POST_NOTIFICATIONS deny fallback.

**Tests passing:** relay `go test ./...` green (includes new `pair_notify_test.go`, `validator_test.go` expanded to 20 cases). Mobile `tsc --noEmit` clean. Instrumented Android tests (Room DAO, DenylistLoader, JwtMinter, CryptoStore, Encrypter, Fingerprint, ReplayGuard, NotificationMapDao) documented but require emulator (MEMORY §8). JVM unit tests (ReasonCodeFilter 18 cases, CanonIdBuilder 4 cases, PendingPeerCancel 4 cases, EncryptedEnvelope 2 cases, OutboundQueue 4 cases) green.

**Deferred (documented known limitations):**
- Manual sig copy-paste during pairing (Phase 4: bidirectional sig push via relay WS).
- Lazy-FGS + IDLE_DEMOTED (Phase 9 Battery Tuning).
- LAN transport + NSD + TLS-pinned SPKI (Phase 4).
- FCM wake path (Phase 5).
- Reply bridge (Phase 6).
- Icon cache + hash-elide (Phase 7).
- MessagingStyle / BigTextStyle reconstruction (Phase 18).
- App filter user-override enforcement (Phase 4).
- Peer display names (Phase 4+).
- Home screen metrics (Today/Latency/Blocked) — counters not instrumented yet.
- Two-sided cryptographic confirmation (B's sig enforcement) — still UX-gated, not crypto-enforced.

### Phase 4 — Housekeeping + CI integrity (in progress on branch `phase-4-pair-auto-and-enforcement`)

Tasks 1–6 ship pair automation, app filter enforcement, unpair-notify-peer, peer display name, and home metrics instrumentation. Task 7 is a housekeeping sweep with the following 7 items (all addressed):

1. **A — ws.go comment updated**: Replaced stale `TODO(phase-2)` on `CheckOrigin: true` with a rationale block explaining JWT gate on `/ws` and pair_token gate on unauthenticated pair endpoints.
2. **B — jsonschema AssertFormat enabled**: `compiler.AssertFormat()` called in `NewValidator()`; `format:"uuid"` on `msg_id` is now enforced. Test `TestValidateEnvelope_BadUUIDFormat` added and passes.
3. **C — SyncService.currentWs double-assign removed**: `val ws = client.newWebSocket(...)` → `client.newWebSocket(...)`, post-`newWebSocket` `currentWs = ws` line dropped. Assignment inside `onOpen` is the single authoritative write.
4. **D — ReasonCodeFilterTest reason-code 5 coverage**: Two new cases added (`!ownPkg + !pending + reason 5 → NoEmit` and `ownPkg + pending + reason 5 → Suppress`).
5. **E — NonceSource.regenerate KDoc**: Documents the atomic coupling with `PeerStore.clear()` and `CryptoStore.rotate()` and the catastrophic nonce-reuse risk of mid-session regeneration.
6. **F — CI denylist integrity gate**: `denylist-integrity` job added to `.github/workflows/mobile.yml`; `shasum -a 256` on the asset vs. `EXPECTED_SHA256_HEX` constant in `DenylistLoader.kt`; fails CI if they diverge.
7. **G — MEMORY.md refreshed**: §10 Known gaps pruned of Phase 4-fixed items; §3 Phase 4 subsection added; §6 updated to Phase 5; §12 updated.

Commit TBD — branch merges to main after final review.

---

## 4 · Key design decisions (read the spec §§ for full context)

Spec: `docs/superpowers/specs/2026-04-20-phone-sync-design.md` (v10).

- **Crypto:** libsodium `crypto_box_easy` (authenticated). Keystore wraps the libsodium keys via AES-GCM — it does NOT hold them (Keystore can't expose raw X25519 bytes to libsodium). `crypto_box_seal` rejected as unauthenticated.
- **Nonce:** 16-random + 8-counter hybrid. Counter persisted, prevents backup-restore collisions.
- **Pairing:** two-sided in spirit — Device A signs `sig_A(token || A_enc || A_sign || B_enc || B_sign)`. **v1 only implements A's signature; B's consent is UX-gated (by choosing to call complete after seeing fingerprint), NOT cryptographically enforced at relay.** Phase 3 adds B's signature path + WebSocket-push of A's sig to B (currently out-of-band).
- **Fingerprint:** 5-field binding (A first, then B). Spec was originally 3-field; upgraded to 5-field for MITM resistance.
- **JWT:** Ed25519 signed; `jti` + `exp=60s`; relay keeps a `seenJti` cache with **2×TTL retention** to prevent a replay window between GC and exp expiry.
- **LAN TLS:** Spec calls for pinning peer TLS cert's SPKI to paired `enc_pubkey` (not yet implemented — Phase 4+).
- **mDNS:** rotate advertising ID daily via `HKDF(pair_secret, "ad-id", utc_epoch_day)`. Advertise ±1 day to tolerate clock skew.
- **Relay trust model:** E2EE, relay sees only ciphertext + metadata (device_id, timestamps, byte counts). Documented as known metadata leak.
- **Threat scope (documented out-of-scope):** rooted/OS-compromised devices, forward secrecy (no ratchet), malicious paired apps with NLS grant.
- **Loop avoidance (for when NLS lands in Phase 3):** self-package filter in `onNotificationPosted`, `localIdToCanonId` reverse map, `pendingPeerCancel` tombstones (30s TTL), reason-code filter (suppress REASON_LISTENER_CANCEL=10 only if canon_id is in pendingPeerCancel).
- **Android platform:** FGS type `remoteMessaging` (Android 14+), POST_NOTIFICATIONS runtime perm (API 33+), NEARBY_WIFI_DEVICES + ACCESS_LOCAL_NETWORK (Android 16 prep). Lazy-FGS design requires FCM high-priority exemption — without FCM (offline LAN), Dozed receiver can't re-promote FGS (documented limitation; user opts into "Always Connected" mode).
- **Privacy defaults:** mirrored notifications posted with `VISIBILITY_PRIVATE`; Android Auto categories excluded; OTP/banking apps default-denied (hash-verified JSON shipped in APK).
- **Transport:** LAN-first (NSD + TLS direct), FCM-wake + relay-WebSocket fallback, lazy FGS promotion. Phase 1 is relay-only; LAN and FCM ship in later phases.

---

## 5 · Naming / rebrand state

**Product name: Twinotify** (picked from shortlist where `.com`/`.app`/`.io`/`.dev`/npm/GitHub were all clean). Meaning: "twin" (paired devices) + "notify".

**User-facing rebrand done:**

- `docs/design/SCREEN_INVENTORY.md` title + product framing
- `docs/test-scenarios.md`
- `docs/superpowers/plans/*` running copy
- `docs/superpowers/specs/*` title + FGS notification string ("Twinotify active — connected via LAN")
- `relay/README.md` title
- `SCREEN_INVENTORY.md` includes wordmark/logo guidance for the designer

**Internal rebrand completed in Phase 3 Task 0** (commit `818af64`, merged 2026-04-21). All migrated atomically:

- Android package `com.twinotify.app` (was `com.phonesync.app`)
- Kotlin package `co.twinotify.core` (was `expo.modules.phonesynccore`)
- Expo module dir `mobile/modules/twinotify-core/` (was `phone-sync-core/`)
- Native module JS name `TwinotifyCore`, class `TwinotifyCoreModule`
- Go module `github.com/twinotify/relay`
- DataStore keys `twinotify_{crypto,peer,identity,nonce,replay}`
- Keystore alias `twinotify.master`
- Schema `$id` base `https://twinotify.app/schemas/` + validator `schemaBaseURL` (byte-for-byte match)
- Android deep-link scheme `twinotify://`
- mDNS service type `_twinotify._tcp` (comment-only until Phase 4)

**Repo directory** `Learn/phone-sync/` intentionally NOT renamed (historical). The filesystem path still reads "phone-sync" — cosmetic only. Design spec doc filename still `2026-04-20-phone-sync-design.md` (historical; content was rebranded earlier).

---

## 6 · What ships next (Phase 5)

**Phase 4** is in progress on branch `phase-4-pair-auto-and-enforcement` (Tasks 1–7 complete). After merge, Phase 5 opens:

1. **LAN transport (`LanTransport`)** — `NsdManager.registerService(_twinotify._tcp)` with daily-rotated ad-id (`HKDF(pair_secret, "ad-id", utc_epoch_day)`) ± 1 day window. TLS socket with SPKI pinning to paired `enc_pubkey` (spec §4.3). Transport selection order: LAN → relay.
2. **FCM wake path** — high-priority FCM message promotes FGS from Doze. Required for background delivery when LAN is unavailable and device is idle.
3. **mDNS ad-id rotation** — ±1 day window for clock-skew tolerance; rotate the advertising ID daily via HKDF.

Later phases: reply bridge (Phase 6), icon cache + hash-elide (Phase 7), desktop Tauri (Phase 8+), lazy FGS + battery tuning (Phase 9), MessagingStyle reconstruction (Phase 18).

---

## 7 · UI / Design handoff (complete)

- **Tier-1 design bundle received** and committed at `docs/design/assets/tier-1-design-bundle/` (2026-04-21): `tokens.jsx`, `primitives.jsx` (15 atoms), `screens.jsx` (18 screens), interactive HTML prototype, chat transcript.
- **Direction locked:** warm neutrals + mint-teal accent (oklch 0.62 0.14 180), Inter + JetBrains Mono. Mirror-motif monogram (two interlocked rings). Light + dark parity. Accent hue tweakable (mint/indigo/amber/rose).
- **Ported to React Native** in Phase 3 Tasks 9–12. `mobile/components/tokens.ts` + `ThemeProvider` + `mobile/components/primitives/*.tsx` + full screen set under `mobile/app/`.
- Tier 2/3 assets (app-filter deep views, self-notification screens, desktop frames) still open — design AI may produce more as later phases need them.
- `docs/design/SCREEN_INVENTORY.md` remains the reference list (~95 surfaces).

---

## 8 · Working conventions the user established

- **Subagent-driven development** — spec + plan reviewed first, then implementation via TDD with fresh subagent per task; spec-compliance reviewer + code-quality reviewer after each task; commits small + bisectable.
- **Phase-wise roadmap** — each phase has its own plan doc. Plan goes through review → APPROVED → executed → merged → next phase planned.
- **Review rigor** — specs went through 5+ rounds, plans through 4+ rounds with sub-agent re-review loops. Don't skip review just because it "looks fine".
- **Honest reporting** — when a subagent can't verify something (e.g., no emulator for instrumented tests), report DONE_WITH_CONCERNS, don't fake pass.
- **Commit hygiene** — `feat(scope): ...`, `fix(scope): ...`, `docs(scope): ...`, `chore(scope): ...`. Never amend shared commits. Three branches so far: `phase-1-relay-proto-scaffold`, `phase-2-crypto-pairing`, `phase-3-listener-first-mirror` — all merged to main.
- **User sometimes pastes external review feedback** (their Claude.ai web review). Treat it seriously — most findings have been real. Don't dismiss; verify each claim, upgrade spec if their reasoning is tighter.
- **User's writing style:** terse, often typos OK, moves fast. Auto mode for long executions is fine. When reaching UI/design questions, PAUSE and ask — they want to drive visual decisions.
- **Test coverage:** Kotlin module has NO runnable instrumented tests in this env (no Android SDK / emulator accessible). User runs manually. Unit tests that CAN run (Go, TS) must be green before moving on.
- **Environment quirks observed:**
  - `apk` network blocked inside Docker builds → inline `sync-proto` instead of `apk add make` in Dockerfile.
  - Harness blocks `rm -rf` of Expo template boilerplate → leave unreachable, note as inert.
  - Go version on dev machine is 1.26.2 (future version); pin go.mod directive to 1.22/1.23 to not require that exact toolchain.

---

## 9 · Open decisions / things the user needs to do

1. **Register domains + handles** (~$40–50/yr): `twinotify.com`, `.app`, `.io`, `github.com/twinotify`, `npmjs.com/~twinotify`, `@twinotify` on social platforms, Play Console dev account.
2. **Run the Phase 3 10-scenario manual smoke** on two physical Android phones (see `docs/test-scenarios.md` Phase 3 section). Expect to surface bugs that the no-emulator dev loop couldn't catch.
3. **Decide on Phase 4 scope confirmation** — bidirectional pair-sig automation + LAN transport + app-filter enforcement + unpair-notify + peer display names + home metrics. Plan to be written after smoke feedback.
4. **Optional: rename repo directory** `phone-sync` → `twinotify` (cosmetic; everything else has already moved).

---

## 10 · Known gaps / TODOs planted in code

- **Two-sided confirmation partial** — only Device A's sig enforced at the relay (spec §4.7 known gap). B's consent remains UX-gated. Cryptographic enforcement is Phase 4+.
- Reply `action_id` map is in-memory only (PendingIntents are process-local); OEM kill → reply fails with `reply.failed` (Phase 6 when reply bridge lands).
- LAN TLS SPKI pinning not yet implemented (Phase 5).
- mDNS ad-id rotation + ±1 day window not yet implemented (Phase 5).
- FCM-based wake path absent (Phase 5); Phase 3 is relay-WS-only.
- Icon hash-elide absent (Phase 7); every mirror inlines full PNG bytes base64.
- MessagingStyle / BigTextStyle reconstruction (Phase 18).

---

## 11 · Critical paths that future-you must NOT break

- **Schema `$id` = compiler resource URI = base `https://twinotify.app/schemas/`.** If you change the `$id` in `/proto/*.schema.json`, you MUST change `schemaBaseURL` constant in `relay/internal/server/validator.go` to match byte-for-byte. Mismatched → validator silently rejects every message.
- **Go tabs in Makefile.** Required. Lint warns; ignore.
- **libsodium JNA is snake_case on Android.** `sodium.crypto_box_easy`, not `cryptoBoxEasy`. `sodium.crypto_sign_detached`, not `cryptoSignDetached`. Compile first to confirm.
- **Ed25519 secret key is 64 bytes in libsodium** (seed + pubkey concat). Not 32. Guard with `require(signSecret.size == Sign.SECRETKEYBYTES)` wherever you sign.
- **`ed25519.PublicKey` vs `[]byte`.** Relay-side verification needs `ed25519.PublicKey(pairStore.SignPubkeyFor(sub))` cast. Failing cast → verify returns false silently.
- **Nonce counter is monotonic** — do NOT reset in normal operation. Only reset on `unpair()` or explicit `regenerate()`. Counter reset mid-session + same random prefix = nonce reuse = catastrophic.
- **JTI cache retention = 2×TTL.** 1×TTL creates a replay window between GC and JWT exp. Do not "optimize" this back to 1×.
- **Dockerfile build context = repo root** (not `relay/`). Compose uses `context: ..` + `dockerfile: relay/Dockerfile`. Changing this without updating the inline sync-proto `COPY proto/` line will break builds.
- **Phase 1's WS safety limits (SetReadLimit, SetPongHandler, sync.Mutex writes, ping goroutine) were clobbered once during Phase 2 Task 7.** Surgical edits only. Any "rewrite ws.go" must preserve all of Phase 1's safety infrastructure. Phase 3 Task 7 added `/pair/notify` in a separate `pair_notify.go` file; don't merge them.
- **Room schema is at version 2.** Adding a new entity → bump version 3 + add Migration(2,3). Never use `fallbackToDestructiveMigration()` — will wipe paired state.
- **`.gitattributes` locks `default-denylist.json` to LF.** Don't remove that entry. Cross-OS LF→CRLF would break the SHA-256 integrity gate on every Windows contributor's build.
- **`PendingPeerCancel.add` MUST happen BEFORE `NotificationManager.cancel` in `MirrorDismisser`.** Reversed order → listener's `onNotificationRemoved` fires + misses tombstone + emits spurious `notif.cancel` to origin. Already got this right; don't let future cleanups swap the two statements.
- **`OutboundQueue.enqueue` goes through `enqueueCapped(@Transaction)`.** Don't expose the raw `insertRaw` or bypass the cap check — the `@Transaction` is the atomic guard against concurrent enqueue races.
- **`SyncService.flushQueue` holds a `Mutex`.** Any parallel drain-and-ack path added later must acquire the same `flushMutex` or messages will be sent twice.

---

## 12 · Where to pick up

If starting a new session, read in this order:

1. This file (MEMORY.md).
2. `docs/superpowers/specs/2026-04-20-phone-sync-design.md` (spec v10).
3. `git log --oneline main | head -30` (recent history).
4. `docs/superpowers/plans/2026-04-21-phase-3-listener-first-mirror.md` (executed; reference for patterns).
5. `docs/test-scenarios.md` Phase 3 + Phase 4 sections.

**Current state (2026-04-21):** Phase 4 is in progress on branch `phase-4-pair-auto-and-enforcement`. Tasks 1–7 are complete. Branch is ready for final review + merge.

**Immediate next step (when user returns):**

- Merge Phase 4 branch to main, update §3 with final SHA.
- Write Phase 5 plan (`docs/superpowers/plans/2026-04-<DATE>-phase-5-lan-fcm.md`) covering LAN transport + FCM wake path (see §6).

User will say "go" / "proceed" / "subagent" to begin execution.
