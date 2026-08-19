# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository workspace rule

- Work directly in this repository's primary checkout by default.
- Do not create, attach, switch to, or work inside a Git worktree unless the user explicitly requests a worktree in the current conversation.
- A task plan, skill, branch-isolation recommendation, or existing `.worktrees/` directory is not permission to use a worktree.
- If work is already underway in a worktree without explicit current user authorization, stop there and continue only after asking the user how to proceed.

## What this is

**Twinotify** — end-to-end-encrypted Android ↔ Android notification mirroring. A notification on one phone appears on the other; dismissing either side dismisses both. The relay is untrusted: it sees ciphertext plus routing metadata only.

Three cooperating parts:

- `relay/` — Go 1.23 WebSocket + HTTP relay (chi, gorilla/websocket, bbolt). Pairing broker, JWT gate, durable per-recipient mailbox.
- `mobile/` — Expo SDK 54 / RN app. All device logic lives in `mobile/modules/twinotify-core/`, a custom Expo native module written in Kotlin; the TS layer is screens + a thin bridge.
- `proto/` — JSON Schema 2020-12 packet contracts. **Single source of truth** for both sides.

## Commands

### Relay (Go)

```bash
make sync-proto        # copy proto/*.schema.json into relay/internal/server/schemas/ (generated, gitignored)
make relay-test        # sync-proto + go test ./... -race -count=1   (~90s)
make relay-build       # → bin/relay
make deployment-test   # deploy/assert-compose.sh: dev+prod compose invariants (needs docker)
```

Anything that compiles the relay needs `make sync-proto` first — the schema dir is `go:embed`-ed and not committed, so a fresh clone fails to build without it.

Single test / package:

```bash
cd relay && go test ./internal/server -run TestProtocolFixtures -race -count=1
cd relay && go test ./internal/store -race -count=1
```

Run from `relay/`, not the repo root — the Go module lives there.

`make verify` / `make proto-test` / `make relay-verify` are specified by the design doc but **not implemented yet** (protocol-relay plan Task 9). Use `make relay-test` today.

### Mobile

```bash
cd mobile
npm ci
npm run typecheck      # tsc --noEmit — what CI gates on
npm run lint
npx expo-doctor        # also gated in CI
npm run prebuild       # expo prebuild --clean; generates mobile/android + mobile/ios (both gitignored)
npm run android        # expo run:android — needs a dev build + device/emulator
npm run build:dev      # pinned ephemeral EAS CLI, development profile, local APK
```

`mobile/android/` and `mobile/ios/` do not exist in a fresh clone; they are prebuild output. Kotlin JVM tests (`modules/twinotify-core/android/src/test/`) and instrumented tests (`.../androidTest/`) only run after a prebuild produces a Gradle project. The mobile CI prebuilds Android, then runs Kotlin lint, JVM tests, and `assembleDebug`; instrumented tests still require an API-compatible emulator or physical device.

### Relay in Docker

```bash
cd deploy && docker compose up -d relay && curl -sf http://localhost:8080/health
TWINOTIFY_DOMAIN=relay.example.com docker compose -f docker-compose.prod.yml up -d   # Caddy TLS front, relay unexposed
```

Docker build context is the **repo root** with `dockerfile: relay/Dockerfile` (the image inlines the proto copy instead of running make). Changing the context breaks the build.

## Architecture

### Protocol: v1 and v2 coexist

`proto/` holds both generations. **v1** (`packet.schema.json`, `envelope-encrypted.schema.json` with `v:1`) is online-only passthrough: the relay forwards to a live peer or fails. **v2** (`inner-event-v2.schema.json`, `peer-receipt.schema.json`, `relay-control.schema.json`) adds an authenticated inner packet (`msg_id`, `canon_id`, `sequence`, `expires_at` inside the ciphertext), a durable relay mailbox, and end-to-end peer receipts.

Current state: **the relay and Android both implement v1 + v2**. Android advertises `[2,1]` with `relay.hello`, sends durable v2 envelopes with `relay.put`, authenticates inner events, stores inbound/outbound state in Room version 4, materializes desired notification/call state, and emits peer receipts. The deprecated DataStore replay guard and legacy outbound queue remain only for v1 compatibility/migration; do not route new reliable-delivery work through them.

