# Twinotify — Session Memory

> Self-contained handoff for a fresh Claude session. Load this, then work the project. Updated: 2026-04-21.

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
├── mobile/                       Expo SDK 52+ RN app
│   ├── app/                      Expo Router screens (index.tsx currently = ping test)
│   ├── modules/phone-sync-core/  custom Expo Native Module (Kotlin)
│   │   └── android/src/main/java/expo/modules/phonesynccore/
│   │       ├── PhoneSyncCoreModule.kt  # 12 AsyncFunctions exposed to JS
│   │       ├── crypto/                 # KeystoreMaster, WrappedKeys, CryptoStore, NonceSource, Encrypter
│   │       ├── auth/JwtMinter.kt
│   │       ├── pairing/                # Fingerprint, PairPayload, PairProtocol
│   │       └── storage/                # DeviceIdentity, PeerStore, ReplayGuard
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
    │       ├── 2026-04-20-phase-1-relay-proto-scaffold.md   (executed, merged)
    │       └── 2026-04-21-phase-2-crypto-pairing.md         (executed, merged)
    └── test-scenarios.md         Phase 1 + Phase 2 manual smoke procedures
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

**Internal identifiers NOT yet migrated** (scheduled as Phase 3 Task 0 — coordinated rename to avoid breaking in-flight state):

- Android package: `com.phonesync.app` → target `com.twinotify.app`
- Kotlin package: `expo.modules.phonesynccore` → target `co.twinotify.core`
- Expo module dir: `mobile/modules/phone-sync-core/` → target `mobile/modules/twinotify-core/`
- Expo module package name: `"phone-sync-core"` → `"twinotify-core"`
- Native module name (JS-side `requireNativeModule`): `"PhoneSyncCore"` → `"TwinotifyCore"`
- Go module: `github.com/phonesync/relay` → target `github.com/twinotify/relay`
- DataStore namespaces: `phonesync_crypto`, `phonesync_peer`, `phonesync_identity`, `phonesync_nonce`, `phonesync_replay` → `twinotify_*`
- Keystore alias: `phonesync.master` → `twinotify.master`
- mDNS service type: `_phonesync._tcp` → `_twinotify._tcp`
- Schema `$id` base: `https://phone-sync.local/schemas/` → `https://twinotify.app/schemas/` (matches canonical domain)
- Project directory: `Learn/phone-sync/` → optionally renamed (user decision)

**Rationale:** renaming these simultaneously requires file moves + Gradle/Manifest updates + schema URL changes (invalidates embedded validators) + DataStore wipes (users would lose paired state). Done as one atomic Phase 3 Task 0 commit BEFORE Phase 3 backend work begins.

---

## 6 · What ships next (Phase 3 plan to be written)

**Phase 3 — NotificationListenerService + first mirror (Android)**

Required: internal rebrand first (Task 0). Then:

