# PB-008 Automatic Transport Recovery Design

**Status:** Approved for implementation by the owner's 2026-09-01 backlog directive

## Goal

Restore an enabled Twinotify transport after process death, app launch, boot, or an in-place package replacement without asking the user to toggle mirroring, while preserving an explicit paused state and explaining permission or platform blocks.

## Scope

- Introduce one process-wide recovery authority used by app foreground, boot, package replacement, and the existing retry control.
- Reconcile the durable `ServiceConfig.enabled` intent, peer/route availability, notification-listener access, notification-post availability, and the live service instance before starting anything.
- Serialize and briefly fence start requests so concurrent lifecycle callbacks cannot create duplicate service starts or transport coordinators.
- Handle `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` asynchronously with `BroadcastReceiver.goAsync()` rather than blocking the receiver thread.
- Publish durable enabled intent and a bounded recovery issue to the existing native status surfaces so Home can distinguish “paused” from “enabled but needs attention.”
- Reuse the existing permission explanation screen for a permission-blocked recovery action.
- Add JVM, TypeScript, source-contract, and emulator instrumentation coverage. Keep physical OEM evidence explicitly deferred.

## Non-goals

- Twinotify cannot restart itself while Android keeps the package force-stopped. The next explicit app launch is the supported recovery wake.
- Do not bypass Android foreground-service restrictions, battery controls, or notification permissions.
- Do not change pairing, transport leasing, retry backoff, or durable outbox ownership.
- Do not create a second service, coordinator, worker, alarm, or periodic wake mechanism.
- Do not treat process-local health as durable user intent.

## Recovery contract

The durable enabled flag outranks every route. A disabled configuration always produces `NoAction("disabled")`. An enabled configuration must also have a peer and at least one persisted route. Notification-listener access and effective notification-post availability are required before a recovery start. Missing access produces a bounded `notification_access_required` or `post_notifications_required` issue and leaves the enabled intent intact.

If the service is active, recovery is idempotent and only refreshes status/materialization. If a start was requested very recently but Android has not delivered `onCreate` yet, another lifecycle callback is coalesced. Otherwise the authority issues one explicit `ACTION_START`. Successful service creation clears the fence and recovery issue. A synchronous platform rejection releases the fence, records `background_start_denied`, and lets app launch or the user retry later.

The authority never mutates pairing, relay URL, LAN binding, or the user's enabled flag.

## Lifecycle triggers

- `APP_FOREGROUND`: always reconcile. This is the recovery point after force-stop and the fallback after an OEM denies a background start.
- `BOOT_COMPLETED`: reconcile after unlocked boot delivery when Android permits the foreground-service start.
- `MY_PACKAGE_REPLACED`: reconcile after an in-place signed update; the existing app data remains authoritative.
- `USER_RETRY`: if the service is dead, run recovery first; if it is live, retain the existing immediate route-retry signal.

Android's documented force-stop behavior means no receiver can wake the app until the user explicitly interacts with it. Recovery therefore reports that limit honestly instead of installing an unreliable workaround.

Platform references: Android documents both the background-start exemptions for boot/package-replacement broadcasts and the force-stopped-package constraint in its [foreground-service launch guidance](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start). Android 15's boot restrictions do not include Twinotify's `remoteMessaging` foreground-service type; see the official [Android 15 behavior changes](https://developer.android.com/about/versions/15/behavior-changes-all).

## Product status and action

`SyncHealth.enabled` mirrors only the durable user intent. Home drives its switch from this field when present, with the former live-state inference retained as compatibility fallback. This prevents a dead-but-enabled transport from being mislabeled “Paused.”

The route presentation gives recovery issues priority over idle route copy:

- missing notification access: “Notification access needed” with “Review permissions”;
- missing notification-post availability: “Notifications need attention” with “Review permissions”;
- background-start rejection: “Open Twinotify to resume” with “Try again now.”

Copy stays one sentence, exposes no endpoint or diagnostic exception, and routes permission recovery through the existing explanatory permission screen.

## Acceptance evidence

- JVM tests cover durable-intent truth, every eligibility block, active-service idempotence, coalesced start requests, timeout/retry, and denied-start recovery.
- Source-contract tests cover both manifest broadcasts, `goAsync`, and all call sites using the shared authority.
- TypeScript tests prove Home renders enabled-but-blocked recovery truth and invokes the correct permission/retry action.
- Emulator instrumentation proves recovery policy idempotence and that service configuration plus peer data survive an in-place reinstall of the same signed debug APK.
- Emulator lifecycle commands cover app force-stop followed by launcher wake and package-replaced delivery without duplicate service instances.
- Physical two-phone signed-upgrade, reboot, force-stop, and OEM background-policy evidence remains pending and is not claimed from emulator results.