Frames (`relay/internal/server/relay_frame.go`): in — `relay.hello`, `relay.put`, `relay.ack`; out — `relay.accepted`, `relay.deliver`, `relay.rejected`, `relay.expired`, `relay.capabilities`, `relay.legacy_forwarded`. Once both devices advertise `[2,1]` the relay records a protocol floor of 2 per pair and refuses v1 frames for it, so a downgrade cannot be forced.

### Relay internals

`cmd/relay/main.go` opens Bolt, builds `server.NewWithConfig(bolt, Config)`, starts a maintenance ticker (pending-pair sweep, mailbox/status expiry, JTI GC) and an HTTP server with explicit timeouts. Routes live in one place — `server.routes()` in `internal/server/server.go`.

- `internal/store/pair_store.go` — pending + confirmed pairs, per-device capability records, protocol floor, revocation.
- `internal/store/mailbox_store.go` — the durable mailbox. Every operation has a `…ForPair` variant and authorizes `(pairID, deviceID)` inside the same Bolt transaction as the mutation. Pair-scoping is the mechanism that stops a revoked or rebound pair generation from reading or acking another generation's data; do not add an unscoped path.
- `internal/server/client_hub.go` — live sockets. Connection replacement cancels the old registration's context rather than closing a producer-visible channel, so a producer can never send on a closed channel.
- `internal/server/durable_handoff.go` + `transferHandoffFrames` — moves mailbox records from durable state into a socket's writer queue at one linearization point.

Mailbox invariants worth knowing before touching that code: persistence commits **before** `relay.accepted`; delivery is a read from Bolt, not a transfer of ownership; `relay.ack` must carry the exact `envelope_sha256`; a duplicate `(recipient, msg_id)` with a matching digest is idempotent and replays the original `accepted_at`, while a mismatched digest is rejected as `id_conflict`; retention is 24h from acceptance with a metadata-only expiry tombstone kept another 24h; per-recipient caps (2,000 items / 128 MiB) return explicit backpressure and never evict accepted ciphertext.

`Server` carries nil-in-production function fields (`relayHelloBeforeActivate`, `webSocketBeforeRegister`, `revokeAfterCommit`, …). These are deterministic test seams for the concurrency tests — keep them nil-checked and keep using them instead of sleeps.

### Auth and pairing

`/ws` and `/pair/revoke` sit behind `authMiddleware`: Ed25519 (`EdDSA`) JWT, `sub` = device ID, parsed unverified to select the stored `sign_pubkey`, then verified against it. `jti` is single-use via `JTICache` with **2×TTL retention** — 1×TTL leaves a replay window between GC and `exp`. The middleware puts `device_id` + `pair_id` in the request context; handlers read both and pass them into pair-scoped store calls.

Unauthenticated pairing endpoints are IP-rate-limited and body-size-bounded (`http_limits.go`): `POST /pair/init` (A) → `POST /pair/hello` (B) → `POST /pair/send_sig` (A) → `POST /pair/complete` (B), with `GET /pair/notify` letting a waiting device pick up state that landed before it subscribed (resumable and idempotent — replayed from persisted pending state, not memory). Pair tokens expire after 5 minutes.

Two-sided confirmation is only partly cryptographic: A's signature is enforced at the relay, B's consent is UX-gated by the fingerprint screen. That is a known, documented gap.

### Mobile

Expo Router file routes under `mobile/app/` (`onboarding/`, `pair/`, `settings/`, `home.tsx`, `filter.tsx`); `app/index.tsx` routes to onboarding or home off AsyncStorage flags in `state/onboardingState.ts`. Design system is `components/tokens.ts` (oklch computed to hex via culori at module load), `components/Theme.tsx`, and `components/primitives/Tw*.tsx` — build screens from those primitives rather than raw styled Views.

Native surface: `TwinotifyCoreModule.kt` exposes ~30 `AsyncFunction`s (identity, keys, pairing handshake, encrypt/decrypt, service start/stop, status, denylist, metrics), typed in `modules/twinotify-core/src/`, wrapped by `hooks/useTwinotifyCore.ts`. JS never touches crypto or the WebSocket directly.

