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
