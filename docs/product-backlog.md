# Twinotify Product Backlog

**Last reviewed:** 2026-09-01

**Purpose:** Local source of truth for user-visible follow-up work that is not
owned by the reliability, direct-LAN, relay-hardening, or protected-release
plans.

**Status values:** `BACKLOG`, `READY FOR DESIGN`, `BLOCKED`, `IN PROGRESS`,
`SOURCE COMPLETE`, `DONE`. `SOURCE COMPLETE` means implementation and all
available host/emulator evidence are complete, while named physical or
owner-controlled acceptance remains. An item moves to `IN PROGRESS` only after
its design/spec and numbered implementation plan are approved. Physical
observations never become `DONE` from host or emulator tests alone.

## Priority order

| ID | Priority | Product outcome | Status | Dependency |
| --- | --- | --- | --- | --- |
| PB-001 | P0 | Preserve multiple messages and conversation fidelity | SOURCE COMPLETE | physical two-phone fixture |
| PB-002 | P0 | Make the persistent service notification truthful and open Twinotify | SOURCE COMPLETE | physical notification/tap regression |
| PB-008 | P0 | Restore transport automatically after process/package restart | SOURCE COMPLETE | physical lifecycle/OEM regression |
| PB-003 | P1 | Show useful, private, groupable notification history with explicit clearing | DONE | none |
| PB-004 | P1 | Make the themed launcher icon render correctly on supported Android launchers | SOURCE COMPLETE | physical launcher captures |
| PB-005 | P1 | Make relay setup consumer-friendly with a safe default service | BLOCKED | approved production relay URL and service policy |
| PB-007 | P1 | Complete a non-technical-user UX audit across onboarding, pairing, home, settings, recovery, and unpair | SOURCE COMPLETE | PB-005 plus physical two-phone/TalkBack evidence |
| PB-009 | P1 | Make home delivery metrics reflect verified mirrored traffic | SOURCE COMPLETE | physical two-phone reconciliation |
| PB-011 | P1 | Choose the Android 17 local-network privacy model before target SDK 37 | BLOCKED | owner privacy/UX decision |
| PB-006 | P2 | Add a secondary “Open in Twinotify” mirrored-notification action | SOURCE COMPLETE | physical OEM notification-shade regression |
| PB-010 | P2 | Provide an accessible non-camera pairing fallback | BLOCKED | owner security/UX decision |

## Implemented invariant requiring physical regression coverage

Twinotify's own posted notifications are already excluded before capture in
`TwinotifyNotificationListener.capturePosted`, and active-notification
reconciliation also excludes the application package. Keep this fail-closed.
Add a physical regression row to PB-002: the foreground-service notification
and Twinotify-authored mirrored notifications must never be sent to the peer,
while removal of a locally mirrored Twinotify notification must continue to
drive the existing peer-dismiss logic exactly once.

## PB-001 — Preserve multiple messages and conversation fidelity

**Implementation:** Source complete in `41eada3`. See the
[design](superpowers/specs/2026-09-01-pb-001-conversation-fidelity-design.md)
and [implementation plan with evidence](superpowers/plans/2026-09-01-pb-001-conversation-fidelity.md).
Emulator instrumentation covers bounded 30-message history, two simultaneous
mirrors, repeated in-place updates, and independent cancellation. The required
paired-phone WhatsApp-like run remains pending.

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

**Implementation:** Source complete in `26cf7ad`. See the
[design](superpowers/specs/2026-09-01-pb-002-foreground-notification-design.md)
and [implementation plan with evidence](superpowers/plans/2026-09-01-pb-002-foreground-notification.md).
The emulator verifies truthful state presentation, private/ongoing notification
flags, a sanitized explicit immutable content intent, repeated taps into one
task, and the fail-closed own-package filter. Physical shade, lock-screen,
restart, tap, and paired-phone self-notification evidence remains pending.

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

**Implementation:** Done in `120931b`. See the
[design](superpowers/specs/2026-09-01-pb-003-private-history-design.md) and
[implementation plan with evidence](superpowers/plans/2026-09-01-pb-003-private-history.md).
Host tests and 12 emulator instrumented tests cover the Room v10 migration,
Keystore-protected content, age/row/byte bounds, grouping, pagination,
transactional clearing, and immediate content-retention disable behavior. The
release History screen and its destructive confirmation were also exercised.

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

