# Twinotify protocol contracts

`proto/` is the committed single source of truth for Twinotify JSON Schema
2020-12 packet contracts. The Android Kotlin client and Go relay are the
current consumers.

## Versioned delivery

v1 remains an online-only compatibility path for legacy envelopes. Current
reliable delivery uses v2: authenticated encrypted inner events with message and
canonical IDs, sequence and expiry metadata, durable relay mailboxes, and
end-to-end peer receipts. Android advertises v2 before v1 and the relay records
a protocol floor once both peers support v2, preventing a forced v1 downgrade.

The relay schemas are generated build input, not a second source of truth. Run:

```bash
make sync-proto
make proto-test
```

`make sync-proto` copies the committed schemas and fixtures into
`relay/internal/server/`, which is gitignored because the Go server embeds that
copy at build time. Do not edit the generated directory. Every schema `$id`
must retain the exact `https://twinotify.app/schemas/` prefix used by
`relay/internal/server/validator.go`; a mismatch makes validation fail.

### Conversation payloads and v2 cancellation

`notif-post.schema.json` includes Android's optional nullable `conversation`.
A non-null conversation has `is_group` and 1–25 messages. Text is 1–4096 Unicode
code points; optional sender name/key and title are at most 256, conversation key
at most 512. Optional strings may be missing or null. Unknown conversation or
message fields are rejected. Shared manifest fixtures exercise these limits.

The v2 `notif.cancel` **inner event** carries canonical ID and sequence in its
header. Its payload is not the v1 `notif-cancel.schema.json` packet. Android's
capture path supplies numeric `reason` and `removed_at`; reconciliation paths may
supply only a reason. Existing v2 decoders treat this payload as an object and do
not require v1 packet fields. Cancellation ordering and ownership are validated
by the authenticated receiver, not by the untrusted relay.

`peer.receipt` outcomes are `applied`, `expired`, `rejected`, and `decrypt_failed`;
rejected/decrypt_failed require a nonempty reason of at most 128 code points.
Receipt custody precedes original-envelope acknowledgement. `applied` follows
platform materialization, not merely desired-state persistence.

Public test-only cryptographic known answers live in `crypto/known-answer-v1.json`.
Swift tests and Android `SharedCryptoVectorTest` consume the same file. Regeneration
uses `crypto/generate-vectors.py` against the libsodium C ABI with fixed test seeds.
Never use those test keys as an application identity.
