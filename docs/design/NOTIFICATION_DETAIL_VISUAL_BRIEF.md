# Twinotify Notification Detail - Visual Proposal

Status: approved by the user on 2026-08-29

Date: 2026-08-29

## Decision

Use an edge-to-edge Material 3 reading screen called **The handoff receipt**.

This screen should feel like a native Android destination, not a notification card enlarged into a page. The source notification is presented as readable content on one continuous surface. The existing Twinotify A/handoff mark appears bare in the top app bar and supplies the one authored motion cue when an action is handed back to the other phone. Android Monet supplies every color role.

The screen does not redesign the rest of the app. It establishes a detail-screen pattern that the broader Material 3 rethink can reuse later.

## Approaches considered

### 1. The handoff receipt - recommended

One continuous reading surface, a compact source header, notification content with generous margins, and an action area anchored after the content. It is calm, direct, and recognizably Material 3 without becoming a stack of rounded cards.

Why it wins:

- The content remains the focus.
- It scales cleanly to long messages and 200 percent font size.
- The A/handoff mark has a real product role instead of decorative logo placement.
- Active, dismissed, pending, and failed states fit without changing the page skeleton.

### 2. Expanded Android notification

Recreate the system notification layout inside a large tonal container, with source icon, content, and actions grouped as a single card.

Why it loses:

- It duplicates Android System UI instead of adding useful context.
- It creates a kitchen-sink card once reply, status, source launch, and metadata are added.
- A large rounded container inside a full screen makes the screen feel like a component demo.

### 3. Conversation-first detail

Treat every notification like a message, with a large sender heading and reply composer permanently fixed to the bottom.

Why it loses:

- Many notifications are not conversations.
- Non-text actions such as Archive or Mark as read become awkward.
- A fixed composer is more likely to collide with the keyboard and large text.

## Phone wireframe

```text
┌────────────────────────────────────┐
│  ‹  Notification                    A  │  top app bar
│                                    │
│  [real app icon]  WhatsApp         │
│                   from MI 11X      │
│                   8:42 PM · Active   │
│                                    │
│  New message from Sam              │
│  Are we still meeting at 9?        │
│                                    │
│  Reply                             │
│  ┌────────────────────────────┐  │
│  │ Write a reply              │  │
│  └────────────────────────────┘  │
│  [ Send reply ]                    │
│                                    │
│  Archive                           │
│  [ Archive ]                       │
│                                    │
│  Open WhatsApp                     │  only if launchable
└────────────────────────────────────┘
```

At 200 percent font size, the source metadata stacks below the icon, action labels wrap, and every button stays at least 48 dp tall. Nothing uses a fixed content height.

## Layout and hierarchy

### Top app bar

- Edge-to-edge under system bars, with a 64 dp minimum app-bar height.
- Back is a 48 by 48 dp target using the app's established Material back affordance.
- Title is `Notification`, set in the existing title role.
- The existing Twinotify A/handoff mark sits bare at the trailing edge. No tile, circle, glow, gradient, or wordmark lockup.
- Hardware back and app-bar back perform the same real navigation action.

### Source header

- Use the real source app icon at 44 dp with a subtle image-only outline. Do not put it in a colored tile.
- App name uses title2. `from <device>` and the received time use body and caption roles.
- State is plain text in the metadata sentence: `Active`, `Dismissed`, or `No longer active`. It is not a chip or badge.
- On narrow or large-font layouts, metadata wraps below the app name. It never truncates the device label to protect layout.

### Notification content

- Use the page surface directly with 24 dp horizontal gutters, reduced to 16 dp at 360 dp width and below.
- Title uses title1, at most as many lines as the content requires. There is no forced one-line ellipsis.
- Body uses the normal body role. `bigText` follows as a separate paragraph only when it adds content not already shown in `text`.
- `subText` appears only when meaningful and does not repeat source metadata.
- No enclosing card, divider rule, quote marks, background glow, decorative grid, or clipped silhouette.
- The reading column has deliberate bottom space before actions so long content and action controls do not visually collide.

### Action area

- Show at most the three actions carried by the notification contract.
- Each action is a labeled section, not a chip. The action title is visible above its control when the control label alone would be ambiguous.
- Non-reply actions use the existing Material tonal button treatment at full available width. Actions are not paired as filled-primary plus outlined-secondary buttons.
- A reply action expands one inline composer under its label. The text field uses `surfaceContainerHigh`, `onSurface`, and a clear focus indicator in `primary`. It has a minimum 56 dp height and grows for wrapped text.
- `Send reply` is the only strong action while a reply composer is open. Other actions remain tonal and spatially separate.
- A pending action is disabled and keeps its slot. It reads `Sending…` beside or below the action label, not inside a pill.
- Terminal result copy replaces the pending copy in the same slot. The layout does not jump.
- Dismissed and gone details show no invokable controls. Their former action titles may remain as read-only context only if that helps explain a terminal result.

