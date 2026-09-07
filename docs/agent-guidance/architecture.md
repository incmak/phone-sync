# Architecture reference

Paths below are relative to the repository root. Read the sections relevant to protocol, relay, pairing, or native-mobile changes.

### Protocol: v1 and v2 coexist

`proto/` holds both generations. **v1** (`packet.schema.json`, `envelope-encrypted.schema.json` with `v:1`) is online-only passthrough: the relay forwards to a live peer or fails. **v2** (`inner-event-v2.schema.json`, `peer-receipt.schema.json`, `relay-control.schema.json`) adds an authenticated inner packet (`msg_id`, `canon_id`, `sequence`, `expires_at` inside the ciphertext), a durable relay mailbox, and end-to-end peer receipts.

Current state: **the relay and Android both implement v1 + v2**. Android advertises `[2,1]` with `relay.hello`, sends durable v2 envelopes with `relay.put`, authenticates inner events, stores inbound/outbound state in Room version 12 under a route-neutral custody column (`custodyAcceptedAt`/`custodyRoute`), materializes desired notification/call state, and emits peer receipts. The deprecated DataStore replay guard and legacy outbound queue remain only for v1 compatibility/migration; do not route new reliable-delivery work through them.

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

The relay verifies A's confirmation signature and B's domain-separated responder confirmation signature. B's signature binds the same pairing transcript plus A's signature. Signed notify requests bind token, role, and device ID; fingerprint confirmation remains a client UX requirement.

### Mobile

Expo Router file routes under `mobile/app/` (`onboarding/`, `pair/`, `settings/`, `home.tsx`, `filter.tsx`); `app/index.tsx` routes to onboarding or home off AsyncStorage flags in `state/onboardingState.ts`. Design system is `components/tokens.ts` (oklch computed to hex via culori at module load), `components/Theme.tsx`, and `components/primitives/Tw*.tsx` — build screens from those primitives rather than raw styled Views.

Native surface: `TwinotifyCoreModule.kt` exposes ~30 `AsyncFunction`s (identity, keys, pairing handshake, encrypt/decrypt, service start/stop, status, denylist, metrics), typed in `modules/twinotify-core/src/`, wrapped by `hooks/useTwinotifyCore.ts`. JS never touches crypto or the WebSocket directly.

Kotlin flow: `TwinotifyNotificationListener` captures → filters → `OutboundQueue` (Room) → `SyncService` (foreground service, type `remoteMessaging`) → relay WS. Inbound: `InboundDispatcher` → `MirrorPoster` / `MirrorDismisser`. Crypto is libsodium `crypto_box_easy`, with Android Keystore wrapping the libsodium keys via AES-GCM (Keystore cannot hold X25519 for libsodium directly).

### macOS and multi-peer implementation

Plan 031 (`advisor-plans/031-macos-receiver-three-device.md`) tracks the native macOS receiver and two-active-links-per-device migration. The implementation supports two peer links per device; rollout remains gated by the three-device acceptance matrix. Room version 12 includes the explicit 11→12 migration and committed schema 12.json. Public peer records, route settings, reliable work and removal lifecycles are link-scoped; the local identity, nonce allocator and source sequence remain installation-wide.

Current relay purge scans recipient prefixes and filters each mailbox item by sender/recipient. Device-wide sequence and capability deletion is the immediate multi-peer isolation problem; it must become pair-generation scoped before enabling a second link.
