# PB-001 Conversation Fidelity Implementation Plan

**Design:** `docs/superpowers/specs/2026-09-01-pb-001-conversation-fidelity-design.md`

**Status:** Source and emulator verification complete; physical two-phone observation deferred

## Task 1: Prove identity and payload gaps

- [x] Add JVM tests proving two canonical IDs/local mirror tags coexist and three updates retain one identity.
- [x] Add RED notification-payload tests for ordered, bounded conversation parsing and malformed input.
- [x] Add RED Android tests that capture MessagingStyle arrays and build a MessagingStyle mirror.
- [x] Run focused tests and record the expected failures before implementation.

## Task 2: Capture and encode bounded conversation state

- [x] Add immutable conversation/message snapshot types at the notification-listener boundary.
- [x] Extract stable Android conversation metadata and bounded message arrays without retaining framework callback objects.
- [x] Extend notification JSON encoding/decoding compatibly and keep the fallback payload shape valid.
- [x] Run focused JVM and Android tests.

## Task 3: Render without changing canonical semantics

- [x] Render valid conversations with `NotificationCompat.MessagingStyle`.
- [x] Preserve BigText fallback, primary tap behavior, actions, visibility, and canonical `(tag, id)` posting.
- [x] Run reducer, materializer, snapshot, dismissal, and notification-builder regression tests.

## Task 4: Verify

- [x] Run focused core JVM tests, Android-test assembly, and instrumented tests on the emulator. Full module lint/test/assemble remains part of the final backlog gate.
- [x] Post two distinct fixture notifications, update one three times, dismiss each independently, and inspect active-notification state.
- [x] Record physical two-phone WhatsApp-like evidence as pending rather than claiming it from the emulator.
- [x] Review the final diff for protocol privacy, payload bounds, identity drift, and unrelated changes.

## Evidence

- RED: focused JVM compilation failed on the intentionally missing conversation model before implementation.
- JVM: `NotifPostJsonTest` and `NotificationStateReducerTest` pass, followed by listener/reducer/materializer/reliable-pipeline/snapshot regressions.
- Android build: `:twinotify-core:assembleDebugAndroidTest` passes.
- Emulator API 37: `NotifPostBuilderAndroidTest` and `MirrorActionNotificationTest` pass (6 tests), including bounded 30-message capture, two simultaneous mirrors, an in-place three-message update, and independent cancellation.
- Pending physical evidence: two real paired phones with WhatsApp-like notifications, as required by the product backlog's physical-observation rule.