1. Manifest entries: `BIND_NOTIFICATION_LISTENER_SERVICE`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_REMOTE_MESSAGING`, `NEARBY_WIFI_DEVICES`, `ACCESS_LOCAL_NETWORK`, `RECEIVE_BOOT_COMPLETED`.
2. `PhoneSyncNotificationListener : NotificationListenerService` — with spec §4.1 callbacks (self-package filter, reason-code truth table, `mirroredFromPeer` + `localIdToCanonId` + `pendingPeerCancel` maps). `mirroredFromPeer` and `localIdToCanonId` persisted to Room (process-death recovery). `pendingPeerCancel` in-memory (30s tombstone TTL).
3. `SyncService : Service` (foreground type `remoteMessaging`) — lazy FGS by default, always-connected as user opt-in.
4. Single mirror channel `mirrored_notifications` with `IMPORTANCE_DEFAULT` and `VISIBILITY_PRIVATE`. Phase 1 mirror content: title + text + small icon + large icon. Phase 2 adds MessagingStyle reconstruction.
5. Dismissal sync + loop avoidance (see spec §4.1 + §6 flow).
6. Integrate with existing Phase 2 crypto: notification payloads go through `encryptToPeer`/`decryptFromPeer`; all WS traffic uses the authed JWT.
7. App allowlist/denylist UI stub (full UI ships in Phase 4 with design assets). Default denylist in `mobile/assets/default-denylist.json` with SHA-256 integrity check at startup.
8. Reliability onboarding (OEM battery detection + deep links — per §7 error handling).
9. Device A → Device B confirmation_sig transport via relay WebSocket push (closes the out-of-band gap).

Out of scope for Phase 3: LAN transport (Phase 4), FCM (Phase 5), reply bridge (Phase 6), icon cache (Phase 7), desktop (Phase 8+), battery tuning (Phase 9).

---

## 7 · UI / Design handoff

- **`docs/design/SCREEN_INVENTORY.md`** — exhaustive list of ~95 surfaces across onboarding, pairing, home/status, mirror rendering, history, app filter, settings, error/recovery, self-notifications, desktop tray/windows/toasts, empty states, global primitives.
- Tiered for implementation priority (Tier 1 = needed for Phase 3 functional UI; Tier 2 = Phase 4 polish; Tier 3 = Phase 5+ desktop).
- User is handing this to a design AI. Assets expected back: design tokens JSON, primitives rendered, hero screen, Tier 1 frames, component names in camelCase, app + notification icons as SVG + Android densities.
- Place returned assets in `docs/design/assets/`.
- **Engineering tasks DO NOT wait on design** — Phase 3 backend (NLS + first mirror driven by the headless module methods) proceeds in parallel. UI wiring happens once assets arrive.

---

## 8 · Working conventions the user established

- **Subagent-driven development** — spec + plan reviewed first, then implementation via TDD with fresh subagent per task; spec-compliance reviewer + code-quality reviewer after each task; commits small + bisectable.
- **Phase-wise roadmap** — each phase has its own plan doc. Plan goes through review → APPROVED → executed → merged → next phase planned.
- **Review rigor** — specs went through 5+ rounds, plans through 4+ rounds with sub-agent re-review loops. Don't skip review just because it "looks fine".
- **Honest reporting** — when a subagent can't verify something (e.g., no emulator for instrumented tests), report DONE_WITH_CONCERNS, don't fake pass.
- **Commit hygiene** — `feat(scope): ...`, `fix(scope): ...`, `docs(scope): ...`, `chore(scope): ...`. Never amend shared commits. Two branches so far: `phase-1-relay-proto-scaffold`, `phase-2-crypto-pairing` — both merged to main.
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
2. **Hand `docs/design/SCREEN_INVENTORY.md` to the design AI.** Expect Tier 1 assets back.
3. **Drop design assets in `docs/design/assets/`** when received.
4. **Approve Phase 3 plan** once written.
5. **Test Phase 2 manually on 2 devices** — headless smoke from `docs/test-scenarios.md`.

---

## 10 · Known gaps / TODOs planted in code

- `TODO(phase-2)` in `relay/internal/server/ws.go` — `CheckOrigin: true`; restrict when paired auth tightens.
- `TODO(phase-2)` in Kotlin `ping()` — `origin_device` was hardcoded `"mobile"`; now replaced with `DeviceIdentity.getOrCreate()`. Legacy TODO may still be in comments — verify.
- `TODO(phase-2): enable jsonschema.WithFormatAssert` in `relay/internal/server/validator.go` — format "uuid" is advisory only.
- Two-sided confirmation partial — only Device A's sig enforced (spec §4.7 known gap).
- Confirmation_sig transport is out-of-band (user copies between devices manually) — Phase 3 adds WebSocket push.
- Reply `action_id` map is in-memory only (PendingIntents are process-local); OEM kill → reply fails with `reply.failed`. Documented.
- LAN TLS SPKI pinning not yet implemented (Phase 4+).
- mDNS ad-id rotation + ±1 day window not yet implemented (Phase 4+).
- Denylist SHA-256 integrity check not yet implemented (Phase 3 UI task).
- jsonschema v5 → v6 migrated in relay; `bytes` import anchor (`var _ = bytes.NewReader`) in `validator.go` is a stylistic leftover that can be removed when touching the file next.

---

## 11 · Critical paths that future-you must NOT break

- **Schema `$id` = compiler resource URI = base `https://phone-sync.local/schemas/`.** If you change the $id in `/proto/*.schema.json`, you MUST change `schemaBaseURL` constant in `relay/internal/server/validator.go` to match byte-for-byte. Mismatched → validator silently rejects every message.
- **Go tabs in Makefile.** Required. Lint warns; ignore.
- **libsodium JNA is snake_case on Android.** `sodium.crypto_box_easy`, not `cryptoBoxEasy`. `sodium.crypto_sign_detached`, not `cryptoSignDetached`. Compile first to confirm.
- **Ed25519 secret key is 64 bytes in libsodium** (seed + pubkey concat). Not 32. Guard with `require(signSecret.size == Sign.SECRETKEYBYTES)` wherever you sign.
- **`ed25519.PublicKey` vs `[]byte`.** Relay-side verification needs `ed25519.PublicKey(pairStore.SignPubkeyFor(sub))` cast. Failing cast → verify returns false silently.
- **Nonce counter is monotonic** — do NOT reset in normal operation. Only reset on `unpair()` or explicit `regenerate()`. Counter reset mid-session + same random prefix = nonce reuse = catastrophic.
- **JTI cache retention = 2×TTL.** 1×TTL creates a replay window between GC and JWT exp. Do not "optimize" this back to 1×.
- **Dockerfile build context = repo root** (not `relay/`). Compose uses `context: ..` + `dockerfile: relay/Dockerfile`. Changing this without updating the inline sync-proto `COPY proto/` line will break builds.
- **Phase 1's WS safety limits (SetReadLimit, SetPongHandler, sync.Mutex writes, ping goroutine) were clobbered once during Task 7.** Surgical edits only. Any "rewrite ws.go" must preserve all of Phase 1's safety infrastructure.

---

## 12 · Where to pick up

If starting a new session, read in this order:

1. This file (MEMORY.md).
2. `docs/superpowers/specs/2026-04-20-phone-sync-design.md` (spec v10).
3. `git log --oneline main | head -30` (recent history).
4. `docs/design/SCREEN_INVENTORY.md` (UI scope).

Then: write Phase 3 plan (`docs/superpowers/plans/2026-04-<DATE>-phase-3-listener-first-mirror.md`), including a **Task 0: Internal rebrand to Twinotify** as the first step.

User will say "go" / "proceed" / "subagent" to begin execution.
