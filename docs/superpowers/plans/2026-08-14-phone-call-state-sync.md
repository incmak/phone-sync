# Phone-call state synchronization

## Goal

Add privacy-preserving synchronization of a device's cellular call state to the
existing v2 reliable-delivery pipeline, and mirror that state as an action-free
native Android call notification on the paired device. This plan covers state
capture and state mirroring only. It does not answer, reject, hang up, or place
calls remotely. Those controls require a separately approved Telecom/InCall
Service product decision and role/permission flow.

## Frozen contract

- The canonical event type is `call.state`.
- Each call has a random UUID `call_session_id`; the canonical id is
  `call:<call_session_id>` and sequences start at 1.
- Payload keys are exactly `call_session_id`, `state`, and `direction`.
  `state` is one of `ringing`, `active`, or `idle`; `direction` is one of
  `incoming`, `outgoing`, or `unknown`.
- Payload contains no phone number, contact name, call audio, SIM identifier,
  or raw notification text. The local capture layer never reads contacts.
- `idle` is emitted as the terminal state and is retained through the normal
  reliable outbox/receipt lifecycle. Duplicate sequence/digest deliveries are
  idempotent; lower or conflicting sequences are rejected without changing the
  mirror.
- Capture is opt-in through the existing service configuration. Missing
  `READ_PHONE_STATE`, unavailable telephony hardware, disabled capture, and
  permission revocation produce a truthful disabled health state and no event.
- Remote rendering uses a generic high-priority `CATEGORY_CALL` notification
  in this state-sync task. `Notification.CallStyle` is deferred because its
  public factories require answer/decline or hang-up `PendingIntent`s; adding
  fake or inert controls would be misleading. It has no answer/reject/hang-up
  actions.
  Notification content is generic (`Incoming call`, `Call in progress`, or
  `Call ended`) and uses the stable canonical identity.

Android's `TelephonyCallback.CallStateListener` is the supported API for state
callbacks on API 31+, while `InCallService` is the framework API for future call
controls and is role-gated; the implementation must not use the deprecated
`TelecomManager.acceptRingingCall()` path. References:

- https://developer.android.com/reference/android/telephony/TelephonyCallback.CallStateListener
- https://developer.android.com/reference/android/app/Notification.CallStyle
- https://developer.android.com/reference/android/telecom/InCallService
- https://developer.android.com/reference/android/telecom/TelecomManager

## Tasks

### 1. Freeze protocol and privacy boundaries (RED first)

Files:

- `proto/inner-event-v2.schema.json`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/protocol/ProtocolJson.kt`
- `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/protocol/ProtocolValidationTest.kt`
- `proto/fixtures/manifest.json`
- `proto/fixtures/server/call-state-valid.json`
- `proto/fixtures/server/call-state-invalid-extra-field.json`

Add the `call.state` type and exact payload validation. Add RED cases for
unknown state/direction, missing or non-UUID session id, phone-number/contact
fields, wrong canonical id, sequence zero, and trailing/unknown JSON keys.
Add valid and invalid fixtures to the closed-world manifest. GREEN requires
round-trip encode/decode, exact decoded values, and rejection of every privacy
field.

Commands:

```sh
cd relay && GOCACHE=/tmp/phone-sync-call-go-cache go test ./... -count=1
ANDROID_HOME=/Users/mak/Library/Android/sdk ./gradlew --no-daemon \
  :twinotify-core:testDebugUnitTest --tests '*Protocol*'
```

### 2. Implement local call-state capture

Files:

- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/call/CallState.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/call/CallStateSource.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/call/TelephonyCallStateSource.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/call/CallStateCoordinator.kt`
- `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/call/CallStateCoordinatorTest.kt`
- `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/call/TelephonyCallStateSourceTest.kt`
- `mobile/modules/twinotify-core/android/src/main/AndroidManifest.xml`

Define a small source interface returning `Ringing`, `Offhook`, and `Idle`
callbacks plus capability/permission status. Implement the Android adapter
with `TelephonyManager.registerTelephonyCallback` on API 31+ and an explicit
permission check. The coordinator owns the session UUID, maps framework states
to the frozen payload, suppresses duplicate callbacks, emits strictly
increasing sequences, and emits `idle` exactly once per session. It must be
safe to stop/unregister and must not block the main thread.

