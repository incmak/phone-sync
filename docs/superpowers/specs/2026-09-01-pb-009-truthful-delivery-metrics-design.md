# PB-009 - Truthful delivery metrics design

Status: approved by the owner's 2026-09-01 instruction to complete all locally unblocked backlog work.

## Goal

Make the Home metrics describe notification deliveries that this phone can prove, without counting queue attempts or inventing latency when clock evidence is unusable.

## Scope

- Define “Mirrored” as an outbound v2 `notif.post` or `notif.update` that reaches the existing authenticated terminal peer receipt with status `applied`.
- Persist one metadata-only metric row per original `msg_id` in the same Room transaction that records the receipt and removes the outbox row.
- Count rows whose locally observed receipt time falls within the phone's current local civil day. Re-evaluate the day window at every read so process restart, daylight-saving transitions, and timezone changes remain truthful.
- Calculate delivery latency from the authenticated original event `created_at` to the authenticated peer-receipt `created_at`. Keep only non-negative values within the protocol's 24-hour retention plus five-minute skew allowance. Record clock-skew, implausible, and unavailable evidence separately and exclude those rows from the displayed rolling average.
- Display the integer average of the most recent ten measured notification deliveries. Return `null` when no valid sample exists so a genuine 0 ms value is not confused with “No data”.
- Keep the existing blocked counter local and independent. Move its rollover key to the local civil date so all “today” values use the same user-facing boundary.
- Bound the metric ledger independently of notification history; clearing history must not rewrite delivery facts, while unpairing clears them with the rest of reliable state.

## Non-goals

- No v1/legacy forwarding is labeled verified delivery because it has no authenticated peer receipt.
- Inbound application, retries, duplicate receipts, relay custody, snapshots, controls, calls, cancellations, rejected/expired/decrypt-failed outcomes, and notification-history clearing do not increment Mirrored.
- No protocol or relay change. No notification content, package name, canonical ID, peer ID, or ciphertext is stored in the metric ledger.
- No physical latency-performance claim until the two-phone scenario is rerun.

## Acceptance criteria

1. The first authenticated `applied` receipt for an outbound notification atomically creates one metric row; replaying the receipt creates none.
2. Non-notification traffic and non-applied terminal outcomes create no metric row.
3. The visible count is derived from persisted rows in the current local-day window and survives process restart, UTC midnight, DST, and timezone changes according to those semantics.
4. The visible latency is the last-ten average of valid authenticated timestamp deltas. Negative, overflowed, over-retention, or missing evidence yields no fabricated sample and retains a reason code.
5. The bridge represents absent latency as `null` and renders “No data”; a measured zero renders `0 ms`.
6. Room migration 10 to 11 is explicit and validated; JVM, Room, TypeScript, lint, and Android assembly gates pass. Physical two-phone reconciliation remains pending.
