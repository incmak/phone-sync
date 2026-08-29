# Mirrored Notification Actions Design

**Date:** 2026-08-29 (revised same day after design review)

**Status:** Approved

**Scope:** Standalone per-notification mirrors with reconstructed actions (reply, mark-as-read, archive, …), secure origin-side action execution, and tap-to-open routing on the mirror phone.

**Depends on:** Reliable-delivery foundation (v2 protocol, outbox/mailbox/receipts, canonical state + materializer), direct LAN delivery (route-agnostic; actions ride whichever route holds custody).

## 1. Goal

Every mirrored notification is already a standalone notification with a stable identity (per-`canon_id` mirror tag/id, updates replace in place, dismissal round-trips). This subproject adds the three missing capabilities:

1. **Action fidelity.** The mirror reproduces the source notification's action buttons — including inline text reply — and invoking one executes the *original* `PendingIntent` on the origin phone, because Android action intents are capabilities owned by the originating app and device and cannot be transferred.
2. **Tap-to-open.** Tapping a mirror opens the same app locally when it is installed on the mirror phone; otherwise it opens a Twinotify notification-detail screen. The source notification's tap-dismiss (auto-cancel) semantics are preserved on both phones.
3. **Honest action lifecycle.** An invocation is at-most-once, valid for 2 minutes, visible as Pending while in flight, and reports a truthful terminal outcome. "Success" is reported as **dispatched** — Android only proves the intent was sent, not that the source app applied it — and a crash window is reported as **outcome unknown**, never silently retried. The strongest confirmation of a reply is organic: the source app's own subsequent notification update.

Non-goals for this subproject: media/transport controls, call answer/decline controls (still `call_style_deferred_no_controls`), image or file replies, smart/generated replies, iOS/desktop.

## 2. Product Semantics

### 2.1 What is mirrored

