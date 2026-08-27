# Superseded Inbound Custody Completion Plan

**Status:** Complete on 2026-08-27. Final focused JVM and Room instrumentation passed, the full native gate passed, and independent rereview approved the frozen diff. Evidence: `.omo/evidence/plan018-terminalize-superseded-inbound.md`.

**Goal:** When a newer authenticated notification or call state supersedes an older inbound row that is still `PENDING_PLATFORM`, durably reject the older message with reason `superseded` so its sender can terminalize the original outbox row instead of waiting until expiry. Also terminalize a newly received lower/equal-sequence notification with a rejected peer receipt rather than accepting custody without the receipt its sender requires.

**Architecture:** The authenticated inbound dispatcher already serializes desired-state work with `stateMutex`. It performs a read-only duplicate/ID-conflict/stale preflight before doing cryptographic work. Before it commits a genuinely newer desired state, it reads the exact older pending inbound rows for that canonical and prepares one encrypted rejected peer receipt per original message. A single Room transaction then repeats all preflight checks, re-queries the authoritative exact older set, inserts the newer inbound/canonical state, replaces any provably private unactivated staged receipt for each older row, marks each older inbound `REJECTED`, and activates its rejected receipt as `NEW`. If receipt preparation is incomplete, the newer delivery is rejected before durable acceptance so LAN sends no `Accepted` frame and relay sends no ACK. Existing route-neutral receipt custody then deletes each receipt and marks its original inbound ready for relay ACK. A bounded materializer reconciliation uses the stranded `PENDING_PLATFORM` rows themselves as durable work to repair rows created before this version.

## Constraints

- Apply to both notification and call canonicals; both share `commitInboundDesired` and platform materialization.
- One rejected receipt must acknowledge exactly one older `msgId` and its exact stored `envelopeSha256`.
- Use the closed-world status `rejected` and bounded reason `superseded`.
- Never report acceptance for the newer inbound unless all older pending rows were terminalized atomically with durable rejection receipts.
- Preserve duplicate replay and `id_conflict` behavior. A duplicate newer message must not create new receipts or re-run supersession.
- A lower/equal-sequence notification with a different message ID is a durable state rejection, not successful materialization: use `rejected` with bounded reason `notification_sequence_stale`. Same-message/same-digest remains Duplicate; same-message/different-digest remains `id_conflict`.
- An older staged `PENDING_PLATFORM` receipt says `applied` and must not be activated after supersession. Delete it only inside the same transaction that installs the replacement rejected receipt, and only after proving it is a `peer.receipt`, has `requiresPeerReceipt=false`, has `state=PENDING_PLATFORM`, and is referenced by exactly that older inbound row.
- Exact-set validation is bidirectional and transaction-authoritative: every older pending row has exactly one supplied entry, every supplied entry names a current older pending row, stored digests match, and receipt IDs are unique.
- `REJECTED` is a terminal inbound outcome and must participate in the existing retention sweep.
- Preserve `CancellationException` identity while preparing receipts. Ordinary receipt-preparation failure is fail-closed and content-free.
- Do not add a Room migration, protocol field, relay behavior, timer, destructive fallback, or UI change.
- Work directly in the primary checkout. Keep commits local and do not mutate device pairing or radio state.

## Task 1 - Freeze supersession preparation behavior

**Files:** `InboundDispatcher.kt`, `InboundDispatcherControlTest.kt` or a new focused JVM test.

1. RED: add a pure production-used preparation seam that takes stored older inbound rows and a rejected-receipt factory. Add a read-only DAO preflight that checks `inbound(msgId)` first and returns same-digest duplicate, digest conflict, lower/equal sequence, or genuinely newer before receipt creation.
2. Prove it creates exactly one `superseded` rejection per row, preserves stored message ID/digest order, returns unavailable if any receipt cannot be created, and rethrows the exact `CancellationException` object.
3. Wire both notification and call desired-state paths through the supersession seam only after the cheap preflight reports genuinely newer, while retaining the transaction's authoritative duplicate/conflict/sequence recheck. Prove same-digest duplicate and digest-conflict paths invoke neither the receipt factory nor commit mutation.
4. For a lower/equal notification with a different message ID, prepare one `rejected/notification_sequence_stale` receipt using the production rejection factory, preserve exact `CancellationException` identity, and fail before custody if ordinary preparation returns unavailable.
5. Generalize or reuse the existing call-rejection journal so notification and call state rejection share one atomic, tested receipt lifecycle without duplicating transaction logic.
6. Prove no supersession preparation runs when there are no older pending rows.

## Task 2 - Commit newer state and older rejection receipts atomically

**Files:** `ReliableDeliveryDao.kt`, `ReliableDeliveryTransactionTest.kt`.

