# Twinotify Product Backlog

**Last reviewed:** 2026-08-31

**Purpose:** Local source of truth for user-visible follow-up work that is not
owned by the reliability, direct-LAN, relay-hardening, or protected-release
plans.

**Status values:** `BACKLOG`, `READY FOR DESIGN`, `BLOCKED`, `IN PROGRESS`,
`DONE`. An item moves to `IN PROGRESS` only after its design/spec and numbered
implementation plan are approved. Physical observations never become `DONE`
from host tests alone.

## Priority order

| ID | Priority | Product outcome | Status | Dependency |
| --- | --- | --- | --- | --- |
| PB-001 | P0 | Preserve multiple messages and conversation fidelity | READY FOR DESIGN | none |
| PB-002 | P0 | Make the persistent service notification truthful and open Twinotify | READY FOR DESIGN | PB-001 independent |
| PB-003 | P1 | Show useful, private, groupable notification history with explicit clearing | READY FOR DESIGN | PB-001 content model |
| PB-004 | P1 | Make the themed launcher icon render correctly on supported Android launchers | READY FOR DESIGN | physical launcher captures |
| PB-005 | P1 | Make relay setup consumer-friendly with a safe default service | BLOCKED | approved production relay URL and service policy |
| PB-006 | P2 | Add a secondary “Open in Twinotify” mirrored-notification action | BACKLOG | PB-001/PB-003 detail model |
| PB-007 | P1 | Complete a non-technical-user UX audit across onboarding, pairing, home, settings, recovery, and unpair | BACKLOG | PB-002 and PB-005 |

## Implemented invariant requiring physical regression coverage

Twinotify's own posted notifications are already excluded before capture in
`TwinotifyNotificationListener.capturePosted`, and active-notification
reconciliation also excludes the application package. Keep this fail-closed.
Add a physical regression row to PB-002: the foreground-service notification
and Twinotify-authored mirrored notifications must never be sent to the peer,
while removal of a locally mirrored Twinotify notification must continue to
drive the existing peer-dismiss logic exactly once.

## PB-001 — Preserve multiple messages and conversation fidelity

**Problem:** A later notification from the same app/conversation can replace
the previous mirrored presentation. The desired-state protocol intentionally
uses one canonical notification identity, but the current mirror does not
reconstruct messaging history well enough for conversation notifications.

**Scope:** Audit source `StatusBarNotification` identity, message arrays,
conversation metadata, canonical-ID construction, reducer semantics, and
`MirrorPoster` local tag/ID selection. Define when an Android update should
update one conversation notification and when two independently visible source
notifications must remain independently visible.

**Non-goals:** Do not weaken ordered desired-state convergence, peer-dismiss
mapping, supersession terminalization, or notification privacy filtering.

**Acceptance:**

- Two distinct source notifications from one package remain simultaneously visible on the peer.
- Three updates to one source notification converge to one peer notification without duplicates.
- Messaging-style notifications preserve bounded per-conversation message history and sender ordering.
- Dismissal of either distinct notification cancels only its own canonical identity.
- Relay retry, LAN retry, snapshot reconciliation, and process restart do not resurrect superseded content.
- JVM reducer tests, Android notification tests, and a two-phone WhatsApp-like fixture run cover post/update/dismiss in both directions.

## PB-002 — Persistent notification truth, navigation, and self-filtering

**Problem:** The foreground notification always titles itself “Twinotify
active,” places route state only in secondary text, and has no content intent.
It can therefore look connected while delivery is not authenticated, and
tapping it does not open the app.

**Scope:** Derive title and explanation from the same `SyncRouteStatus` and
delivery-state presenter used by the home screen. Add an immutable explicit
content intent to Twinotify's launcher/home route. Preserve the ongoing
foreground-service contract and private lock-screen presentation.

**Acceptance:**

- Tapping the foreground notification opens the existing Twinotify task at home without creating duplicate activities.
- The title distinguishes direct, relay, reconnecting/queued, paused, and stopped states; it never says active/connected from a local relay socket alone.
- Secondary text explains custody using the same pending-local versus relay-held truth as the home screen.
- The content intent is explicit, immutable, and contains no relay URL, peer identifier, token, or notification content.
- Twinotify-authored notifications are never captured for outbound mirroring.
- Unit tests cover every route/delivery state; physical tests cover tap behavior, lock screen, restart, and both OEM phones.

## PB-003 — Private notification history, grouping, retention, and clearing

**Problem:** The current recent-activity journal keeps metadata for up to 500
rows/30 days but exposes at most 20 rows, does not retain display content, does
not group by app/conversation, and has no user-facing clear action. Package
fallbacks can appear as `com.whatsapp` or “Source app” instead of useful
history.

