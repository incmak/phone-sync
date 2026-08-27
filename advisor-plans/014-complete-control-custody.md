# Plan 014: Complete durable custody for receipts and snapshot controls

> **Executor instructions**: Use the primary checkout and strict TDD. Do not
> commit until an independent protocol/Room reviewer approves. No worktree,
> device, network, push, schema migration, or fabricated relay evidence.

## Status

- **Priority**: P0
- **Effort**: M
- **Risk**: HIGH
- **Depends on**: Plan 013
- **Category**: bug
- **Planned at**: commit `66dc533`, 2026-08-27

## Drift check

```bash
git diff --stat 66dc533..HEAD -- \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/InboundDispatcher.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/LiveTransportRoutes.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReliableDeliveryDao.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/RelayTransport.kt
```

## Why this matters

Snapshot exceptions are logged and then acknowledged over LAN as if durable
processing succeeded. Separately, successfully handled peer receipts and
snapshot controls create no READY relay-ACK journal row, so their relay mailbox
records redeliver until expiry. A conflicting peer receipt is detected by Room
but the dispatcher discards that result and still accepts it.

## Current state

- `InboundDispatcher.kt:179-227` unconditionally returns `Accepted` for peer
  receipts and all snapshot controls; snapshot failures are swallowed.
- `LanTransport.kt:130-164` treats `Accepted` as permission to send custody.
- `ReliableDeliveryDao.kt:211-215` sends relay ACKs only for inbound rows with
  `relayAckState='READY'`.
- `ReliableDeliveryDao.kt:532-550` reaches READY only through the ordinary
  materialization-receipt path.
- `OutboxRepository.onPeerReceipt` preserves digest conflict, but
  `InboundDispatcher` ignores the transition.

The existing `inbound_message` schema already permits null canon/sequence; do
not add a new table or migration.

## Scope

**In scope**:

- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/InboundDispatcher.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/LiveTransportRoutes.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/RelayTransport.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReliableDeliveryDao.kt`
- focused dispatcher, relay, LAN, snapshot, and Room transaction tests
- `advisor-plans/README.md`

**Out of scope**: protocol schema, mailbox server semantics, snapshot algorithm,
Room schema version, or changing ordinary state receipt-before-ACK ordering.

## Implementation steps

### 1. Capture all three RED classes

Add tests proving the current failures:

1. `onBegin`, `onItem`, and `onEnd` storage exceptions must not emit LAN
   acceptance or relay ACK readiness;
2. successful `peer.receipt`, digest, begin, item, and committed end each create
   one idempotent READY ACK journal entry and later become SENT;
3. reconnect does not execute an already ACKed control again;
4. same msg/digest duplicates replay the same lifecycle, while digest conflict
   is rejected;
5. `OutboxTransition.Conflict` from a peer receipt is never accepted;
6. snapshot end becomes ACK-ready only after the Room commit succeeds.

### 2. Make control processing fail closed

Replace `runCatching(...).onFailure(...); Accepted` with explicit outcomes:

- cancellation escapes unchanged;
- permanent authenticated validation errors return bounded `Rejected` codes;
- transient storage/runtime failures throw so the route closes without custody;
- success proceeds to durable control-journal commit.

Do not include exception messages, snapshot IDs, canon IDs, or payload content in
public codes.

### 3. Persist direct relay-ACK readiness

Add an idempotent DAO transaction using `InboundMessage` with null
canon/sequence for the ordinary no-peer-receipt controls covered here:
`peer.receipt`, `state.digest`, `state.snapshot.begin`,
`state.snapshot.item`, and `state.snapshot.end`. It must atomically verify
msg/digest, record APPLIED/READY only after control processing, and return
Duplicate or IdConflict consistently. Preserve the typed
`InboundDispatchResult` through the relay hook where needed; do not convert
rejection to Unit-success.

`unpair` is explicitly excluded from this generic path. It retains the existing
`AcceptedAfterCustody` ordering: LAN writes acceptance before its once-only
post-custody wipe/service-stop finalizer, while relay executes the same finalizer
only after relay custody already exists. Add regression tests proving Plan 014
does not create a generic READY journal row for unpair or run its finalizer twice.

For ordinary notification/call state, keep the existing platform materialize ->
peer receipt custody -> READY relay ACK ordering.

### 4. Gates

```bash
cd mobile/android && ANDROID_HOME=/Users/mak/Library/Android/sdk ./gradlew --no-daemon \
  :twinotify-core:testDebugUnitTest --tests '*InboundDispatcher*' --tests '*RelayTransportTest' --tests '*LanTransportTest' --tests '*Snapshot*' --rerun-tasks
cd mobile/android && ANDROID_HOME=/Users/mak/Library/Android/sdk ./gradlew --no-daemon \
  :twinotify-core:testDebugUnitTest :twinotify-core:compileDebugAndroidTestKotlin :twinotify-core:lintDebug
cd relay && GOCACHE=/private/tmp/twinotify-control-custody-cache go test ./... -race -count=1
git diff --check
```

## Done criteria

- [ ] Failed snapshot processing cannot produce LAN custody or relay ACK.
- [ ] Every accepted no-peer-receipt control reaches READY then SENT exactly once.
- [ ] Conflicting receipts/controls reject and preserve retryable source state.
- [ ] Ordinary receipt-before-ACK order is unchanged.
- [ ] Unpair remains on its dedicated once-only post-custody finalizer path.
- [ ] Kotlin full gates, relay race tests, and independent review pass.

## STOP conditions

- A proposal marks ACK ready before durable control processing.
- A schema migration or server protocol change appears necessary.
- Rejection would expose payload, peer, network, or message identifiers.
- Ordinary notification materialization loses its peer-receipt barrier.