### Source app affordance

- Show `Open <app name>` only when the live native query says a launcher is available.
- Treat it as a quiet full-width text action after the notification actions, with a bare launch glyph if the existing Material icon set supplies one.
- If the app is missing, do not show a dead or disabled control and do not tell the user to install it.

## Typography

- Keep Android system type for this utility surface. The brand is carried by the A/handoff mark and behavior, not by importing another display font.
- Use existing roles: title1 for notification title, title2 for source app and action headings, body for content, caption for time and state.
- No uppercase eyebrow, letterspaced status label, monospace metadata, italic accent word, or multi-font display treatment.
- All text allows font scaling. Paragraphs use natural height and no clipping. At 200 percent, controls stack rather than squeeze side by side.

## Color and Material 3 roles

Use the complete Monet scheme already provided by `Theme.tsx`. The fixed Seam palette remains the non-Android and incomplete-dynamic-color fallback.

| Role | Use |
| --- | --- |
| `surface` | Page and system-bar continuity |
| `surfaceContainerLow` | Optional action grouping only when spacing is insufficient |
| `surfaceContainerHigh` | Reply field resting surface and pressed tonal states |
| `onSurface` | Titles, content, control labels |
| `onSurfaceVariant` | Device, timestamp, secondary content |
| `primary` / `onPrimary` | Send reply and focus state |
| `secondaryContainer` / `onSecondaryContainer` | Non-reply action controls |
| semantic `warn` | `Unconfirmed` |
| semantic `danger` | Failed, unavailable, and expired outcomes |

No fixed saturated brand color appears on this screen. No gradient, glow, glass, candy wash, cool blue-charcoal base, cream base, or default gray panel is introduced.

## Exact UX copy

### State

- Active: `Active`
- Cancelled: `Dismissed on one of your phones`
- Gone: `No longer active`

### Loading and unavailable

- Loading: `Loading notification`
- Missing or expired title: `This notification is no longer available`
- Missing or expired body: `Twinotify keeps dismissed notification details briefly, then removes them from this phone.`
- Load failure title: `Couldn’t load this notification`
- Load failure body: `Try again. Your notification content stays on your paired phones.`
- Retry action: `Try again`
- Exit action: `Back`

### Reply

- Composer label: `Reply`
- Placeholder: `Write a reply`
- Submit: `Send reply`
- Empty reply validation: `Write a reply first`
- Oversized reply validation: `Reply is too long`
- Locked result: `Unlock this phone to reply`

### Invocation status

- Pending: `Sending…`
- Dispatched: `Sent`
- Outcome unknown: `Unconfirmed`
- Failed: `Could not send`
- Action gone: `Action unavailable`
- Notification gone: `Notification unavailable`
- Expired: `Timed out`

`Sent` means Android dispatched the source PendingIntent. It does not claim server acceptance or app-level delivery.

## Interaction behavior

1. Load detail from the opaque route ID.
2. Query source-app launchability after detail loads and whenever the screen regains focus.
3. A reply action reveals its composer and moves accessibility focus to the field.
4. Submit calls the native shared invoker. On `queued`, re-read the detail immediately.
5. While any action is `PENDING`, refresh the detail once per second while the screen is focused, stopping when it becomes terminal or the two-minute product window ends.
6. On a terminal result, stop polling and announce the exact status through a polite live region.
7. If the detail becomes cancelled or gone, close the keyboard, disable invocation immediately, preserve readable content, and announce the state change.
8. `Open <app>` calls `openNotificationSourceApp(detailId)`, which resolves the package from the opaque local detail and rechecks launchability through native code at tap time. A failed launch removes the affordance on refresh rather than leaving a dead control.

Only one reply composer may be open at a time. A second reply action closes the first after preserving no unsent draft. Twinotify does not persist reply drafts.

## Motion