1. RED: insert sequence 1 as `PENDING_PLATFORM`, stage its not-yet-active applied receipt, then commit sequence 2 with a prepared supersession rejection.
2. Extend `commitInboundDesired` with a typed supersession bundle. Inside its existing Room transaction:
   - check duplicate and ID conflict before any supersession mutation;
   - reject stale/non-increasing desired state as today;
   - query the exact older `PENDING_PLATFORM` rows for the canonical;
   - require the supplied bundle to cover that set exactly, with unique receipt IDs and matching original digests;
   - delete only linked, route-invisible `PENDING_PLATFORM` staged `peer.receipt` rows that require no peer receipt and have an exact reference count of one;
   - insert each rejected receipt as `NEW` with `requiresPeerReceipt=false`;
   - mark its older inbound row `REJECTED`, set terminal time, link the new receipt, and leave relay ACK state to the existing receipt-custody transition;
   - insert the newer inbound and canonical state in the same transaction.
3. GREEN assertions: sequence 1 is terminal with one live rejected receipt, its staged applied receipt is gone, sequence 2 is pending, canonical latest is 2, and no unrelated row changes.
4. Mutation-sensitive rollback assertions: missing bundle entry, digest mismatch, duplicate receipt ID, receipt conflict, or unsafe staged-receipt state leaves every row unchanged.
5. Duplicate and stale regressions: replaying sequence 2 is idempotent. A lower/equal notification with a different message ID commits one inbound `REJECTED` row plus one `NEW` peer receipt with reason `notification_sequence_stale`; it never enters platform materialization. Its sender terminalizes the original row through authenticated `applyPeerReceipt`.

## Task 3 - Repair already-stranded superseded rows

**Files:** `NotificationMaterializer.kt`, `ReliableDeliveryDao.kt`, focused materializer and Room tests.

1. RED: seed canonical sequence 2 as already materialized while sequence 1 remains `PENDING_PLATFORM` with an unactivated staged applied receipt. Prove the current pending-materialization query cannot see it.
2. Add a query bounded by canonical groups, not raw rows. For every selected canonical, the reconciliation transaction must load and validate the complete authoritative set of rows where `outcome=PENDING_PLATFORM` and `sequence < canonical.latestSequence`; never truncate one canonical's exact set. The rows are durable retry records, so no schema or second work table is needed.
3. Extend the existing receipt factory with a default rejected-receipt method while retaining one abstract method so existing functional fakes remain source-compatible. Production `DurableReceiptFactory` implements the rejected method.
4. At the start of each real materialization pass, prepare and atomically terminalize a bounded batch through the same exact-set transaction helper used by newer commits. Preserve exact `CancellationException` identity. If ordinary preparation fails, leave the row untouched and schedule one bounded retry; service/app startup and foreground remain durable fallback triggers after process death.
5. Assert the pass is idempotent, never reposts platform state, replaces the staged applied receipt safely, and stops scheduling once no stranded row remains.
6. Add `REJECTED` to terminal inbound retention and prove the sweep removes it only after the configured cutoff.
7. Include a cancellation-specific regression: notification sequence 1 is stranded `PENDING_PLATFORM`, sequence 2 is an authenticated `notif.cancel`, sequence 1 gets exactly one `rejected/superseded` receipt, and the canonical remains `CANCELLED` at sequence 2.
8. Add a pathological per-canonical overflow test proving reconciliation either processes the complete authoritative set or fails closed without mutating a partial set; it must not loop forever over the same truncated prefix.

## Task 4 - Prove route lifecycle and end-to-end sender terminalization

**Files:** focused pipeline/dispatcher/DAO tests only unless a production seam is demonstrably missing.

1. Add a pipeline regression covering sequence 1 platform failure, sequence 2 arrival, sequence 2 application, and peer processing of sequence 1's rejected receipt.
2. Assert the sender removes its original sequence-1 outbox row through the existing authenticated `applyPeerReceipt` path with status `rejected` and detail `superseded`.
3. Assert rejected receipt custody uses existing LAN/relay receipt behavior: receipt row is deleted on accepted custody and original inbound relay ACK becomes `READY` without changing ordinary receipt-before-ACK ordering.
4. Assert call and notification canonicals share the same transaction behavior.

## Verification

1. Observe focused JVM/Room REDs before production changes.
2. Run focused JVM suites for the preparation seam, materializer/pipeline behavior, and dispatcher contracts.
3. Compile Android tests, then run the exact Room transaction selector on `emulator-5554` if it remains attached and authorization permits.
4. Run the sequential native gate: `lintDebug testDebugUnitTest compileDebugAndroidTestKotlin assembleDebug`.
5. Run `git diff --check`, inspect exact scope, and request fresh independent correctness/security review.
6. Record scenario, command, terminal result, binary observable, and artifact path. Do not conflate compiled instrumentation with executed instrumentation.

## Review questions

1. Is preparing rejection receipts before the Room transaction the smallest way to keep cryptography out of Room while still failing closed before custody?
2. Does exact-set validation prevent a newly committed state from silently leaving any older `PENDING_PLATFORM` row behind?
3. Can any staged applied receipt already be route-visible, or does `PENDING_PLATFORM` guarantee it is safe to replace?
4. Does marking the older inbound `REJECTED` preserve the relay ACK lifecycle through `inboundForReceipt` and `acceptReceipt`?
5. Does the bounded reconciliation repair historical rows without creating an undurable timer dependency or another schema? The expected answer is yes.
6. Does the notification lower/equal sequence path now mirror the durable call-rejection lifecycle instead of acknowledging custody without a peer receipt?
7. Is any schema or protocol migration actually required? The expected answer is no.
