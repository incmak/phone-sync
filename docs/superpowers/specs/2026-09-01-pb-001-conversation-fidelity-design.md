# PB-001 Conversation Fidelity Design

**Date:** 2026-09-01

**Status:** Approved by the owner's instruction to execute every unblocked product-backlog item

**Scope:** Android notification capture, encrypted notification payloads, desired-state reduction, mirrored Android presentation, and dismissal identity

**Depends on:** reliable-delivery v2, Room version 9, and the existing canonical notification reducer

## Goal

Keep independently visible source notifications independently visible on the peer, while updates to one Android notification converge in place and messaging notifications retain a bounded sender-ordered conversation transcript.

## Root cause

Twinotify already derives one canonical identity from the source device, package, Android notification ID, and tag. The reducer also allocates a durable `(tag, id)` pair per canonical identity, so two Android notifications with distinct source identities do not intentionally share a mirror identity.

The lost fidelity is in the payload and renderer: capture reduces `Notification.EXTRA_MESSAGES` to the last message text, and `MirrorPoster` always builds `BigTextStyle`. A later conversation update therefore replaces the earlier visible text without reconstructing the notification's message history.

## Design

- Keep canonical identity, sequence allocation, reducer semantics, and mirror-local identity unchanged.
- Extend the version-1 notification payload compatibly with an optional `conversation` object. Older clients continue to ignore the added field.
- A conversation contains only a stable source conversation key when Android provides `shortcutId`, an optional conversation title, the group-conversation flag, and at most 25 messages.
- Capture historic messages before current messages, preserve source array order, remove exact duplicate entries, and retain the newest 25 entries. Each message carries bounded text, timestamp, and an optional bounded sender name and sender key.
- Do not synthesize a stable conversation key from a title or message content. Missing trustworthy metadata remains missing.
- Render a valid conversation with `NotificationCompat.MessagingStyle`; otherwise retain the current BigText fallback.
- Use the existing canonical tag/ID for posting and cancellation. Conversation metadata never participates in notification identity.
- Treat malformed or oversized optional conversation data as invalid at the Kotlin payload boundary. The authenticated envelope is still required before parsing.

## Privacy and limits

Conversation content remains inside the existing end-to-end-encrypted payload. The relay sees no new plaintext. Limits are 25 messages, 4,096 Unicode code points per message, 256 per sender name, 256 per sender key, 512 per conversation key, and 256 per conversation title. No intents, URIs, avatars, phone numbers outside display text, or package-controlled deep links are copied.

## Non-goals

- No change to ordered desired-state convergence, retry, snapshot, supersession, or receipt behavior.
- No notification bundling across canonical identities.
- No inference that two notifications belong to one conversation when Android does not provide a stable key.
- No history database or clear-history UI; PB-003 owns retained local history.
- No change to primary or secondary notification actions.

## Acceptance mapping

- Distinct source identities retain distinct canonical and local mirror identities.
- Repeated updates retain one canonical/local identity and only advance its sequence/content.
- MessagingStyle parsing and rendering preserve the bounded source order.
- Cancellation remains scoped to the canonical identity.
- Existing reducer and snapshot tests continue proving stale work cannot resurrect superseded content.
- JVM payload/reducer tests, Android builder tests, and emulator notification fixtures cover distinct post, update, and dismiss behavior. Physical two-phone evidence remains explicitly pending.

