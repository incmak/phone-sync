# Phone-Sync Relay (Phase 1)

Go WebSocket relay that echoes validated-envelope messages. Used during development and as the wake-path relay for the mobile app.

## Run locally

    make sync-proto
    cd relay && go run ./cmd/relay   # listens on :8080
    curl -sf http://localhost:8080/health

## Run in Docker

    cd deploy && docker compose up -d relay
    curl -sf http://localhost:8080/health

## Phase 1 security posture — DO NOT EXPOSE PUBLICLY

- `CheckOrigin` accepts ALL origins. Any browser anywhere can open a WebSocket to `/ws`. Intentional for dev scaffolding; Phase 2 adds JWT auth bound to the paired device's `sign_pubkey`.
- No rate limiting in Phase 1.
- No TLS termination in the relay itself — Caddy handles TLS in `deploy/` when exposed.
- Bind to `127.0.0.1` in `LISTEN_ADDR` or firewall port 8080 if running on a LAN-accessible host until Phase 2 auth lands.

## Endpoints

- `GET /health` — liveness, returns `{"status":"ok"}`.
- `GET /ws` — WebSocket upgrade. Echoes validated envelope back. Validates only envelope (not payload). Max message size: 1 MB.

## Schema

Envelope schema is embedded at build time from `/proto` via `make sync-proto`. See `/proto/README.md` for the protocol definition.
