# PB-003 — Private notification history design

Status: approved by the owner's 2026-09-01 instruction to complete all locally unblocked backlog work.

## Goal

Turn the existing metadata-only activity journal into useful local history without making pairing or delivery correctness depend on retained plaintext.

## Scope

- Keep `ui_activity_event` as the bounded, content-free presentation journal.
- Add a Room v10 sidecar keyed by journal event ID. It stores only an AES-GCM ciphertext, IV, and byte count. Android Keystore's existing Twinotify master key seals a small JSON object containing title and preview.
- Retain at most 500 journal rows, 30 days, and 2 MiB of encrypted content. The user may choose 7 or 30 days.
- Default content retention on for new/existing installs. Disabling it atomically changes the Room policy and deletes all sidecar rows; metadata remains.
- Add a History screen reachable from Home. It supports chronological or app grouping, locally resolved app artwork, clear-all, per-app clear, and explicit content/retention controls.
- Capture outbound post/update content after its authoritative delivery transaction. Capture inbound post/update content after successful materialization. A content-cache failure must never roll back or block notification delivery.

## Non-goals

- Do not retain full notification payloads, icons, actions, replies, phone numbers, or message transcripts in the history sidecar.
- Do not change active desired notification state, pairing, inbound/outbound custody, peer receipts, action journals, or the notification-detail cache.
- Do not add conversation grouping. If added later it must be based only on a stable authenticated conversation source key.
- Do not claim physical OEM evidence in this task.

## Data and transaction rules

- `ui_activity_content.eventId` is a foreign key to `ui_activity_event.eventId` with cascade deletion.
- `ui_history_policy` has one fixed row (`id = 0`) and is read inside the same Room transaction that conditionally inserts content.
- Disabling content retention and clearing sidecar content is one Room transaction.
- Clear-all and clear-app delete only `ui_activity_content` and `ui_activity_event` rows in one Room transaction.
- Retention maintenance deletes journal rows by configured age and row cap, then removes oldest sidecar rows until the encrypted-byte cap is satisfied.
- Bridge results omit message IDs, event IDs, packages, ciphertext, IVs, and conversation identifiers. Per-app clearing uses an opaque SHA-256 group token resolved only inside the native module.

## Failure behavior

- Missing, corrupt, or undecryptable sidecar content yields a truthful metadata-only history row.
- Keystore failure prevents content retention but does not prevent capture, materialization, or history metadata.
- History read/clear/config errors are reported to the UI as local-history errors and do not affect transport.

## Acceptance criteria

1. History rows show app, direction/state/route/time, plus bounded title and preview when enabled and available.
2. Time and app grouping are available; no unstable conversation grouping is exposed.
3. Age, row, and encrypted-byte bounds are constants with tests and user-facing copy.
4. Clear-all and clear-app are transactional and leave pairing, desired state, custody, and receipts untouched.
5. Turning content retention off immediately removes all sidecar rows while preserving metadata rows.
6. Room migrates explicitly from 9 to 10 and exports schema 10; destructive fallback is not used.
7. JVM, TypeScript, Android lint/build, instrumented build, and API-compatible emulator scenarios pass.
