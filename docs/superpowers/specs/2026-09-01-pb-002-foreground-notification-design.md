# PB-002 Foreground Notification Truth and Navigation Design

**Status:** Approved for implementation by the owner's 2026-09-01 backlog directive

## Goal

Make Twinotify's foreground-service notification use the same delivery truth shown on Home, open the existing app task safely, and preserve the existing fail-closed rule that Twinotify never mirrors its own notifications.

## Scope

- Move the enabled/paired delivery copy into a native presenter over `SyncRouteStatus`.
- Include that native presentation in the public route-status payload so Home consumes the same title, explanation, custody counts, and peer-evidence interpretation.
- Render the foreground notification from the native presentation instead of `SyncHealth.service` and the hard-coded “Twinotify active” title.
- Add an explicit, immutable launcher `PendingIntent` whose sanitized intent contains no product or transport data and reuses the existing activity task.
- Centralize the outbound self-package predicate and use it in live capture and active-notification reconciliation.
- Add JVM, TypeScript, and emulator instrumentation coverage. Record real-phone/OEM-only observations as deferred.

## Non-goals

- No new foreground-notification actions, channels, colors, branding, or custom layout.
- No changes to source-notification primary tap behavior or peer-dismiss semantics.
- No network endpoint, peer identifier, notification content, token, or diagnostic exception may enter the route presentation or launcher intent.
- No physical-phone claim will be inferred from emulator behavior.

## Presentation contract

The presenter returns a state, label, one-sentence explanation, optional recovery action, queued count, and optional peer-evidence line. Authenticated LAN is “Direct on Wi-Fi.” Authenticated relay is “Via relay,” but relay-socket availability alone never claims that the peer is reachable. Pending-local and relay-held custody remain distinct. Idle is “Stopped,” disabled is “Paused,” and all remaining active phases are reconnecting or locally queued.

The native route event carries the presentation. Home applies only its authoritative unpaired/disabled overrides; for an enabled paired session it renders the native presentation directly. A compatibility fallback remains for an older native bundle, but current production paths have one presenter.

## Launcher intent contract

The notification obtains the package launcher intent, requires an explicit component in Twinotify's own package, removes data, clip data, selector, and extras, and applies `CLEAR_TOP | SINGLE_TOP`. The `PendingIntent` is immutable and update-current. It carries no deep link; the existing launcher/home routing decides the visible screen and Android reuses the existing task.

## Self-filter contract

One pure predicate decides whether a posted notification is eligible for outbound capture: source package must differ from Twinotify's package. Live callbacks and both reconciliation snapshots use that predicate. Own-package removal handling is intentionally unchanged so a locally dismissed mirror still emits exactly the existing peer-cancel behavior.

## Acceptance evidence

- JVM truth-table tests cover direct, relay custody variants, queued, reconnecting, paused, stopped, and unpaired states.
- TypeScript tests prove Home prefers the native presentation and retains safe lifecycle overrides/fallback.
- Intent tests prove explicit own-package targeting, task reuse flags, immutability flags, and absence of sensitive payload fields.
- Emulator instrumentation posts the foreground notification, inspects privacy/ongoing/content-intent properties, sends the intent, and verifies the app task opens without duplication where the generated launcher is available.
- Self-filter tests cover live and reconciliation paths; physical two-phone/OEM tap, lock-screen, restart, and self-mirroring observations remain pending.
