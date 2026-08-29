# Twinotify Material 3 Home and Identity Design

**Date:** 2026-08-29  
**Status:** Approved visual direction, implementation pending  
**Scope:** Android home screen, shared Twinotify identity, launcher assets, and Android notification icon treatment

## Objective

Replace the current static home screen with a trustworthy live control center. The screen must tell the user whether mirroring is active, how the two phones are connected, what recently happened, and what action is available when delivery needs help. It must use Material 3 hierarchy and Android Monet dynamic color without becoming a generic Material sample.

The approved identity is **The Seam**: two notification surfaces joined by one continuous handoff line. The brand fallback palette remains mineral green. On supported Android devices, semantic colors follow the user's wallpaper through Monet.

## Product Boundaries

This work includes:

- A complete home-screen information architecture and visual rewrite.
- A real, privacy-bounded recent-activity feed.
- Material 3 color, typography, shape, state-layer, and touch-target roles needed by the home screen.
- Automatic Android Monet support with light and dark schemes.
- A custom Twinotify mark and revised wordmark treatment.
- Adaptive, legacy, monochrome, and splash launcher assets.
- Purpose-built Android small-notification icons for mirrored notifications, the foreground sync service, and mirrored calls.
- Source-app artwork in mirrored notifications and the activity feed when available.

This work does not redesign onboarding, pairing, filters, or settings. Those screens inherit the revised shared theme and wordmark but retain their current information architecture. It does not add notification message content to the activity feed, a user-selectable theme picker, remote analytics, or a cloud activity history.

## Design Direction

### The Seam

The Twinotify mark is a bare, custom vector. Two offset notification outlines meet at a central seam; one vertical stroke makes the handoff continuous. It appears without a colored tile in the app bar and wordmark. The launcher icon uses the same mark inside Android's required adaptive-icon canvas. The monochrome adaptive layer allows Android's themed icons to apply Monet at launcher level.

The signature home artifact is the live handoff trace between two phone endpoints. It communicates route state through geometry, not decoration:

- Direct: one uninterrupted line.
- Relay: one intentional waypoint.
- Reconnecting: a visible gap that quietly moves when reduced motion is off.
- Queued: a holding bay and exact queue count.
- Paused: a closed gate.
- Unpaired: separated endpoints.

No content begins hidden. Motion only changes already-visible route geometry, remains interruptible, and becomes static when the system requests reduced motion.

### Material 3, not a Material template

Material 3 supplies the behavior and semantic system:

- Color roles instead of fixed component colors.
- System typography and scalable type roles.
- Large, medium, and small shape roles with concentric nesting.
- 48 dp minimum interactive targets.
- State layers for pressed, focused, selected, and disabled feedback.
- The 52 by 32 dp Material switch geometry.

Twinotify supplies the identity:

- The Seam mark and handoff trace.
- Product-specific route geometry and copy.
- A disciplined mineral-green fallback scheme.
- A single, operational control-center composition rather than a stack of interchangeable cards.

Material Symbols may be used for conventional Android actions such as Settings. They remain bare and use one optical weight. Product identity and route communication never use generic icon-pack glyphs.

## Home Information Architecture

The screen is one vertically scrollable control center in this order:

1. **Top app bar**: Seam mark, `twinotify` wordmark, and one 48 dp Settings icon button.
2. **Connection surface**: route label, one-sentence explanation, master mirroring switch, live handoff trace, paired-phone identity, reachability, and the one recovery action when required.
3. **Operational measure row**: mirrored today, typical delivery latency, and filtered today. Values remain aligned at 200% font scale; narrow screens wrap the row into a deliberate two-row layout rather than compressing text.
4. **Recent activity**: up to five newest local activity items plus a `See all` action only when more than five exist.
5. **App filter action**: a Material list action named `Choose mirrored apps`.

The connection surface is the only large tonal container. Metrics and activity use spacing and typography instead of independent bordered cards. This avoids a dashboard made of repeated boxes.

## Home State Contract

The existing `presentRoute` function remains the single source of user-facing route truth. The redesign must preserve its six states and never infer relay failure when LAN is healthy.

| State | Headline | Trace | Control and action |
| --- | --- | --- | --- |
| Direct | Direct on Wi-Fi | Continuous | Switch on; no action |
| Relay | Via relay | One waypoint | Switch on; no action |
| Reconnecting | Reconnecting | Recovering gap | Switch on; automatic retry, no button |
| Queued | Queued for delivery | Holding bay | Switch on; exact count and `Try again now` |
| Paused | Paused | Closed gate | Switch off; no secondary button |
| Unpaired | Not paired | Separated endpoints | Switch disabled; `Link your other phone` |

State transitions reuse the same spatial structure. Text, route geometry, semantic color role, and available action change without moving the master switch or replacing the entire surface.

## Material 3 and Monet Color Architecture

The theme changes from named paint values such as `fill` and `accent` to semantic Material roles:

- `primary`, `onPrimary`, `primaryContainer`, `onPrimaryContainer`
- `secondary`, `onSecondary`, `secondaryContainer`, `onSecondaryContainer`
- `tertiary`, `onTertiary`, `tertiaryContainer`, `onTertiaryContainer`
- `surface`, `surfaceDim`, `surfaceBright`, and the surface-container ladder
- `onSurface`, `onSurfaceVariant`, `outline`, `outlineVariant`
- `error`, `onError`, `errorContainer`, `onErrorContainer`
- `inverseSurface`, `inverseOnSurface`, `inversePrimary`, and `scrim`

On Android, the theme resolves these roles from the platform's wallpaper-derived accent and neutral tonal palettes. The scheme updates on light/dark changes and after the activity is recreated following a wallpaper palette change. If a platform role cannot be resolved, the complete fixed Seam light or dark scheme is used. Theme consumers never receive a mixture of dynamic and fallback roles.

The Seam mark uses `onSurface` in the app bar, so it remains recognizable without fighting the user's palette. Healthy interactive emphasis uses `primary`; containers use surface or primary-container roles. Route warnings use semantic roles, not a fixed saturated brand hue.

The implementation keeps the current `ThemeProvider` boundary and existing primitive library. It does not add a second component system. Shared primitives are revised to consume Material roles, which lets unchanged secondary screens inherit the new scheme safely.

## Typography and Shape

Android's system sans is the neutral UI voice. Identity comes from the Seam mark and layout rather than a downloaded display font. Type maps to Material roles:

- Route headline: display small.
- Section titles: title medium.
- Device and activity titles: body large or label large depending on control semantics.
- Explanations: body medium.
- Metrics: title large with tabular alignment where Android supports it.
- Supporting metadata: body small.

The launcher and notification mark remain vector geometry, not type glyphs.

Shape roles are limited and functional:

- Connection surface: extra large.
- App-filter list action: large.
- Source-app artwork: medium, matching the app icon's own mask where possible.
- Icon buttons and switch track: full.

Nested radii follow the outer-radius-equals-inner-radius-plus-padding rule. There are no default all-around shadows. Tonal elevation separates the one connection surface from the page.

## Recent Activity Data Contract

### Privacy

Recent activity is local-only. It stores and returns only:

- A random event ID and optional protocol message ID.
- Direction: sent or received.
- Kind: mirror or dismissal.
- Status: queued, delivered, applied, expired, or rejected.
- Optional custody route: direct or relay.
- Source package name and source app label.
- Event timestamp.

It never stores or returns notification title, body, expanded text, contact name, conversation text, nonce, ciphertext, device identifier, canonical notification identifier, or raw failure detail.

### Storage

A dedicated `ui_activity_event` Room table isolates presentation history from the protocol's existing `activity_event` terminal journal. Reusing the protocol journal would couple UI reads to custody transitions and cannot represent inbound activity safely.

The table is added with an explicit migration from Room 7 to 8 and a committed schema export. Inserts and updates are idempotent by stable event ID or message ID. The journal caps itself at 500 rows and retains no more than 30 days. Unpair clears it with every other paired-state table.

### Writes

- Durable outbound notification capture inserts a queued `mirror/sent` event in the same transaction as its outbound message.
- Authenticated custody and peer receipts update that event's route and terminal status.
- Authenticated inbound notification materialization inserts an applied `mirror/received` event only after the platform post succeeds.
- Authenticated local or peer dismissal inserts an applied `dismissal/sent` or `dismissal/received` event after the state transition commits.
- Replay, duplicate delivery, snapshot recovery, and process restart do not create duplicate rows.
- Filtered notifications affect the existing daily metric only; they do not create per-app activity rows.

### Native and TypeScript API

`TwinotifyCoreModule` exposes `getRecentActivity(limit)` with a hard native maximum of 20. Returned objects contain the privacy-bounded fields above plus an optional small source-app artwork data URI generated locally at read time. Artwork is never persisted in the UI journal.

`useRecentActivity` refreshes immediately on screen focus and at the same bounded cadence as home metrics. A failed refresh keeps the last successful snapshot. A first-load failure shows `Activity unavailable` with a retry action; a genuine empty result shows `No activity yet` and explains that the first mirrored notification will appear there.

## Brand and Icon Pipeline

### Shared in-app identity

`TwinotifyMark` is a reusable `react-native-svg` component using `currentColor`. `TwWordmark` composes the bare mark with the word `twinotify`, with optical alignment verified at every shipped size. The mark may appear alone only where the surrounding UI already names Twinotify.

### Launcher and splash

The asset set contains:

- Full legacy launcher PNG.
- Adaptive foreground PNG with safe-zone compliance.
- Adaptive background in the fixed Seam fallback tone.
- Monochrome adaptive layer for Android themed icons.
- Splash mark on a tonal surface.

Expo configuration references all four launcher roles explicitly, including `monochromeImage`. Template Expo assets and unused React sample imagery are removed only after reference checks prove they are unused.

