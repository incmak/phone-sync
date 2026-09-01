# PB-006 — Secondary Open in Twinotify action design

Status: approved by the owner's 2026-09-01 instruction to complete all locally unblocked backlog work.

## Goal

Give a mirrored notification a secondary path to its local Twinotify detail while preserving the primary tap's source-app semantics and the source notification's own actions.

## Scope

- Reuse the existing opaque UUID `detailId`, explicit `NotificationRouterActivity`, and local detail screen.
- Add an immutable activity `PendingIntent` titled “Open in Twinotify” when the detail ID is valid and fewer than three source actions are currently visible.
- Keep source actions first and cap the Android notification at three total actions. If all three slots are occupied by source actions, omit the Twinotify action instead of hiding or reordering source capabilities.
- Give the secondary intent a distinct sanitized URI mode that instructs the router to skip source-app launch and open Twinotify's local detail route directly.
- Missing or expired cache data continues to render the detail screen's truthful unavailable state.

## Non-goals

- No change to the primary notification tap, auto-cancel behavior, source action execution, inline reply, notification content, protocol, or Room schema.
- No package name, canonical ID, peer ID, relay data, notification content, or package-controlled deep link enters the secondary intent.
- No claim about action layout on POCO F1 or MI 11X until physical shade captures can be recorded.

## Acceptance criteria

1. Primary content intent remains unchanged and continues to prefer the locally installed source app.
2. With a valid detail ID and an available slot, the final action is “Open in Twinotify”, explicit, immutable, and distinct from the primary pending intent.
3. The secondary route bypasses source-app launch and opens only the opaque local detail route.
4. Invalid detail IDs do not create either navigation pending intent.
5. At most three actions are emitted, source actions keep priority/order, and pending-action suppression still works.
6. JVM and Android notification tests pass; physical OEM notification-shade layout remains explicitly pending.