**Implementation:** Source complete in `86549ba`. See the
[design](superpowers/specs/2026-09-01-pb-004-themed-launcher-icon-design.md) and
[implementation plan with evidence](superpowers/plans/2026-09-01-pb-004-themed-launcher-icon.md).
Asset checks, clean prebuild, generated density resources, and light/dark themed
rendering pass on `emulator-5558`. POCO F1 and MI 11X launcher captures remain
pending and are the only reason this item is not `DONE`.

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

**Implementation:** Source complete in `2af48fc`. See the
[design](superpowers/specs/2026-09-01-pb-006-open-in-twinotify-action-design.md)
and [implementation plan with evidence](superpowers/plans/2026-09-01-pb-006-open-in-twinotify-action.md).
Eight emulator instrumentation scenarios cover the explicit router activity,
opaque detail IDs, primary-tap preservation, bounded action count, unavailable
fallback, update, and cancel behavior. Layout on both target OEM notification
shades remains pending.

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

**Implementation:** Source/emulator scope complete in `7b5fd8d`. See the
[design](superpowers/specs/2026-09-01-pb-007-nontechnical-journey-audit-design.md),
[implementation plan](superpowers/plans/2026-09-01-pb-007-nontechnical-journey-audit.md),
and [numbered screenshot audit](audits/pb-007/README.md).

The audit fixed a critical nearby-pairing permission gap, corrected selection,
privacy, and battery-setting copy, enlarged/named the permission controls, and
verified a clean install through the Android **Nearby devices** prompt to a real
QR/waiting state. The pairing QR was not captured because it contains ephemeral
pairing material. Full two-phone success/recovery/unpair, TalkBack, one-handed
use, OEM background behavior, and the PB-005 relay path remain external evidence.

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

## PB-008 — Automatic transport recovery after process restart

**Implementation:** Source complete in `1b06246`. See the
[design](superpowers/specs/2026-09-01-pb-008-automatic-transport-recovery-design.md)
and [implementation plan with evidence](superpowers/plans/2026-09-01-pb-008-automatic-transport-recovery.md).
API 37 emulator runs cover signed in-place replacement, force-stop/foreground
recovery, exact persisted-state preservation, paused-state preservation, and
single-service idempotence. Signed upgrades, reboot, force-stop/open, OEM
auto-start/battery policy, and resumed delivery still need both physical phones.

**Problem:** Physical testing found that Twinotify can retain an enabled-looking
preference after a force-stop or package replacement while no transport is
running. Opening the app alone may leave it disconnected until the user changes
a setting. An enabled product must restore its service and route without this
manual wake-up.

**Scope:** Audit persisted mirroring intent, package-replaced/boot handling,
activity resume, foreground-service eligibility, and OEM background restrictions.
Define one idempotent recovery authority that starts only when the user has
enabled mirroring and all required permissions remain valid.

**Acceptance:**

- An in-place signed upgrade preserves pairing/data and resumes delivery after the next Android-permitted wake without toggling a preference.
- Opening Twinotify after force-stop reconciles enabled intent with actual service state and starts or explains the required recovery action.
- Reboot, process death, package replacement, and ordinary activity recreation do not create duplicate services or transport coordinators.
- A user-paused state remains paused across every lifecycle event.
- Tests cover persisted-intent truth, idempotent startup, permission loss, and OEM-denied background start; both physical phones cover upgrade, force-stop, and reboot.

## PB-009 — Truthful mirrored and latency metrics

**Implementation:** Source complete in `6bc1543`. See the
[design](superpowers/specs/2026-09-01-pb-009-truthful-delivery-metrics-design.md)
and [implementation plan with evidence](superpowers/plans/2026-09-01-pb-009-truthful-delivery-metrics.md).
Room/JVM coverage locks first-receipt deduplication, exclusion rules, local-day
bounds, last-ten latency, migration 10-to-11, and the distinction between no
latency evidence and a measured zero. Two physical phones must still reconcile
visible counts and latency against authenticated receipts.

