# PB-006 — Secondary Open in Twinotify action implementation plan

1. Add failing router and Android notification tests for direct-Twinotify routing, immutable/distinct pending intents, invalid IDs, source-action priority, and the three-action cap.
2. Add a validated route-URI helper and a direct-Twinotify mode to the existing explicit router activity.
3. Append the localized secondary action only when a valid detail exists and the bounded action budget has room.
4. Run focused/full JVM and instrumented suites, Android assembly, and mobile verification gates.
5. Complete the notification-action visual/interaction checklist and record emulator/source evidence while leaving OEM shade layout pending.

## Evidence

- TDD red observed: `NotificationRouterActivityTest` failed to compile because the direct-Twinotify route mode did not exist.
- Focused JVM/router test and Android-test compilation passed after implementation.
- Full Android gate passed: `:twinotify-core:testDebugUnitTest :twinotify-core:assembleDebugAndroidTest :app:assembleDebug` (727 tasks, 2026-09-01).
- Final module lint passed: `:twinotify-core:lintDebug`.
- Emulator `emulator-5558` passed all 8 `MirrorActionNotificationTest` scenarios, including a real `PendingIntent.send()` launch observed by an activity monitor for the explicit router activity.
- The emulator was restored to the normal Twinotify release APK after the isolated instrumentation run. No command targeted `emulator-5554`.

## Final interface review

- Copy and hierarchy: the only new visible copy is the short localized label “Open in Twinotify”; it follows the source notification's actions and does not compete with the primary tap.
- Controls and interaction: every emitted action is executable; the new action's real Android pending intent was dispatched on the emulator and reached the explicit router activity. The primary tap retains source-app preference.
- Density and layout: Android receives at most three actions. Source actions keep their order and priority, and the Twinotify action is omitted rather than crowding a full action row.
- Safety and privacy: both navigation intents require a validated opaque UUID. The new intent is immutable, authenticated, explicit, and contains no source package, canonical ID, peer metadata, or notification content.
- Visual consistency: the action uses Android's native notification action treatment and the existing Twinotify status icon. No custom card, pill, glow, font, animation, clipping, or parallel layout was introduced.
- State coverage: tests cover zero, one, two, and three visible source-action states, pending-action suppression, conversations, invalid detail IDs, source-launch bypass, and unavailable fallback behavior.
- Responsive/theme coverage: sizing, contrast, touch treatment, dark mode, font scaling, and localization layout remain SystemUI-owned. Source structure is bounded, but POCO F1 and MI 11X shade captures remain pending because OEM rendering cannot be established on the emulator.