- Content is visible by default. There is no page-load fade, entrance translation, scroll reveal, or opacity-zero state.
- Pressed controls use the existing Material state layer. If a press scale is added, it is exactly 0.96 and never moves the control vertically.
- The one authored motion is the A/handoff mark's two halves closing inward by 2 dp and settling when an invocation becomes queued. Duration is 180 ms with no bounce. The `Sending…` label appears immediately, so motion is never the only feedback.
- Composer expansion may use a short, interruptible layout transition. With reduced motion enabled, it appears immediately.
- No looping, floating, glowing, pulsing, parallax, or decorative motion.

## Accessibility

- Every target is at least 48 by 48 dp.
- Back, source launch, reply field, send, and every notification action have explicit accessible names and hints where the result is not obvious.
- State and result changes use polite live regions. Errors also remain as visible text.
- Color is never the sole state signal.
- Reading order is top app bar, source, state and time, title, body, actions, source launch.
- At 200 percent font size, the layout is one column with natural heights. No text, icon, focus ring, or control may touch or be clipped by an edge.
- RTL mirrors navigation and action layout. Source package names remain directionally isolated as data.
- The keyboard never covers the active reply field or submit action.
- App icons are decorative when the adjacent app name is present.

## Loading, error, and lifecycle states

### Loading

Show the app bar immediately, then a standard inline progress indicator and `Loading notification`. Do not skeletonize notification content or reserve fake rows.

### Missing or expired

Show the bare A/handoff mark, unavailable title, explanatory body, and one `Back` action. Do not show an empty notification card or retry a known missing ID.

### Recoverable load failure

Keep the app bar and show `Try again` plus `Back` as vertically separated actions, not a filled-and-outlined pair.

### Cancelled

Keep cached content readable. Show `Dismissed on one of your phones` near the source metadata. Remove action controls.

### Gone

Keep cached content readable when available. Show `No longer active`. Remove action controls and stop result polling.

## Component boundaries for implementation

- `NotificationDetailScreen`: owns route loading, focus refresh, polling, keyboard behavior, and top-level states.
- `NotificationSourceHeader`: source icon, app, device, time, and state.
- `NotificationBody`: title, text, big text, and subtext de-duplication.
- `NotificationActions`: action list, exactly one open reply composer, invocation calls, and status announcements.
- `NotificationActionStatus`: closed mapping from native invocation state to safe user copy.
- `HandoffMotionMark`: existing mark with the approved two-part queued transition and reduced-motion fallback.

All components use existing theme tokens and `Tw*` behavior. A new text-field primitive is allowed only if it is reusable, accessible, and Material-state driven. Do not add Tailwind or a new component framework for this screen.

## Verification requirements

Before this design can ship, verify all of these on the emulator and both physical phones:

- Light, dark, and at least two materially different Monet palettes.
- 320 dp width and standard phone width.
- 100 and 200 percent font scale.
- Loading, missing, recoverable error, active, cancelled, and gone states.
- Reply, non-reply, pending, every terminal result, locked device, invalid reply, and launch-source-app behavior.
- Hardware back, app-bar back, keyboard back, rotation lock behavior, TalkBack order, live-region announcements, and reduced motion.
- Every visible control with a real tap. No dead controls.

## Anti-slop proposal audit

- No gradient, glow, glass, grain over content, grid, floating card, fake window, icon tile, logo tile, eyebrow pill, metadata pills, decorative quote mark, accent bar, countdown, testimonial, pricing layout, or pre-footer pattern.
- No generic split hero, hero stack, label-over-heading section, numbered rail, comparison grid, or card-grid composition.
- No cream, UI-kit gray, blue-purple, cool blue-charcoal, pastel candy, or sprayed-on saturated accent. Monet roles remain tonal and coherent.
- No Google display font or fashionable substitute is introduced. System type stays a neutral Android workhorse; identity comes from the existing A/handoff mark.
- No default all-around shadow, fake offset shadow, bloom, hard color seam, unrounded decorative rule, cut-off glow, clipped content, or edge-jammed text.
- Real source app icons are used bare and only when available. No fake logos, initials avatars, or invented integration marks.
- Buttons do not lift on hover, pair filled with outlined by default, animate underlines, or use glowy pill styling.
- Content is visible by default. Motion is user-triggered, interruptible, reduced-motion aware, and paired with static text.
- Centering, optical alignment, focus rings, gutters, large-font reflow, and clipped edges must be verified in rendered screenshots before completion.
- The screen has one specific product signature: the existing A/handoff mark confirms an actual cross-phone action. It is functional, restrained, and cannot be transplanted unchanged into an unrelated app.

This audit is a proposal check only. The implemented screen still requires the promised full point-by-point anti-slop audit against rendered light, dark, narrow, and 200 percent font states before it can be called complete.
