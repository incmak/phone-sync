# Phone-Sync Protocol (v1)

Single source of truth for packet schemas. Each client parses in its native language:

- Relay (Go) — hand-written structs validated against these schemas in tests
- Mobile (Kotlin) — Moshi/Gson-generated types, schema-checked in CI
- Desktop (Rust) — serde_json structs, schema-checked in CI

## Envelope

See `packet.schema.json`. Every message carries `{v, type, msg_id, origin_device, ts, payload}`. Payload shape varies by `type` — see per-type schemas.

## Types (Phase 1 subset)

- `ping` / `pong` — liveness
- `notif.post` / `notif.update` — mirror a notification (see `notif-post.schema.json`)
- `notif.cancel` — dismiss mirror (see `notif-cancel.schema.json`)

Later phases add `notif.reply`, `icon.request`, `icon.reply`, `reply.failed`, `ack`.

## Validation

Schemas are JSON Schema 2020-12. Use any compliant validator.