**Scope:** Design a separate Keystore-protected local content cache linked to
the existing presentation journal. Keep protocol/custody tables free of
plaintext. Add app grouping, optional conversation grouping where source
metadata is trustworthy, pagination, configurable bounded retention, clear-all,
and clear-group operations.

**Non-goals:** No cloud history, relay-readable content, cross-device history
query, analytics, or indefinite retention.

**Acceptance:**

- Recent activity shows app name, notification title, bounded preview, direction, delivery state, route, and time when content retention is enabled.
- Users can group by time or app; conversation grouping is used only when a stable source conversation key exists.
- Default retention is explicitly documented and bounded by both age and row/byte limits.
- Clear-all and clear-group delete content and metadata transactionally without deleting pairing, active desired state, or delivery receipts.
- Disabling content retention deletes cached plaintext immediately and leaves metadata-only history functional.
- Migration is explicit, non-destructive, and includes a committed Room schema if a new entity is required.

## PB-004 — Themed launcher icon regression

**Problem:** Expo configuration includes an adaptive monochrome layer, but the
icon is not visible on at least one launcher with themed icons enabled.

**Scope:** Inspect the generated adaptive-icon XML and density assets, verify
the monochrome silhouette has correct alpha/viewport/padding, and compare both
OEM launchers in normal, dark, and themed modes. Treat this as a rendering
regression, not a new brand redesign.

**Acceptance:**

- Generated Android resources retain a `<monochrome>` layer after clean prebuild.
- The mark is visible and optically centered on POCO F1 and MI 11X launchers with themed icons enabled.
- Normal adaptive and round icons remain unchanged outside necessary safe-zone corrections.
- Asset/source checks fail if the monochrome layer is blank, fully transparent, or omitted.
- Before/after launcher screenshots are recorded in a private physical evidence directory.

## PB-005 — Consumer-friendly relay onboarding and safe default

**Problem:** Normal users should not need to type a relay URL. The current
relay field exposes infrastructure configuration during the primary flow.

**Blocking product inputs:** Approve the production relay hostname, TLS and
availability ownership, privacy/retention statement, regional policy, abuse
handling, capacity policy, and whether self-hosting is officially supported.
Do not hard-code a guessed endpoint before these inputs exist.

**Scope after unblock:** Ship the approved TLS relay as the default, move custom
relay entry and connectivity diagnostics under Advanced settings, retain a
clear self-hosted option if approved, and migrate existing saved URLs without
overwriting user choices.

**Acceptance:**

- A new non-technical user can reach pairing without seeing or typing a URL.
- Release builds accept only the approved `https`/`wss` endpoint by default; debug loopback behavior remains debug-only.
- Advanced settings can test and save an allowed custom endpoint without exposing tokens or internal paths.
- Existing configured users retain their endpoint across upgrade.
- Offline/error copy distinguishes internet failure, relay unavailability, and invalid custom configuration without raw exception text.

## PB-006 — Secondary “Open in Twinotify” action

**Problem:** The primary mirrored-notification tap correctly follows the source
app's action semantics. Users may also want a separate way to inspect the local
Twinotify detail/history entry.

**Scope:** Add a bounded secondary action only when a valid local detail entry
exists. Use the established opaque `detailId`; never put notification content,
canonical IDs, peer IDs, or package-controlled deep links in the intent URI.

**Acceptance:**

- Primary notification tap behavior remains unchanged.
- The secondary action opens the matching local Twinotify detail screen.
- Expired/missing detail entries open a truthful unavailable state rather than another app.
- Pending intents are explicit, immutable, collision-safe, and covered across update/cancel/process restart.
- Action count and layout remain usable on both target OEM notification shades.

## PB-007 — Non-technical-user UX audit

**Problem:** Individual screens have been designed and tested, but the complete
journey has not been re-audited for a user unfamiliar with relays, notification
access, background restrictions, or pairing terminology.

**Scope:** Test first launch through pairing, permissions, successful mirroring,
recovery, settings, history clearing, and unpair with plain-language tasks.
Remove infrastructure terms from primary paths and keep diagnostics in
progressive disclosure.

**Acceptance:**

- A first-time user can pair two phones and mirror a notification without entering infrastructure data.
- Every permission request states why it is needed immediately before Android shows it.
- Home communicates route, peer evidence, and custody without requiring knowledge of LAN, WebSockets, or queues.
- Recovery actions are specific, reversible, and do not send users into settings without an explanation.
- Large font, TalkBack, light/dark, one-handed reach, and OEM background-restriction paths are recorded on both target phones.

## Backlog maintenance

When work is approved, create one design/spec and one numbered implementation
plan per backlog item under `docs/superpowers/`. Link both here and update the
status. Do not combine PB-001/PB-003 storage and notification semantics with
PB-005 relay operations in one implementation change; their security and
rollback boundaries are independent.
