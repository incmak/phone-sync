# Plan 025: Preserve capture reconciliation wakeups

**Status:** DONE

## Problem

`TwinotifyNotificationListener` owns a process-local `reconciliationRunning`
boolean while `CaptureCoordinator` owns the overflow latch and generation. A
new overflow can arrive after a recovery pass clears its generation but before
the old coroutine clears `reconciliationRunning`. The callback observes a
running worker and returns, then the worker retires without rechecking. That
overflow remains parked until an unrelated later callback or listener rebind.

The existing listener test reads private production source text and asserts
identifier order. It does not execute this lifecycle boundary.

## Task 1: Capture the handoff race as a deterministic RED

- Add a production-used reconciliation request gate with a generation lease.
- Add a focused test that pauses after the first pass has completed but before
  worker retirement, requests reconciliation again, and requires exactly one
  follow-up pass without an external trigger.
- Temporarily leave the old listener boolean path in place so the new behavior
  test fails before the production wiring changes.
- Remove the brittle reconciliation source-order test only when equivalent
  behavioral coverage is present.

## Task 2: Make worker retirement atomic

- Route every listener reconciliation request through the gate.
- Coalesce requests arriving during an active pass into one follow-up pass.
- Retire only the matching worker lease so stale cleanup cannot erase a
  successor worker.
- Preserve exact `CancellationException` identity and existing bounded logging.
- Do not change capture payload, pairing, Room, notification, or route behavior.

## Task 3: Verify and review

- Run the focused listener/coordinator tests.
- Run native lint, all JVM tests, Android-test Kotlin compilation, and debug APK
  assembly.
- Run `git diff --check` and audit the exact owned diff.
- Obtain fresh independent review before commit.
- Re-run the whole-range source audit and the applicable anti-slop/UI audit.

## Acceptance

- The exact after-pass/before-retirement race cannot lose a new request.
- Multiple active-pass requests coalesce into one follow-up.
- Stale worker cleanup cannot clear a successor lease.
- No test depends on private listener identifier ordering.
- Focused and full native gates are green.