- Up to **3 actions** per notification (Android's own visible-action cap), in source order, skipping any action without a `PendingIntent`.
- Action titles truncated to 64 characters. A reply action carries the source `RemoteInput`'s free-form capability and label; only free-form **text** replies are supported (reply text capped at 4,096 bytes UTF-8).
- Whether the source notification auto-cancels on tap (`FLAG_AUTO_CANCEL`) is captured and reproduced on the mirror.

### 2.2 Invocation contract

- Invoking a mirrored action sends an encrypted request to the origin phone; the origin validates it and fires the original `PendingIntent` (with `RemoteInput` results attached for replies).
- **Validity window: 120 seconds** from invocation. If the origin is offline, the request waits in the relay mailbox and executes on reconnect *within* that window; past it, every layer independently refuses execution (§9.2).
- **At-most-once.** A given invocation executes at most once, across duplicate delivery, reconnects, and process death on either phone. The origin durably *claims* an invocation before executing it (§6.2); a claim with no recorded completion is never re-executed and terminates as `outcome_unknown`.
- **Locked mirror phone.** Reconstructed actions set `setAuthenticationRequired(true)`, so Android demands unlock before the action fires; the invoke receiver additionally rejects while the keyguard is locked. Nothing is transmitted from a locked mirror.
- Mirror-side lifecycle states: `PENDING` → `DISPATCHED` | `OUTCOME_UNKNOWN` | `FAILED` | `ACTION_GONE` | `NOTIFICATION_GONE` | `EXPIRED`. While `PENDING`, a reply shows its text via `setRemoteInputHistory` with a "Sending…" indication. `DISPATCHED` presents as "Sent" (documented in-product help as "handed to the app on the other phone"); `OUTCOME_UNKNOWN` presents as unconfirmed, not as failure.

### 2.3 Tap routing

The content intent is always an immutable, direct activity `PendingIntent` into a small Twinotify `NotificationRouterActivity`. Notification trampolines are banned since Android 12, but this activity is started directly by the notification, never through a broadcast or service intermediary. It resolves the destination **at tap time** from an opaque random `detail_id`:

1. If the cached source package currently resolves to a launcher activity on the mirror phone (via a `<queries>` MAIN/LAUNCHER manifest declaration), the router launches that front-door activity and finishes. Android provides no portable deep link into another app's private notification destination, and we do not pretend otherwise.
2. If the package is absent, has no launcher, or launching it fails, the router remains in Twinotify and opens route `notification/[detailId]`. `canon_id` never enters navigation state because canonical IDs embed package and free-form tag content. Cancellation also nulls `desiredPayloadJson` (`NotificationStateReducer`), so the screen reads from a dedicated snapshot cache (§5.3) that is durable before the platform notification can be posted and survives the auto-cancel race.

`setAutoCancel` mirrors the captured source flag. When a tap auto-cancels the mirror, the own-package removal path emits the normal `notif.cancel`, so both copies disappear exactly when the source would have. A non-auto-cancel source stays visible on both phones after a tap.

**Behavior changes:**
- Today `MirrorTapReceiver` makes any mirror tap dismiss both sides unconditionally. That receiver is replaced by the routing above; dismissal-on-tap now follows the source notification's own semantics.
- Today `ReasonCodeFilter` labels every own-package (mirror) removal `user_swipe`. Its own-package branch gains reason-code awareness so a tap-driven auto-cancel is labeled `user_click` (reason 1) and swipes remain `user_swipe`; emission semantics are otherwise unchanged.

### 2.4 Explicit limitations (surfaced honestly, not silently)

- Actions whose `PendingIntent` targets an activity may be blocked by Android 14+ background-activity-launch restrictions on the origin; these report `failed`, never a fake success.
- `dispatched` proves dispatch, not application; the source app's organic update/removal is the real confirmation and propagates as a normal `notif.update`/`notif.cancel`.
- Authentication-gated in-app confirmations still happen on the origin phone.
- A source app update/removal between mirror display and invocation yields `action_gone`/`notification_gone`.
- Some OEM skins (including MIUI/HyperOS on the project's test hardware) suppress or reorder action buttons and restrict background work; the mirror shows what was captured.
- The mirror notification is still attributed to Twinotify by the OS; source app name and icon are shown in content as today.

## 3. Alternatives Considered

### 3.1 Selected: origin-side action registry + encrypted invoke/result events

The only design that executes the *real* app action. The origin keeps the live `Notification.Action` objects (in memory — `PendingIntent`s are process-scoped and must never be persisted), addressed by unforgeable random IDs that the mirror echoes back. High fidelity, E2EE-preserving, no new relay capability.

### 3.2 Rejected: local approximation on the mirror

Opening the local app and hoping the user finishes there cannot reproduce reply/archive/mark-read, and desynchronizes app state across phones.

### 3.3 Rejected: shipping `PendingIntent`/intent descriptions across devices

Not technically possible for the intent itself, and reconstructing intents from attacker-influenceable descriptions is an intent-injection hazard. The mirror never names packages, components, extras, or `RemoteInput` keys to execute — only an opaque `action_id` the origin itself minted.

### 3.4 Rejected: persisting the action registry

Serializing capability tokens to disk widens the theft surface and they do not survive reboot anyway. Instead, the registry is memory-only and self-healing: the listener's existing `onListenerConnected` re-capture pass commits a **new sequence** for each active notification with freshly minted IDs and pushes a `notif.update`, so mirrors converge to valid IDs after any origin restart. Stale IDs fail closed as `action_gone`.

## 4. Protocol Changes

No relay change. All new content is E2EE inner-event payload riding the existing v2 envelope, mailbox, and custody rules; `relay-control.schema.json` and the relay runtime are untouched. The relay observes the same envelope shape and routing metadata as today; ciphertext sizes vary with content, as they already do.

### 4.1 Additive fields on `notif.post` / `notif.update` payload

`NotifPostJson` gains (payload `v` stays 1; the receiver decoder is lenient, verified in `NotifPostBuilder.fromPayloadJson`, so old peers ignore these):

```json
{
  "is_auto_cancel": true,
  "actions": [
    {
      "action_id": "b6d3…-uuid",
      "title": "Reply",
      "semantic": 1,
      "reply": true,
      "reply_label": "Message"
    }
  ]
}
```

- `actions`: max 3 items; `action_id` is a fresh random UUID **minted only together with a newly committed sequence** (§6.6); `semantic` is `Notification.Action.getSemanticAction()`; `reply` means a free-form-text `RemoteInput` exists; `reply_label` ≤ 64 chars, optional. `RemoteInput` result keys never cross the wire.
- Every `notif.update` carries the full current action set; the IDs of prior sequences are invalidated at the origin the moment a newer sequence commits.

### 4.2 Reconciling `proto/notif-post.schema.json`

The existing payload schema is already out of sync with the emitted contract (`package` vs `package_name`, `small_icon_png` vs `small_icon_png_b64`, plus never-emitted `channel_*`/`template`/`messages` fields and an older aspirational `actions` shape carrying `remote_input_key`, which this design forbids on the wire). It is validated nowhere at runtime today. This subproject **rewrites it to match `NotifPostJson` byte-for-byte** — existing emitted fields under their real names, plus `is_auto_cancel` and the §4.1 `actions` descriptor — and wires it into the cross-layer fixture suite (Go `fixture_test.go` + Kotlin `ProtocolFixtureTest`) so payload drift becomes a CI failure instead of dead documentation. Only after that reconciliation do the new fixtures enforce anything.

### 4.3 New inner event types

Added to the `type` enum in `proto/inner-event-v2.schema.json` and to `ProtocolJson.innerTypes` — **not** to `canonicalTypes`: they are control-lane events (no top-level `canon_id`/`sequence`, like `peer.receipt`), so they never perturb notification sequence ordering. Both get fully pinned payload schemas (`additionalProperties: false`, following the `call.state` template) plus valid/invalid fixtures in `proto/fixtures/` with manifest entries and a new cross-layer fixture type in `fixture_test.go`'s `validateFixtureDeclaration`.

`notif.action.invoke` (mirror → origin), top-level `expires_at = created_at + 120_000`:

```json
{
  "invocation_id": "uuid",
  "canon_id": "origin:pkg:id:tag",
  "action_id": "uuid",
  "notification_sequence": 17,
  "reply_text": "On my way",
  "invoked_at": 1788267348000
}
```

`reply_text` optional, ≤ 4,096 bytes; `notification_sequence` is the generation the mirror was displaying, letting the origin fail closed on generation mismatch.

`notif.action.result` (origin → mirror), top-level `expires_at = created_at + 600_000`:

```json
{
  "invocation_id": "uuid",
  "canon_id": "origin:pkg:id:tag",
  "status": "dispatched"
}
```

`status ∈ { dispatched, outcome_unknown, action_gone, notification_gone, expired, failed }` — safe codes only, no source-app internals or exception detail ever crosses the wire.

### 4.4 Transport properties

- Both events are ordinary `OutboundMessage` rows: `requiresPeerReceipt = false` (the result *is* the application-level response for an invoke; a result is fire-and-forget), custody via `relay.accepted`/LAN as today.
- The invoke row's local `expiresAt` is its 2-minute deadline, so `dao.expireLocal` stops retrying it and journals `EXPIRED` if custody was never obtained in time.
- Transport-duplicate delivery is absorbed by the inbound journal (`msg_id` dedup); *semantic* at-most-once is enforced separately by the origin's durable execution journal (§6.2), because a re-send under a new `msg_id` must also never re-execute.
- `EnvelopeAuthenticator` allows +5 min past `expires_at`, which is wider than the product window. The origin therefore applies its own strict origin-clock check, `now ≤ invoked_at + 120_000`, before executing anything. There is no extra execution grace: transport-level expiry is only a backstop, not the product gate. Clock skew may cause an early refusal, but never intentionally extends the documented window on the origin clock.

## 5. Persistent Data Models

Room migrates **8 → 9** (CLAUDE.md/AGENTS.md said 5 and 7 — both stale, corrected alongside this spec; schema JSONs `2.json`–`8.json` are committed today). `MIGRATION_8_9` is additive only, registered in `NotificationDb.get()`, with `schemas/.../9.json` committed. No destructive migration. Rollback caveat in §10.

### 5.1 Mirror-side invocation state (new table `action_invocation`)

```text
ActionInvocation
  invocationId: String primary key
  canonId: String
  actionId: String
  notificationSequence: Long
  replyText: String?          // needed to re-render RemoteInput history across process death;
                              // NULLED in the same transaction as the terminal state transition
  state: PENDING | DISPATCHED | OUTCOME_UNKNOWN | FAILED | ACTION_GONE | NOTIFICATION_GONE | EXPIRED
  createdAt: Long
  expiresAt: Long             // createdAt + 120_000
  updatedAt: Long
```

Committed in the **same Room transaction** as the invoke outbox row. `replyText` exists only while `PENDING` and is cleared on any terminal transition; a retention sweep removes terminal rows after 24 h and hard-expires overdue `PENDING` rows (alarm-driven, reusing the `MaterializationRetry`/`AlarmManager` scheduling pattern so expiry fires even with the app process dead-and-revived).

### 5.2 Origin-side execution journal (new table `action_execution`)

```text
ActionExecution
  invocationId: String primary key
  canonId: String
  actionId: String
  state: CLAIMED | COMPLETED
  resultStatus: String?       // §4.3 enum, set when COMPLETED
  claimedAt: Long
  completedAt: Long?
```

This is the at-most-once mechanism *and* the redelivery answer sheet. `CLAIMED` is inserted before any execution; `COMPLETED` records the terminal status so a redelivered or re-sent invocation re-emits the same result instead of re-executing. Completed rows are swept 24 h after `completedAt`. A `CLAIMED` row with no completion older than a 60-second grace window is finalized as `COMPLETED(outcome_unknown)` by a persistent claim-recovery wake, startup recovery, or redelivery, and is never re-executed.

### 5.3 Mirror-side detail cache (new table `notification_detail_cache`)

```text
NotificationDetailCache
  detailId: String primary key      // opaque random UUID, stable per canonId
  canonId: String (unique index)
  payloadJson: String               // last rendered NotifPostJson snapshot
  originDevice: String
  receivedAt: Long
  updatedAt: Long
  cancelledAt: Long?                // starts the bounded post-cancel retention window
```

Allocated and upserted in the **same Room transaction that commits inbound canonical desired state**, before materialization can post the Android notification. The materializer therefore reads an already-durable `detailId` when it builds the content intent; a crash can leave an inert cache row, never a posted notification whose fallback is empty. `notif.update` refreshes the cached payload in the same state transaction. `notif.cancel` retains the last payload and sets `cancelledAt`, because `desiredPayloadJson` is nulled by the cancel reduction and cannot serve the tap race.

Active rows are not age-evicted. Cancelled rows are swept 10 minutes after `cancelledAt` and bounded to the 500 most recently cancelled rows. This is enough for an in-flight tap or open detail screen without turning Twinotify into a day-long notification-history store. `detailId` is the only notification-content identifier that enters content intents and navigation.

### 5.4 Origin-side action registry (memory only)

```text
ActionRegistry (in-memory, ConcurrentHashMap)
  canonId → ActionGeneration {
    sequence,
    sourceKey,
    packageName,
    handlesByActionId: immutable Map<actionId, Notification.Action>
  }
```

- Populated inside the capture coordinator lane when a sequence commits (`DurableCapturePersister`), atomically replacing the single immutable generation value for that `canonId`; purged on `notif.cancel` capture, unpair, and mirroring-disable.
- Never persisted. Listener rebind re-captures `activeNotifications`, which commits new sequences with fresh IDs and emits `notif.update`s (existing behavior, now also refreshing mirrors' actions).
- Unknown/stale `actionId` or `sequence` mismatch → `action_gone`; `sourceKey` no longer in `activeNotifications` → `notification_gone`.

Mirror-side action metadata needs no new table: it lives in `desiredPayloadJson` on `CanonicalNotificationState` while active, and in the detail cache for post-cancel reads.

## 6. Pipelines

### 6.1 Origin capture (additions to existing lanes)

`NotifPostBuilder.captureSnapshot` copies `notification.actions` (title, semantic, free-form `RemoteInput` presence/label — framework handles retained like icons) and `FLAG_AUTO_CANCEL` into the immutable snapshot. `DurableCapturePersister.persist` mints `action_id`s, embeds the metadata array in the payload, and registers the `Notification.Action` handles in `ActionRegistry` keyed by the finally-committed sequence (losing CAS writers re-run and re-register, as they already re-persist).

### 6.2 Origin invoke handling — the claim protocol (new `InboundDispatcher` control branch)

For `notif.action.invoke`, after `EnvelopeAuthenticator`:

1. **Transaction A** (joins the existing `commitDirectControl`-style journal commit): commit the inbound journal row and look up `action_execution` by `invocation_id`:
   - `COMPLETED` → re-enqueue a result carrying the stored `resultStatus` (idempotent answer, no execution);
   - `CLAIMED` older than the 60-second grace window (an earlier attempt died mid-flight) → do **not** execute; finalize as `COMPLETED(outcome_unknown)` and enqueue that result;
   - `CLAIMED` within the grace window → an in-flight duplicate; drop it, the owning attempt will emit the result;
   - absent → insert `CLAIMED` and continue.
2. **Validation, outside Room:** strict freshness (§4.4), registry lookup (`action_id` present, `canonId`/`sequence` match, `sourceKey` still in `activeNotifications`), reply bounds, reply-for-non-reply-action rejection. Any failure → **Transaction B:** `COMPLETED(<action_gone | notification_gone | expired | failed>)` + enqueue result.
3. **Execution, outside Room:** `RemoteInput.addResultsToIntent` with the origin's own real result keys, then `PendingIntent.send(context, 0, fillIn)`; `CanceledException`/BAL denial → status `failed`. Activity-type intents opt in via `ActivityOptions…setPendingIntentBackgroundActivityStartMode`, still expecting possible denial.
4. **Transaction C:** compare-and-set `CLAIMED → COMPLETED(dispatched | failed)` + enqueue result. If the row was already finalized (a racing sweep resolved it to `outcome_unknown`), the CAS loses and no second, contradictory result is sent — `outcome_unknown` truthfully covers "may have fired".

A successful Transaction A schedules a persistent wake for `claimedAt + 60_000` using the existing inexact `AlarmManager.setAndAllowWhileIdle` pattern plus an in-process earliest-wake job. Startup rehydrates the earliest outstanding wake. When it fires, a compare-and-set finalizes every still-`CLAIMED` due row as `COMPLETED(outcome_unknown)` and enqueues its result. Redelivery performs the same due-row transition. A startup inside the grace period therefore re-arms the remaining delay instead of stranding the claim.

A crash between the `CLAIMED` insert and Transaction C leaves a claim with no completion; the scheduled, startup, and redelivery recovery paths resolve it to `outcome_unknown` without re-execution. The `PendingIntent` fires **at most once** in every interleaving, at the deliberate cost that a crash immediately before dispatch reports `outcome_unknown` for an action that never ran — for side-effecting actions, at-most-once beats at-least-once.

The dispatcher never reads packages, components, class names, extras, or `RemoteInput` keys from the wire — the payload contains none, and any extra field is schema-rejected.

### 6.3 Mirror posting (additions to `MirrorPoster` / `AndroidNotificationPort`)

- Reconstruct up to 3 `Notification.Action`s targeting a **non-exported** `ActionInvokeReceiver` via explicit intents. Intent identity comes from a unique **data URI** — `twinotify://invoke/<mirrorLocalTag>/<mirrorLocalId>/<actionId>` — which both guarantees distinct `PendingIntent`s (no request-code arithmetic, no overflow/collision) and is the *only* input the receiver trusts (§6.4).
  - **Non-reply actions:** `FLAG_IMMUTABLE`.
  - **Reply actions:** `FLAG_MUTABLE` — required since Android 12 for the system to attach `RemoteInput` results — with the immutable data URI carrying identity and all identity-bearing extras ignored by the receiver. `RemoteInput("twinotify_reply")` with the source's `reply_label`.
  - All actions: `setAuthenticationRequired(true)`, `setAllowGeneratedReplies(false)`.
- Content intent per §2.3 is an immutable direct-activity intent to `NotificationRouterActivity` with data URI `twinotify://notification/<detailId>`; the router decides installed-app versus Twinotify fallback at tap time. `setAutoCancel(post.is_auto_cancel)` defaults true when the field is absent, preserving current old-origin behavior; `FLAG_NO_CLEAR` logic is unchanged.
- Re-posts for invocation-state changes reuse the stable tag/id and must not disturb canonical sequencing: they re-render the *current* `desiredPayloadJson` plus invocation overlay, and are skipped if the canonical state is no longer `ACTIVE` at that sequence.

### 6.4 Mirror invoke flow (`ActionInvokeReceiver`, new)

1. Parse identity **exclusively from the intent data URI** (mutable reply intents mean extras are untrusted); resolve `canonId` via the existing mirror-identity lookup (`canonicalForMirrorIdentity`). Unresolvable → drop.
2. Reject if `KeyguardManager.isKeyguardLocked` (defense-in-depth behind `setAuthenticationRequired`).
3. Read reply text from `RemoteInput.getResultsFromIntent`; enforce the 4,096-byte cap (over-long input → immediate local `FAILED` presentation, nothing transmitted).
4. Drop silently if the canonical row is not `ACTIVE` (race with a cancel).
5. One transaction: insert `ActionInvocation(PENDING)` + invoke `OutboundMessage`; then signal the coordinator-owned transport and arm the 2-minute expiry alarm.
6. Re-post the mirror in Pending presentation.

Results (`notif.action.result` branch, mirror side): journaled, then update the `ActionInvocation` row **only if still `PENDING`** — clearing `replyText` in the same transaction — and re-post the terminal presentation. A result arriving after local `EXPIRED` is journal-recorded but does not resurrect the UI state.

### 6.5 Route interaction

Nothing route-specific. Invoke/result rows flow through the coordinator-granted outbox owner like any other row: non-self-draining sessions use `TransportCoordinator.pump`, while relay sessions use their existing self-drainer. LAN custody simply makes the round trip near-instant. No changes to `LanTransport`, `RelayTransport`, or custody semantics.

### 6.6 Sequences, snapshots, and ID rotation

The invariant: **fresh `action_id`s are minted only together with a newly committed sequence.** Consequences:

- `state.snapshot.item` reuses the committed `desiredPayloadJson` **verbatim** — same sequence, same `action_id`s. A snapshot never rotates IDs, so it can never invalidate a mirror's displayed actions without a real state transition.
- After origin restart, the registry is empty until the rebind re-capture commits new sequences; a snapshot racing ahead of that may briefly ship IDs whose handles are gone. An invoke against them fails closed (`action_gone`) and the imminent `notif.update` converges the mirror.
- The registry only ever holds entries for sequences whose live handles were captured in the current process.

## 7. Notification Detail Screen and TS Surface

New native functions on `TwinotifyCoreModule`:

- `getNotificationDetail(detailId)` → source app name/package, origin device label, title/text/subtext/bigText, icons, received/updated timestamps, canonical state (`ACTIVE`/`CANCELLED`/gone), action metadata, and current invocation states — served from the detail cache overlaid with live canonical state when present. Content stays out of the UI activity journal exactly as today; this call is the only content-bearing UI path and is device-local.
- `invokeMirrorAction(detailId, actionId, replyText?)` → same pipeline as §6.4 steps 2–6 (shared implementation), so the detail screen can invoke still-valid actions; invocation is refused locally when the canonical state is no longer `ACTIVE`.
- `canLaunchSourceApp(packageName)` → boolean, for the detail screen's "Open in app" affordance.

New Expo Router route `app/notification/[detailId].tsx` reached from the fallback content intent (deep link) showing: source app + origin device, full content, received time, current state, and actions with their invocation states. Functional requirements only are specified here — **visual design is the user's call and will be proposed for approval separately**, per project convention.

## 8. Security Model

- **Capability confinement:** original `PendingIntent`s never leave origin process memory; the wire carries only opaque random IDs bound (in the registry) to pair, `canon_id`, package, generation, and source key.
- **No intent construction from remote data:** execution is registry lookup + original-handle send, full stop.
- **At-most-once, durable:** the §6.2 claim protocol — `CLAIMED` committed before execution, `COMPLETED` with the stored result after, `outcome_unknown` for the gap — covers transport duplicates, fresh-`msg_id` replays, and process death.
- **Freshness:** strict origin-clock 2-minute window > inner `expires_at` backstop > mirror-side local expiry. Three independent layers; any one refusing is terminal. No execution grace extends the origin-clock window.
- **Generation binding:** a source-notification update rotates `action_id`s with its new sequence; stale invokes fail closed as `action_gone`.
- **Locked mirror:** `setAuthenticationRequired(true)` + keyguard check; nothing transmitted while locked.
- **Receivers and intents:** `ActionInvokeReceiver` is non-exported and explicit-intent only. Reply `PendingIntent`s are necessarily `FLAG_MUTABLE` (Android's direct-reply contract), so their trusted identity lives entirely in the immutable data URI and every identity-bearing extra is ignored; non-reply and content intents are `FLAG_IMMUTABLE`.
- **Information discipline:** result statuses are the closed enum in §4.3; reply text exists only inside E2EE payloads and the mirror's `PENDING` row (nulled at terminal transition); no content, action titles, or reply text in logs, metrics, or the UI activity journal; navigation and intent data carry only opaque `detailId`/`actionId`/mirror-tag values, never `canon_id`.
- The relay learns nothing new in kind: same envelope shape and routing metadata; ciphertext sizes vary with content, as they already do for every payload.

## 9. Failure Handling

### 9.1 Duplicate and lost frames

Invoke redelivery → the execution journal re-emits the stored result, no re-execution. Result loss → mirror stays `PENDING` until its 2-minute local expiry → `EXPIRED` presentation; if the action actually dispatched, the source app's own `notif.update` still converges visible truth. Result redelivery → idempotent row update.

### 9.2 Origin offline

- Offline < 2 min on the origin clock: invoke waits under relay custody and executes on drain within the window. A result arriving after the mirror has locally expired is journaled but does not resurrect its terminal UI; the organic source-app update may still converge visible state.
- Offline > 2 min: origin's strict check rejects with `expired`; if delivery slips past `expires_at + 5 min`, `EnvelopeAuthenticator` refuses and the standard expiry path terminalizes the sender row. Either way nothing executes late.

### 9.3 Process death

- Mirror dies after commit, before send: outbox row + `PENDING` row survive; the coordinator-owned drainer resumes; the expiry alarm re-arms from persisted `expiresAt` on service start.
- Origin dies with a `CLAIMED` row (any point between claim and completion): never re-executed; resolved to `outcome_unknown` by the persistent wake, startup recovery, or the next delivery attempt (§6.2). Whether the intent fired is genuinely unknowable, and the status says so.
- Origin dies losing the registry: rebind re-capture rotates IDs under new sequences (§6.6); in-flight invokes against old IDs → `action_gone`.

### 9.4 Platform execution failure

`PendingIntent.CanceledException`, BAL denial, or `RemoteInput` attach failure → `failed`, with the real cause only in origin-local diagnostics.

### 9.5 Capacity

Invoke/result rows obey existing outbox caps and are never compacted (they are "action-like events" already excluded from safe compaction by the foundation design).

## 10. Compatibility and Rollout

1. Additive payload fields are ignored by current receivers (lenient `fromPayloadJson`, verified) — a new origin paired with an old mirror renders exactly today's mirrors.
2. A mirror only ever invokes actions it received in a payload, so an old origin never sees `notif.action.*` in practice. If one arrived anyway, the old peer's strict inner decode (`ProtocolJson` type allowlist) treats the unknown type as a **validation failure**, taking the foundation's quarantine path (three identical failures → quarantined digest + `rejected`/`decrypt_failed` receipt) — not a graceful `unsupported_event` journal entry. Terminal and non-crashing, but it is the quarantine lane; the sender-side gate (only invoke what was advertised) is what keeps this path theoretical.
3. Relay deploys nothing; schema/fixture sync (`make sync-proto`) keeps `TestProtocolFixtures` and the Kotlin fixture suite in lockstep. The `notif-post.schema.json` reconciliation (§4.2) lands before any fixture claims enforcement.
4. Room 8→9 is additive, but **APK rollback after the migration runs is not supported without clearing app data**: Room has no 9→8 downgrade path and an older build opening a v9 database fails. Additive columns do not change this. Rolling back requires a data reset and re-pairing; the rollout plan treats the migration as a one-way gate, mirroring the foundation's stance on protocol-floor rollback.
5. The `MirrorTapReceiver` replacement and `ReasonCodeFilter` own-package labeling change (§2.3) ship in the same release as the payload change so tap semantics and `is_auto_cancel` stay coherent; absent-field defaults preserve old-origin behavior.

## 11. Verification Strategy

### 11.1 Schema and fixtures

- `notif-post.schema.json` rewritten to the emitted contract and wired into Go + Kotlin cross-layer fixtures (drift = CI failure); fixtures covering `actions` bounds (0, 3, 4-rejected, over-long title, forbidden `remote_input_key`).
- Valid + invalid fixtures for `notif.action.invoke` (bad `reply_text` length, missing `invocation_id`, unknown key, non-uuid `action_id`) and `notif.action.result` (bad status, unknown key), wired into `manifest.json`, `fixture_test.go` (new cross-layer type in `validateFixtureDeclaration`), and the Kotlin fixture resource path.

### 11.2 Kotlin JVM coverage (required behaviors)

- Capture: actions + auto-cancel flag snapshotted immutably; >3 actions truncated; null-`PendingIntent` actions skipped; `action_id`s minted only with a newly committed sequence, including the CAS-retry path; snapshot items reuse the committed payload verbatim (no rotation).
- Registry: register/replace/purge on capture, cancel, unpair, rebind; lookups fail closed on stale sequence and missing source key.
- Claim protocol: `CLAIMED` precedes execution in one transaction with the journal; redelivery of `COMPLETED` re-emits the stored status without executing; redelivery of orphaned `CLAIMED` finalizes `outcome_unknown` without executing; the persistent 60-second wake and startup rehydration finalize stale claims even without redelivery; concurrent duplicate invokes execute exactly zero or one time.
- Origin validation: strict expiry math; reply bounds; reply-for-non-reply rejection; each terminal status; `failed` on `CanceledException`.
- Mirror: invocation row + outbox row atomicity; keyguard rejection; identity parsed from data URI only (identity-bearing extras on a mutable intent are ignored); reply vs non-reply mutability flags; pending/terminal re-posts keep stable tag/id and never post over a `CANCELLED` canonical; late result does not resurrect; `replyText` nulled at terminal transition; expiry alarm rehydrates from Room.
- Tap and detail: direct router activity selects installed app versus fallback at tap time, including uninstall-after-post and launch-failure cases; auto-cancel propagation with the `user_click` label through the own-package path (no echo regression — `PendingPeerCancel` ordering untouched); detail cache commits before platform post and survives cancel (the auto-cancel race test: tap → cancel commits → `getNotificationDetail` still returns full content); active-row retention plus 10-minute cancelled-row bound and sweep.
- Compatibility: payload without new fields renders current behavior; unknown inner type on the receiver takes the quarantine path deterministically.

### 11.3 Go coverage

Only the fixture inventory/cross-layer additions; assert no relay runtime diff is needed (frames unchanged).

### 11.4 Two-emulator E2E

- Reply round trip: publisher app exposes a `RemoteInput` action; mirror reply executes it; result `dispatched`; source update propagates.
- Mark-read-style broadcast action round trip.
- Origin offline 90 s → executes; origin offline 3 min → `expired` everywhere, never executes on reconnect.
- Duplicate invoke delivery → single execution; origin process kill between claim and dispatch → `outcome_unknown`, no execution on restart; mirror process kill at each stage.
- Update-then-invoke race → `action_gone`; cancel-then-invoke → dropped/`notification_gone`.
- Tap on mirror with source app absent, uninstalled after post, or failing to launch → router falls back to the detail route and renders full content even when the tap auto-cancelled the mirror; auto-cancel tap dismisses both; non-auto-cancel tap dismisses neither.

### 11.5 Physical two-phone matrix (release evidence)

The available hardware is **Xiaomi MI 11X and POCO F1** (MIUI/HyperOS — valuable OEM coverage for exactly the battery-restriction and notification-shaping behaviors §2.4 warns about), plus the two-emulator harness for anything the OS versions cannot host. If Pixel/Samsung hardware is later supplied, rows are re-run there; the gate binds to the devices actually recorded. Record device model, OS build, app build, relay build, network, timestamps for:

| Scenario | Expected |
| --- | --- |
| Messaging-app inline reply from mirror, both awake, LAN and relay routes | Reply lands in conversation; mirror shows Sending → Sent → source-app update; ≤ ~2 s off-LAN |
| Gmail Archive / Mark-read from mirror | Executes on origin; both notifications clear via propagated cancel |
| Reply from locked mirror | Unlock demanded; nothing transmitted before unlock |
| Origin in Doze / MIUI battery restriction, invoke sent | Executes within window on wake ≤ 2 min; `expired` beyond; restriction impact recorded honestly |
| Origin airplane-mode 5 min, then reconnect | No late execution; mirror shows Expired |
| Origin process killed mid-invoke (between claim and dispatch) | `Outcome unknown` on mirror; no execution after restart |
| Origin process killed; listener rebind | Fresh action IDs on mirrors; stale invoke → Action gone |
| Tap mirror, app installed on both phones | App opens; auto-cancel semantics match source |
| Tap mirror, app missing or removed after post | Twinotify detail screen with full content + working actions, including after auto-cancel |
| MIUI-shaped notification (system-modified actions) | Captured set matches what origin shade shows, or documented delta |
| 50-action-day battery/loop sanity | No echo loops, no duplicate executions, battery gate held |

## 12. Release Gate

Not complete while any of these remain: any interleaving that can execute an invocation twice or past its window; a `CLAIMED` row that leads to re-execution instead of `outcome_unknown`; reply text appearing in logs/journal/metrics or surviving a terminal transition; a mirror action that silently does nothing (every invoke reaches a terminal presented state); a reply action shipped `FLAG_IMMUTABLE` (inline reply would silently break); tap regression on the auto-cancel round trip (echo loop); an empty detail screen on the auto-cancel race; unverified Room 8→9 migration or an undocumented rollback story; `notif-post.schema.json` still drifted from the emitted payload; missing physical evidence on the named devices for the reply, locked-phone, Doze/battery-restriction, late-execution, and mid-invoke-crash rows above.

## 13. Follow-on Subprojects

1. Call answer/decline via `CallStyle` (lifts the `call_style_deferred_no_controls` deferral) — same invoke/result spine, stricter UX.
2. Media/transport controls (MediaSession-aware).
3. Non-text replies (images/files) — needs chunking within the 1 MiB envelope or a side channel.
4. Messaging-style rich mirrors (`MessagingStyle` reconstruction with per-message history).

## 14. Implementation Plans

To be authored after this design is approved, as `docs/superpowers/plans/2026-08-29-mirrored-notification-actions-{protocol,origin,mirror-ui,verification}.md`, executed in that order (payload/schema reconciliation first, origin registry + claim protocol second, mirror actions/tap/detail-screen third, verification last). The detail-screen visual design will be proposed to the user separately before the mirror-ui plan lands.