**Problem:** The home screen remained at zero mirrored items and no latency data
after bidirectional notifications were physically delivered and acknowledged on
direct Wi-Fi. The metric therefore does not represent verified user delivery.

**Scope:** Trace notification delivery, peer custody receipts, duplicates,
internal controls, and day-boundary accounting into the existing metrics
surface. Choose and document the exact event that increments “Mirrored” and the
timestamps used for latency.

**Acceptance:**

- One logical notification increments the daily mirrored count exactly once after the documented delivery proof.
- Retry, duplicate receipt, snapshot reconciliation, control traffic, call state, and dismissal do not inflate notification counts.
- Bidirectional deliveries are attributed locally and survive process restart and UTC/local day transitions according to documented semantics.
- Latency uses monotonic-compatible or authenticated timestamps and never reports a fabricated value when required evidence is absent.
- JVM/Room tests cover deduplication and rollover; a two-phone run reconciles the visible count with sanitized sent markers.

## PB-010 — Accessible non-camera pairing fallback

**Problem:** “I already have a code” currently leads to the camera scanner. A
user who cannot grant camera access, aim the camera, or visually scan a QR has
no equivalent secure way to join a pair. Exposing the current opaque QR payload
as a manually typed value would be unusable and would encourage unsafe sharing.

**Owner decision required:** Approve the fallback product/security model:
whether it is a short-lived relay-brokered code, a system-mediated nearby/share
flow, or another reviewed transfer. Decide whether the relay may observe pairing
attempt metadata, the allowed validity/rate limits, and the exact user warning.

**Scope after unblock:** Add one progressively disclosed alternative beside QR
scanning, reuse the existing cryptographic confirmation/fingerprint step, and
ensure the QR and fallback routes have the same expiry, cancellation, replay,
and replacement guarantees.

**Non-goals:** No permanent recovery code, raw key/payload transcription,
account-based pairing, silent trust, or downgrade of the existing fingerprint
confirmation.

**Acceptance:**

- A user can join a pair without camera access or visual QR scanning.
- The fallback carries no less cryptographic assurance than the QR path and expires/cancels cleanly.
- Guessing, replay, enumeration, screenshots, clipboard exposure, and relay metadata are threat-modeled and bounded.
- Copy and assistive-technology behavior are tested with non-technical users on both physical phones.

## PB-011 — Android 17 local-network privacy migration

**Problem:** Twinotify targets SDK 36 today. Android 17 makes local-network
protection mandatory for apps targeting SDK 37+, and the product must choose
between a privacy-preserving system-mediated discovery path and requesting broad
`ACCESS_LOCAL_NETWORK` access before the target SDK is raised. Declaring that
permission early caused contradictory permission state during PB-007 and is now
guarded by a regression test.

**Owner decision required:** Approve whether nearby pairing/direct delivery
should use Android's system NSD picker where possible or request broad local
network access with an explicit rationale. Approve the privacy disclosure and
whether reduced automation/discovery is acceptable in exchange for narrower
access.

**Scope after unblock:** Prototype both viable paths against the current NSD,
pinned TLS, LAN-binding, recovery, and direct-delivery flows; write the security
and UX decision before adding `ACCESS_LOCAL_NETWORK` or raising target SDK to
37.

**Non-goals:** Do not add or request `ACCESS_LOCAL_NETWORK` while target SDK
remains 36. Do not weaken TLS pinning, pairing confirmation, route coordination,
or offline operation.

**Acceptance:**

- The approved Android 17 path is documented before the target SDK 37 change lands.
- Permission timing and copy state exactly what local access enables and offer a recoverable denial path.
- Nearby pairing and direct delivery retain their security and single-drainer invariants on Android 17.
- Upgrade and fresh-install tests cover grant, denial, revocation, and previously paired users without resetting identity or pairing state.

## Backlog maintenance

When work is approved, create one design/spec and one numbered implementation
plan per backlog item under `docs/superpowers/`. Link both here and update the
status. Do not combine PB-001/PB-003 storage and notification semantics with
PB-005 relay operations in one implementation change; their security and
rollback boundaries are independent. `SOURCE COMPLETE` items return to active
work only for the named physical evidence or a newly reproduced regression.
