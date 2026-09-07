# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Repository workspace rule

- Work directly in this repository's primary checkout by default.
- Do not create, attach, switch to, or work inside a Git worktree unless the user explicitly requests a worktree in the current conversation.
- A task plan, skill, branch-isolation recommendation, or existing `.worktrees/` directory is not permission to use a worktree.
- If work is already underway in a worktree without explicit current user authorization, stop there and continue only after asking the user how to proceed.

## What this is

**Twinotify** — end-to-end-encrypted Android ↔ Android notification mirroring. A notification on one phone appears on the other; dismissing either side dismisses both. The relay is untrusted: it sees ciphertext plus routing metadata only.

Three cooperating parts:

- `relay/` — Go 1.23 WebSocket + HTTP relay (chi, gorilla/websocket, bbolt). Pairing broker, JWT gate, durable per-recipient mailbox.
- `mobile/` — Expo SDK 57 / React Native 0.86 app. All device logic lives in `mobile/modules/twinotify-core/`, a custom Expo native module written in Kotlin; the TS layer is screens + a thin bridge.
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

`make verify` (proto + relay + mobile) is the one-command gate; `make host-verify` covers the host/E2E scripts without a device. Note `mobile-verify` runs `npm ci` and `expo prebuild --clean`, and `relay-verify` builds the Docker image, so both are slow and need network/docker. For a quick loop use `make relay-test` plus the module's own Gradle tasks.

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

## Architecture boundaries

- The relay is untrusted; tenant/pair authorization occurs inside the same transaction as mailbox mutation.
- Persist before `relay.accepted`; ack requires the exact envelope digest. Accepted ciphertext is never evicted to make room.
- Preserve the negotiated v2 protocol floor, pair-scoped store calls, JWT signature verification, and 2×TTL JTI retention.
- JS uses the native bridge for crypto and transport. Build mobile screens from the existing tokens and Tw primitives.

For protocol, mailbox, pairing, or native-mobile changes, read the relevant section of [the architecture reference](docs/agent-guidance/architecture.md). It preserves packet semantics, retention/cap limits, auth details, and native ownership.

## Invariants that break things silently

- **Schema `$id` prefix == `schemaBaseURL`** in `relay/internal/server/validator.go` (`https://twinotify.app/schemas/`), byte-for-byte. A mismatch makes the validator reject every message with no obvious cause.
- **`relay/internal/server/schemas/` is generated.** Never edit or commit it; edit `proto/` and re-run `make sync-proto`.
- **Mirror-dismiss ordering:** `PendingPeerCancel.add` must run **before** `NotificationManager.cancel` in `MirrorDismisser`. Reversed, the listener's `onNotificationRemoved` misses the tombstone and emits a spurious `notif.cancel` back to the origin — an echo loop.
- **`OutboundQueue.enqueue` goes through `enqueueCapped(@Transaction)`.** Bypassing it loses the atomic cap check.
- **`TransportCoordinator` grants the single outbox-drainer lease.** Exactly one granted route session owns `OutboxRepository.sendable` at a time: non-self-draining sessions use `TransportCoordinator.pump`, while relay sessions self-drain through `RelayTransport.flushV2`. Any new drain path must belong to the coordinator-granted session or messages can send twice. (`SyncService.flushQueue`/`flushMutex` no longer exist.)
- **Room is at version 12.** New entity → version 13 + an explicit `Migration(12,13)` registered in `NotificationDb.addMigrations(...)`, plus a committed `schemas/.../13.json`. Never `fallbackToDestructiveMigration()`; it wipes paired state.
- **Nonce counter is monotonic.** Reset only on `unpair()`/`regenerate()`. Reset + same random prefix = nonce reuse.
- **libsodium JNA on Android is snake_case** (`sodium.crypto_box_easy`, `crypto_sign_detached`), and the Ed25519 secret key is **64 bytes** (seed‖pubkey), not 32.
- **`ws.go` safety scaffolding** (`SetReadLimit`, pong handler + read deadlines, write mutex, ping goroutine) was clobbered once by a rewrite. Make surgical edits; don't regenerate the file.
- **`default-denylist.json` is LF-locked in `.gitattributes`** and its SHA-256 is a constant (`EXPECTED_SHA256_HEX`) in `DenylistLoader.kt`, gated by CI. Change the asset and the constant in the same commit.
- **Production compose must not publish the relay port** — `deploy/assert-compose.sh` fails the build if it does, and also rejects `tls internal` in the prod Caddyfile.
- **Makefile needs real tabs.**

## Docs

- `docs/superpowers/specs/2026-04-20-phone-sync-design.md` — overall system/crypto/threat model (v10).
- `docs/superpowers/specs/2026-08-09-reliable-delivery-foundation-design.md` — the v2 protocol, data models, ordering, verification strategy, and release gate. Read this before touching mailbox, receipt, or sequencing code.
- [Delivery status and plan history](docs/agent-guidance/delivery-status.md) — consult when resuming planned or release work. Local APKs are QA artifacts, not protected release candidates.
- `MEMORY.md` — long-form session handoff, but **last updated 2026-04-21**: it predates the reliable-delivery work and describes Phase 4 as in progress. Trust `git log` and the code over it.
- `docs/test-scenarios.md` — manual two-phone smoke scenarios. `docs/design/SCREEN_INVENTORY.md` — UI surface reference.

## Working conventions

- Establish the requested outcome, then implement and verify routine scoped work. Review new protocol, crypto, schema, or substantial product decisions before implementation unless the user has already approved them. Use numbered plans for work that benefits from them; do not require a plan-review pause for an authorized repair.
- Use a failing regression test when it clarifies changed behavior. Documentation/configuration edits need relevant validation, not ceremonial TDD. Go code changes run with `-race` before every commit.
- **Conventional commits with a scope:** `feat(relay):`, `fix(mobile/pair):`, `test(relay):`, `docs:`, `chore:`. Small and bisectable.
- **Report honestly when something cannot be verified** here (Kotlin compilation, instrumented tests, physical-device behaviour) rather than implying a pass.
- Preserve the user's visual direction. Proceed with the visible correction needed for an explicitly requested UI repair; ask when an unresolved product/design choice would materially change the result.
