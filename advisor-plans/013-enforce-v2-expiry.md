# Plan 013: Enforce v2 expiry on direct delivery and local retry

> **Executor instructions**: Primary checkout, strict RED-first TDD, no worktree,
> device, network, push, or commit before review. Keep every expiry transition
> transactional and route-neutral.

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: HIGH
- **Depends on**: Plan 012
- **Category**: bug
- **Planned at**: commit `66dc533`, 2026-08-27

## Drift check

```bash
git diff --stat 66dc533..HEAD -- \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/protocol/EnvelopeAuthenticator.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/OutboxRepository.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReliableDeliveryDao.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReliableDeliveryEntities.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/NotificationDb.kt \
  mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/protocol/EnvelopeAuthenticatorTest.kt \
  mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/OutboxRepositoryTest.kt \
  mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/storage/ReliableDeliveryMigrationTest.kt
```

## Why this matters

The protocol records `expires_at`, but Android only checks that it is later than
`created_at`. Direct LAN can therefore apply an already-expired event, and a
LAN-only outbox can retry expired rows forever because only relay-originated
`relay.expired` frames terminalize them.

## Current state

- `ProtocolJson.kt:141-163` validates relative timestamp ordering only.
- `EnvelopeAuthenticator.kt:22-59` has no clock or current-time check.
- `ReliableDeliveryDao.kt:119-123` selects due NEW/ACCEPTED rows regardless of
  expiry. Its current first-custody fields cannot prove that a LAN-accepted row
  was later also accepted by relay.
- `sweepRetention` deletes terminal history but does not transition active
  expired rows.
- `ReliableDeliveryDao.expireRelay` already demonstrates the required
  terminal activity pattern but is tied to a relay frame.

## Scope

**In scope**:

- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/protocol/EnvelopeAuthenticator.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/OutboxRepository.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReliableDeliveryDao.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReliableDeliveryEntities.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/NotificationDb.kt`
- `mobile/modules/twinotify-core/android/schemas/co.twinotify.core.storage.NotificationDbImpl/6.json` (generate)
- `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/storage/ReliableDeliveryMigrationTest.kt`
- corresponding protocol, repository, Room transaction, LAN transport tests
- `advisor-plans/README.md`

The controlling contract is
`docs/superpowers/specs/2026-08-09-reliable-delivery-foundation-design.md:47-49,339-341`:
relay retention is 24 hours and the receiver allows exactly five minutes of
clock skew before rejecting inner expiry.

**Out of scope**: changing the 24-hour producer retention, five-minute receiver
skew allowance, or server mailbox TTL. A narrow v5-to-v6 Room migration is in
scope because relay custody authority cannot be inferred safely from the
existing first-custody route field.

## Implementation steps

### 1. RED: expired inbound and outbound cases

With injected clocks, prove:

- `now == expiresAt + 5 minutes` is accepted, while one millisecond beyond that
  boundary is rejected after authentication and before dispatch;
- saturating arithmetic cannot overflow a hostile near-`Long.MAX_VALUE` expiry;
- an expired LAN frame emits no `LanFrame.Accepted` and cannot materialize;
- due active rows with `expiresAt <= now` move once to terminal expired activity
  and are never returned by `sendable`;
- a row first accepted by LAN and later accepted by relay records both facts;
  local expiry never terminalizes it because relay mailbox lifetime is now
  authoritative;
- v5 migration preserves first custody and assigns the new relay-custody state
  conservatively: NEW -> NONE, first-route RELAY -> ACCEPTED, and every other
  already-ACCEPTED row -> UNKNOWN because a LAN-first row may later have reached
  relay without v5 recording that fact;
- migrated UNKNOWN rows are never client-expired. They resolve only through a
  later observed relay acceptance, peer receipt, or authoritative relay expiry;
  migration must prefer bounded over-retention to deleting possibly
  relay-custodied work;
- repeated sweep/selection is idempotent;
- equal-createdAt ordering of remaining rows is unchanged;
- relay-expired and local-expired races produce one terminal record.

### 2. Add the authenticated current-time boundary

Inject `clock: () -> Long` into `EnvelopeAuthenticator`, defaulting to wall time.
After outer/inner equality validation, reject only when `clock()` is strictly
later than `inner.expiresAt + 300_000`, using saturating addition. Return a
stable content-free protocol error. Do not log raw envelopes.

### 3. Terminalize local expiry transactionally

Add a closed-world `relayCustodyState` to `OutboundMessage` with exactly NONE,
UNKNOWN, and ACCEPTED, plus an explicit v5-to-v6 migration/schema export. New
rows start NONE. Every relay acceptance moves NONE or UNKNOWN to ACCEPTED even
if LAN was the first custody route; duplicate relay acceptance is idempotent.
The migration maps NEW to NONE, first-route RELAY to ACCEPTED, and other active
ACCEPTED rows to UNKNOWN. Do not guess that a v5 LAN-first row lacked later
relay custody.

Then add one DAO transaction that finds active expired rows in deterministic
order and moves only these rows through the existing terminal-activity point:

- NEW rows with no custody;
- ACCEPTED rows whose relay custody state is known NONE (LAN-only custody).

Rows with relay custody state ACCEPTED or UNKNOWN remain until authenticated
peer receipt or authoritative `relay.expired`. Wire `OutboxRepository.sendable`
and retention sweep so known LAN-only idle periods converge. Use a route-neutral
local event/status such as `delivery.expired`/`expired`; do not mislabel it as
relay.

### 4. Gates

```bash
cd mobile/android && ANDROID_HOME=/Users/mak/Library/Android/sdk ./gradlew --no-daemon \
  :twinotify-core:testDebugUnitTest --tests '*EnvelopeAuthenticatorTest' --tests '*OutboxRepositoryTest' --rerun-tasks
cd mobile/android && ANDROID_HOME=/Users/mak/Library/Android/sdk ./gradlew --no-daemon \
  :twinotify-core:testDebugUnitTest :twinotify-core:compileDebugAndroidTestKotlin :twinotify-core:lintDebug
git diff --check
```

## Done criteria

- [ ] Expired authenticated LAN events never reach dispatcher/materializer.
- [ ] Expired active outbox rows terminalize exactly once and stop retrying.
- [ ] Relay-custodied rows never terminalize from the client clock.
- [ ] Ambiguous migrated v5 ACCEPTED rows remain UNKNOWN and cannot be
  client-expired; migration tests cover LAN-first-plus-possible-relay history.
- [ ] v5-to-v6 migration and schema identity are compile-checked; connected
  migration/transaction tests run on an explicitly selected device when one is
  available, otherwise that runtime evidence is recorded as pending.
- [ ] Boundary, race, idempotence, and ordering tests pass.
- [ ] No protocol-schema, producer-retention, or relay-mailbox TTL change; the
  only persistence change is the reviewed v5-to-v6 Android Room migration.
- [ ] Full native gates and independent transaction/security review pass.

## STOP conditions

- A fix deletes active rows without terminal activity evidence.
- Expiry requires trusting relay time instead of authenticated inner metadata and
  the local clock.
- Migration would require destructive fallback, guessed relay history, or more
  state than the one closed-world relay-custody column.
- Existing protocol documentation defines a contrary expiry boundary.