### Android notifications

The native module includes a white-on-transparent vector resource named `ic_stat_twinotify`. It replaces the generic Android information and Bluetooth icons in:

- Mirrored notifications.
- The foreground sync-service notification.
- Mirrored call-state notifications.

Android requires the small notification icon to be an app resource, so a remote source bitmap cannot occupy that slot. Twinotify's monochrome mark therefore owns the status-bar position. The captured source app's large icon remains preferred; when absent, the captured source small icon becomes large notification artwork after safe decoding and scaling. If neither is available, the notification ships without fabricated artwork.

Notification color uses a resolved Material primary role without colorizing the full notification. Call notifications retain their current privacy-bounded generic call content and action-free behavior.

## Components

The home screen is decomposed into focused units:

- `TwinotifyMark`: brand vector only.
- `HomeTopAppBar`: wordmark and Settings action.
- `ConnectionSurface`: route presentation, switch, trace, peer, and recovery action.
- `HomeMetrics`: responsive operational values.
- `RecentActivitySection`: loading, error, empty, and populated states.
- `RecentActivityRow`: source artwork, privacy-safe event copy, and relative time.
- `HomeFilterAction`: navigation to the existing filter screen.
- `useRecentActivity`: focus-aware native data refresh.

`home.tsx` orchestrates state and navigation. It does not contain activity formatting, brand SVG paths, or raw Material color mapping.

## Error and Empty States

- Missing pair data uses `Unknown device` without exposing a device ID fragment.
- Unavailable source artwork uses the bare Seam source glyph, not initials inside a gradient or a fake app logo.
- Zero latency displays `No data`.
- Empty activity says: `No activity yet. Your first mirrored notification will appear here.`
- Activity read failure says: `Activity unavailable` and offers `Try again`.
- Toggle failure rolls the control back and retains the existing explicit error alert.
- Route retry and pairing retain their existing native calls and navigation paths.

## Accessibility

- Every interactive target is at least 48 by 48 dp.
- The connection surface exposes one concise live-region status sentence; the decorative trace is hidden from accessibility services.
- The mirroring switch has a stable label and state.
- Activity rows are accessibility text, not buttons, unless a later detail screen gives them an action.
- Source app artwork is decorative when the app name is present in text.
- Layout is verified at font scales 0.85, 1.0, and 2.0 on both physical phone sizes.
- Text contrast is verified for fixed light/dark schemes and representative Monet palettes.
- Reduced motion removes trace movement without removing state geometry.
- All ordinary content remains fully visible when animation does not run.

## Testing and Acceptance

### Automated

- Theme-role tests cover full dynamic-scheme success, all-or-nothing fallback, and light/dark mapping.
- Component tests cover all six route states, stable layout roles, 48 dp targets, activity loading/error/empty/populated states, and no notification content rendered.
- Native unit tests cover the UI activity journal's cap, retention, idempotency, privacy fields, terminal updates, and unpair clearing.
- Migration tests cover 7 to 8 and preserve existing paired state.
- Notification-builder tests assert the Twinotify resource for mirror, service, and call notifications and the source-artwork fallback order.
- Expo configuration tests assert foreground, background, monochrome, and splash asset wiring.
- Existing TypeScript, lint, Kotlin lint, JVM, and Android assembly gates remain mandatory.

### Physical-device matrix

On both the Mi 11X and POCO F1 release build:

1. Verify launcher icons in normal and themed-icon modes.
2. Verify Seam fallback or resolved Monet palette in light and dark modes.
3. Change wallpaper palette, recreate the app, and verify role remapping without mixed old/new colors.
4. Verify all six route-state presentations where safely inducible.
5. Send one notification each direction and verify matching privacy-safe activity rows.
6. Dismiss each direction and verify one idempotent dismissal row.
7. Verify the status-bar, shade, foreground-service, and mirrored-call small icons.
8. Verify source artwork preference and no fabricated icon when source artwork is unavailable.
9. Verify every control by physical tap.
10. Verify scroll reachability and absence of clipping at 0.85, 1.0, and 2.0 font scales in light and dark modes.
11. Re-run the complete anti-slop audit point by point against screenshots and the implemented source.

Physical call verification remains dependent on the user's controlled real-call step. Protected release signing remains dependent on owner-held credentials.

## Acceptance Criteria

The work is complete only when:

- The home screen never shows the hardcoded `No mirrors yet` placeholder after real activity exists.
- Route truth and mirroring controls retain their current behavior.
- Recent activity is real, bounded, local-only, content-free, and idempotent.
- Material roles and Monet recolor the entire home surface coherently in light and dark modes.
- The Seam mark replaces Expo template branding everywhere in scope.
- Generic Android information and Bluetooth notification icons are absent from production notification builders.
- Both physical phones pass the visual, interaction, icon, delivery, dismissal, restart, and accessibility checks that do not require owner-only credentials or real-call participation.
- No anti-slop finding remains in the shipped surface.