Kotlin flow: `TwinotifyNotificationListener` captures → filters → `OutboundQueue` (Room) → `SyncService` (foreground service, type `remoteMessaging`) → relay WS. Inbound: `InboundDispatcher` → `MirrorPoster` / `MirrorDismisser`. Crypto is libsodium `crypto_box_easy`, with Android Keystore wrapping the libsodium keys via AES-GCM (Keystore cannot hold X25519 for libsodium directly).

## Invariants that break things silently

- **Schema `$id` prefix == `schemaBaseURL`** in `relay/internal/server/validator.go` (`https://twinotify.app/schemas/`), byte-for-byte. A mismatch makes the validator reject every message with no obvious cause.
- **`relay/internal/server/schemas/` is generated.** Never edit or commit it; edit `proto/` and re-run `make sync-proto`.
- **Mirror-dismiss ordering:** `PendingPeerCancel.add` must run **before** `NotificationManager.cancel` in `MirrorDismisser`. Reversed, the listener's `onNotificationRemoved` misses the tombstone and emits a spurious `notif.cancel` back to the origin — an echo loop.
- **`OutboundQueue.enqueue` goes through `enqueueCapped(@Transaction)`.** Bypassing it loses the atomic cap check.
- **`SyncService.flushQueue` holds `flushMutex`.** Any new drain path must take the same mutex or messages send twice.
- **Room is at version 2.** New entity → version 3 + an explicit `Migration(2,3)`. Never `fallbackToDestructiveMigration()`; it wipes paired state.
- **Nonce counter is monotonic.** Reset only on `unpair()`/`regenerate()`. Reset + same random prefix = nonce reuse.
- **libsodium JNA on Android is snake_case** (`sodium.crypto_box_easy`, `crypto_sign_detached`), and the Ed25519 secret key is **64 bytes** (seed‖pubkey), not 32.
- **`ws.go` safety scaffolding** (`SetReadLimit`, pong handler + read deadlines, write mutex, ping goroutine) was clobbered once by a rewrite. Make surgical edits; don't regenerate the file.
- **`default-denylist.json` is LF-locked in `.gitattributes`** and its SHA-256 is a constant (`EXPECTED_SHA256_HEX`) in `DenylistLoader.kt`, gated by CI. Change the asset and the constant in the same commit.
- **Production compose must not publish the relay port** — `deploy/assert-compose.sh` fails the build if it does, and also rejects `tls internal` in the prod Caddyfile.
- **Makefile needs real tabs.**

## Docs

- `docs/superpowers/specs/2026-04-20-phone-sync-design.md` — overall system/crypto/threat model (v10).
- `docs/superpowers/specs/2026-08-09-reliable-delivery-foundation-design.md` — the v2 protocol, data models, ordering, verification strategy, and release gate. Read this before touching mailbox, receipt, or sequencing code.
- `docs/superpowers/plans/2026-08-09-reliable-delivery-{protocol-relay,android,verification}.md` — task-by-task plans, executed in that order. The relay plan is complete through Task 8; Task 9 (one-command verification) and the whole Android plan are open.
- `MEMORY.md` — long-form session handoff, but **last updated 2026-04-21**: it predates the reliable-delivery work and describes Phase 4 as in progress. Trust `git log` and the code over it.
- `docs/test-scenarios.md` — manual two-phone smoke scenarios. `docs/design/SCREEN_INVENTORY.md` — UI surface reference.

## Working conventions

- **Plan, then execute.** Work lands as numbered tasks from a plan doc in `docs/superpowers/plans/`, reviewed before implementation. `.superpowers/sdd/` (gitignored) holds per-task briefs, reports, and review diffs from that workflow.
- **TDD, failing test observed first.** Go changes run with `-race` before every commit.
- **Conventional commits with a scope:** `feat(relay):`, `fix(mobile/pair):`, `test(relay):`, `docs:`, `chore:`. Small and bisectable.
- **Report honestly when something cannot be verified** here (Kotlin compilation, instrumented tests, physical-device behaviour) rather than implying a pass.
- Pause and ask on UI/visual decisions — those are the user's to drive.

---

Foreign agent configs were detected at `~/.codex` and `~/.gemini`. Reply `/import` to scan and list what's importable (MCP servers, slash commands, subagents, skills, instructions), then `/import --yes=<digest>` to apply the user-level items.