RED tests cover ordering, duplicate callbacks, idle-before-ringing, stop
race, permission denial, unsupported telephony, and callback exceptions.
GREEN includes a fake source unit suite and an emulator test that only checks
registration/unregistration and permission-denied behavior; it must not claim
that a real cellular call occurred.

### 3. Persist and transmit call events through v2 reliability

Files:

- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/call/CallStatePersister.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener/DurableCapturePersister.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncService.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/ServiceConfigStore.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReliableDeliveryDao.kt`
- `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/call/CallStatePersistenceTest.kt`

Add a call persister that uses the existing authenticated v2 envelope and
Room transaction boundary. `call.state` rows must use the same custody,
retry, receipt, retention, and unpair deletion rules as notification rows.
Start/stop it from `SyncService` only when the persisted capture setting is
enabled and permission is present. Update `SyncHealth` with a disabled reason
or last call event timestamp without exposing raw call data.

RED tests cover crash between sequence reservation and outbox insert,
duplicate same-digest retry, conflicting sequence, offline retention, unpair
wipe, and permission revocation while a callback is in flight. GREEN must
prove one atomic durable row per accepted sequence and no call event after
disabled/stop.

### 4. Materialize a remote action-free call mirror

Files:

- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/call/CallStateMaterializer.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/NotificationMaterializer.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/AndroidNotificationPort.kt`
- `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/call/CallStateMaterializerTest.kt`
- `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/call/CallNotificationTest.kt`

Route authenticated `call.state` events through the existing inbound reducer
and materializer. Use a stable tag/id derived only from canonical id. Build a
generic high-priority `CATEGORY_CALL` notification on every supported API with
generic text and no call-control `PendingIntent`s. `Notification.CallStyle`
remains part of the deferred controls plan. `idle` must cancel the mirror after
the durable inbound row is APPLIED. Replays must not create a second
notification or resurrect an idle call.

RED tests cover ringing -> active -> idle, out-of-order active, duplicate
replay, conflicting digest, missing permission, disabled notifications, and
process restart with a pending materialization receipt. GREEN includes a
debug emulator test asserting stable tag/id and cancellation; it must not
inspect phone-number content because none is permitted.

### 5. Integrate protocol fixtures and end-to-end host coverage

Files:

- `e2e/internal/scenario/call_state.go`
- `e2e/internal/scenario/call_state_test.go`
- `e2e/internal/control/client.go`
- `e2e/fixtures/call-state.json`
- `e2e/README.md`

Add a deterministic host scenario that drives the debug control surface's
synthetic call-state command (no real call required), waits for authenticated
state convergence on device B, and verifies sanitized state/sequence/queue
fields. Add a negative case for a forbidden phone number field. The scenario
must return `ErrUnsupportedEnvironment` when the debug command is unavailable,
never fabricate a physical-call pass, and redact all artifacts.

Commands:

```sh
cd e2e && GOCACHE=/tmp/phone-sync-call-e2e-cache go test ./... -race -count=1
./e2e/scripts/preflight_test.sh
```

### 6. Full gates and independent review

Run, capture, and inspect:

```sh
make proto-test
make relay-ci-test
ANDROID_HOME=/Users/mak/Library/Android/sdk make mobile-verify
cd e2e && GOCACHE=/tmp/phone-sync-call-e2e-cache go test ./... -race -count=1
go vet ./...
git diff --check
```

Run the focused Android instrumentation tests on an explicitly selected
`ANDROID_SERIAL`; if no device is available, record that as pending rather
than claiming success. Review the diff for permission minimization, absence of
phone-number/contact/audio data, stable sequence/custody behavior, and no
remote call-control surface. Commit only after independent review approves;
then update the release-evidence documentation to state that physical call
scenario evidence is still pending unless it was actually captured.

## Explicitly deferred follow-up: remote call controls

Answer/reject/hang-up requires a separate design and consent flow. Android's
`InCallService` is the supported control surface and may require the default
dialer role; do not add `READ_CALL_LOG`, `CALL_PHONE`, `ANSWER_PHONE_CALLS`, or
an exported `InCallService` in this state-sync task. A future plan must define
role acquisition, user-visible controls, authorization, replay safety, and
physical-device tests before implementation.
