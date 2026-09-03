# Stock Incoming-Call Control Capability Bridge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a paired Android phone answer, decline, or end a cellular call that began as incoming by invoking short-lived capabilities exposed by a compatible source dialer's `Notification.CallStyle`, while keeping call identity and audio off the wire.

**Architecture:** The source notification listener extracts typed call `PendingIntent` extras only from the current default dialer's unambiguous call notification, keeps them in a generation-scoped memory registry, and republishes the current `call.state` with opaque control UUIDs. The mirror renders native `Notification.CallStyle` actions, queues a 15-second E2EE `call.control.invoke`, and the origin durably claims the one-use capability before dispatching the original `PendingIntent`; organic `call.state` remains the only state authority.

**Tech Stack:** Android API 34+, Kotlin, Android `NotificationListenerService`, `Notification.CallStyle`, `PendingIntent`, `TelephonyCallback`, Room 11, kotlinx.coroutines, Expo native module bridge, React Native, JSON Schema 2020-12, Go relay fixture validation

## Global Constraints

- Implement only incoming cellular/IMS call answer, decline, and hang-up; no outgoing, emergency, call-waiting, conference, hold, mute, DTMF, video, caller identity, or audio feature is part of this plan.
- `call.control.invoke` expires exactly 15,000 ms after `created_at`; `call.control.result` expires exactly 300,000 ms after `created_at`.
- `invocation_id` equals the origin-minted `control_id`, making a capability one-use across repeated taps and duplicate transport delivery.
- Advertise controls only for the current default dialer, `Notification.CATEGORY_CALL`, one current incoming Twinotify session, and one unambiguous source notification.
- A ringing control set is empty or exactly `answer` plus `decline`; an active incoming set is empty or exactly `hang_up`; every other state has no controls.
- Persist no `PendingIntent`, dialer package, intent metadata, caller name, caller number, contact, SIM identity, verification data, notification text, or audio.
- Keep Room at version 11. Reuse `ActionInvocation` and `ActionExecution`; this plan creates no entity or migration.
- Keep `PendingIntent.send()` outcome named `dispatched`. Only an organic `call.state` transition confirms application.
- A control receiver is unexported and may run from the paired phone lock screen after a direct user tap; the enablement dialog must disclose that physical-possession behavior.
- Do not request `READ_CALL_LOG`, `CALL_PHONE`, `ANSWER_PHONE_CALLS`, `MANAGE_ONGOING_CALLS`, default-dialer role, accessibility service, or any audio permission.
- Build the mirror from native `Notification.CallStyle` and the current Twinotify notification icon/channel. Do not add a React call screen, full-screen intent, decorative call UI, fake control, or disabled control.
- Follow TDD: observe every focused RED failure before production code, then run the focused GREEN command before each commit.

---

### Task 1: Freeze call-control protocol and privacy boundaries

**Files:**

- Modify: `proto/inner-event-v2.schema.json`
- Modify: `proto/fixtures/manifest.json`
- Create: `proto/fixtures/v2-valid/call-state-controls-valid.json`
- Create: `proto/fixtures/v2-valid/call-control-invoke-valid.json`
- Create: `proto/fixtures/v2-valid/call-control-result-valid.json`
- Create: `proto/fixtures/v2-invalid/call-state-controls-invalid-set.json`
- Create: `proto/fixtures/v2-invalid/call-state-controls-duplicate-id.json`
- Create: `proto/fixtures/v2-invalid/call-control-invoke-privacy-field.json`
- Create: `proto/fixtures/v2-invalid/call-control-invoke-id-mismatch.json`
- Create: `proto/fixtures/v2-invalid/call-control-result-bad-status.json`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/protocol/ProtocolJson.kt`
- Modify: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/protocol/ProtocolValidationTest.kt`
- Modify: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/protocol/ProtocolFixtures.kt`
- Modify: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/protocol/ProtocolFixtureTest.kt`
- Modify: `relay/internal/server/fixture_test.go`

**Interfaces:**

- Consumes: the existing `InnerEventV2` envelope and `call.state` canonical identity `call:<call_session_id>`.
- Produces: strict `call.state.controls`, `call.control.invoke`, and `call.control.result` contracts accepted identically by JSON Schema and `ProtocolJson`.

- [x] **Step 1: Write failing Kotlin protocol tests**

Add these cases to `ProtocolValidationTest.kt` using its existing JSON helper style:

```kotlin
@Test
fun callState_acceptsOnlyCompleteControlsForIncomingState() {
    ProtocolJson.decodeInner(callStateWithControlsJson("ringing", "incoming", "answer,decline"))
    ProtocolJson.decodeInner(callStateWithControlsJson("active", "incoming", "hang_up"))

    for (raw in listOf(
        callStateWithControlsJson("ringing", "incoming", "answer"),
        callStateWithControlsJson("ringing", "incoming", "answer,answer"),
        callStateWithControlsJson("active", "incoming", "answer"),
        callStateWithControlsJson("active", "unknown", "hang_up"),
        callStateWithControlsJson("idle", "incoming", "hang_up"),
    )) assertFailsWith<IllegalArgumentException> { ProtocolJson.decodeInner(raw) }
}

@Test
fun callControlInvoke_isOneUseShortLivedAndPrivacyBounded() {
    val decoded = ProtocolJson.decodeInner(callControlInvokeJson())
    assertEquals("call.control.invoke", decoded.type)
    assertEquals(null, decoded.canonId)
    assertEquals(null, decoded.sequence)

    for (raw in listOf(
        callControlInvokeJson().replace("\"expires_at\":16000", "\"expires_at\":16001"),
        callControlInvokeJson().replace(CONTROL_ID, OTHER_CONTROL_ID, ignoreCase = false)
            .replaceFirst(OTHER_CONTROL_ID, CONTROL_ID),
        callControlInvokeJson().replace("\"kind\":\"answer\"", "\"kind\":\"mute\""),
        callControlInvokeJson().replace("\"invoked_at\":1000", "\"invoked_at\":1000,\"phone_number\":\"+15551234567\""),
        callControlInvokeJson().replace("\"call_sequence\":2", "\"call_sequence\":0"),
    )) assertFailsWith<IllegalArgumentException> { ProtocolJson.decodeInner(raw) }
}

@Test
fun callControlResult_acceptsOnlyTruthfulTerminalStatuses() {
    ProtocolJson.decodeInner(callControlResultJson("dispatched"))
    for (status in listOf("outcome_unknown", "capability_gone", "call_gone", "stale_state", "expired", "failed")) {
        ProtocolJson.decodeInner(callControlResultJson(status))
    }
    assertFailsWith<IllegalArgumentException> { ProtocolJson.decodeInner(callControlResultJson("answered")) }
}
```

Use these exact test constants and construct `controls` as JSON objects rather
than interpolating an unchecked raw string:

```kotlin
const val CALL_SESSION_ID = "11111111-1111-4111-8111-111111111111"
const val CONTROL_ID = "2a846785-e576-47d0-8c4b-e4fba30d88bd"
const val OTHER_CONTROL_ID = "0d47171d-c1ae-463a-bae7-3e8778517c0f"
```

- [x] **Step 2: Run the focused protocol test and observe RED**

Run:

```bash
cd mobile/android
./gradlew --no-daemon :twinotify-core:testDebugUnitTest --tests 'co.twinotify.core.protocol.ProtocolValidationTest'
```

Expected: FAIL because `call.state` rejects `controls` and the two new event
types are unsupported.

- [x] **Step 3: Implement exact runtime validation**

In `ProtocolJson.kt`, add the event types, lifetimes, status set, and validators:

```kotlin
private const val CALL_CONTROL_INVOKE_TTL_MS = 15_000L
private const val CALL_CONTROL_RESULT_TTL_MS = 300_000L
private val callControlResultStatuses = setOf(
    "dispatched", "outcome_unknown", "capability_gone", "call_gone",
    "stale_state", "expired", "failed",
)
private val callControlKinds = setOf("answer", "decline", "hang_up")

private fun validateCallControls(payload: JSONObject) {
    val state = payload.getString("state")
    val direction = payload.getString("direction")
    val controls = payload.optJSONArray("controls") ?: return
    val parsed = List(controls.length()) { index ->
        val item = controls.getJSONObject(index)
        requireOnlyKeys(item, setOf("control_id", "kind"), "call control descriptor")
        requiredUuid(item, "control_id", "call control descriptor") to
            requiredString(item, "kind", "call control descriptor")
    }
    require(parsed.map { it.first }.toSet().size == parsed.size) { "call controls require unique control_id" }
    require(parsed.map { it.second }.toSet().size == parsed.size) { "call controls require unique kind" }
    require(parsed.all { it.second in callControlKinds }) { "unsupported call control kind" }
    val kinds = parsed.map { it.second }.toSet()
    val legal = when {
        parsed.isEmpty() -> true
        state == "ringing" && direction == "incoming" -> kinds == setOf("answer", "decline")
        state == "active" && direction == "incoming" -> kinds == setOf("hang_up")
        else -> false
    }
    require(legal) { "call controls do not match call state" }
}

private fun validateCallControlInvoke(payload: JSONObject) {
    requireOnlyKeys(
        payload,
        setOf("invocation_id", "canon_id", "call_session_id", "call_sequence", "control_id", "kind", "invoked_at"),
        "call.control.invoke payload",
    )
    val invocationId = requiredUuid(payload, "invocation_id", "call.control.invoke payload")
    val controlId = requiredUuid(payload, "control_id", "call.control.invoke payload")
    require(invocationId == controlId) { "call control invocation must equal control id" }
    val session = requiredUuid(payload, "call_session_id", "call.control.invoke payload")
    require(requiredString(payload, "canon_id", "call.control.invoke payload") == "call:$session")
    require(requiredNonNegativeLong(payload, "call_sequence", "call.control.invoke payload") >= 1)
    require(requiredString(payload, "kind", "call.control.invoke payload") in callControlKinds)
    requiredNonNegativeLong(payload, "invoked_at", "call.control.invoke payload")
}

private fun validateCallControlResult(payload: JSONObject) {
    requireOnlyKeys(
        payload,
        setOf("invocation_id", "canon_id", "kind", "status"),
        "call.control.result payload",
    )
    requiredUuid(payload, "invocation_id", "call.control.result payload")
    require(requiredString(payload, "canon_id", "call.control.result payload").startsWith("call:"))
    require(requiredString(payload, "kind", "call.control.result payload") in callControlKinds)
    require(requiredString(payload, "status", "call.control.result payload") in callControlResultStatuses)
}
```

Call `validateCallControls` after the existing call-state validator. For each
control event require null top-level `canonId`/`sequence`, exact TTL, and
`invoked_at == createdAt` for invoke.

- [x] **Step 4: Extend the JSON Schema and committed fixture manifest**

Add `call.control.invoke` and `call.control.result` to the top-level `type`
enum. Add the exact payload schemas matching the Kotlin validators. Extend the
`call.state` payload with:

```json
"controls": {
  "type": "array",
  "maxItems": 2,
  "items": {
    "type": "object",
    "required": ["control_id", "kind"],
    "properties": {
      "control_id": { "type": "string", "format": "uuid" },
      "kind": { "type": "string", "enum": ["answer", "decline", "hang_up"] }
    },
    "additionalProperties": false
  }
}
```

Use conditional schema branches to enforce the legal state/direction control
sets from Global Constraints. Register all eight new fixture files in
`proto/fixtures/manifest.json`; the privacy fixture includes `phone_number` and
the ID-mismatch fixture uses different `invocation_id` and `control_id` values;
the duplicate-control fixture reuses one `control_id` for `answer` and `decline`;
all expect `invalid_frame`. Because JSON Schema cannot express relational
timestamp/ID equality, the cross-layer Go fixture harness additionally checks
unique call-state control IDs, invoke ID equality, canonical/session equality, `invoked_at == created_at`,
the exact 15-second invoke TTL, and the exact 5-minute result TTL.

- [x] **Step 5: Run protocol gates**

Run:

```bash
make proto-test
cd mobile/android
./gradlew --no-daemon :twinotify-core:testDebugUnitTest --tests 'co.twinotify.core.protocol.*'
```

Expected: both commands PASS and every fixture in the manifest is consumed.

- [x] **Step 6: Commit the protocol**

```bash
git add proto relay/internal/server/fixture_test.go mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/protocol/ProtocolJson.kt mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/protocol
git commit -m "feat(proto): define one-use incoming call controls"
```

---

### Task 2: Select and register only unambiguous default-dialer capabilities

**Files:**

- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/call/CallControl.kt`
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/call/CallCapabilitySelector.kt`
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/call/CallCapabilityRegistry.kt`
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/call/CallCapabilityCollector.kt`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/call/CallCapabilitySelectorTest.kt`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/call/CallCapabilityRegistryTest.kt`
- Create: `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/call/CallCapabilityCollectorTest.kt`

**Interfaces:**

- Consumes: `CallFrameworkState`, `CallDirection`, current default-dialer package, and notification listener snapshots.
- Produces: `CallCapabilitySelection.Ready<T>` containing one source key and a complete map of `CallControlKind` to live origin-owned handles; `CallCapabilityRegistry.lookup(canonId, sequence, controlId, kind)`.

- [x] **Step 1: Write selector and registry RED tests**

Create tests that use strings as fake handles:

```kotlin
@Test
fun ringingRequiresOneDefaultDialerCandidateWithBothCapabilities() {
    val ready = selector.select(
        defaultDialerPackage = "com.android.dialer",
        state = CallFrameworkState.RINGING,
        direction = CallDirection.INCOMING,
        candidates = listOf(candidate(answer = "a", decline = "d")),
    ) as CallCapabilitySelection.Ready
    assertEquals(setOf(CallControlKind.ANSWER, CallControlKind.DECLINE), ready.handles.keys)

    assertIs<CallCapabilitySelection.None>(selector.select(
        "com.android.dialer", CallFrameworkState.RINGING, CallDirection.INCOMING,
        listOf(candidate(answer = "a", decline = null)),
    ))
    assertIs<CallCapabilitySelection.None>(selector.select(
        "com.android.dialer", CallFrameworkState.RINGING, CallDirection.INCOMING,
        listOf(candidate(answer = "a", decline = "d"), candidate(sourceKey = "second", answer = "a2", decline = "d2")),
    ))
}

@Test
fun hangupIsAvailableOnlyForAObservedIncomingSession() {
    assertIs<CallCapabilitySelection.Ready<String>>(selector.select(
        "com.android.dialer", CallFrameworkState.OFFHOOK, CallDirection.INCOMING,
        listOf(candidate(hangUp = "h")),
    ))
    for (direction in listOf(CallDirection.OUTGOING, CallDirection.UNKNOWN)) {
        assertIs<CallCapabilitySelection.None>(selector.select(
            "com.android.dialer", CallFrameworkState.OFFHOOK, direction,
            listOf(candidate(hangUp = "h")),
        ))
    }
}

@Test
fun registryRejectsOldSequenceAndPurgedCapabilities() {
    val registry = CallCapabilityRegistry<String>()
    registry.install("call:$SESSION", CallCapabilityGeneration(2, "source", mapOf(CONTROL to RegisteredCallControl(CallControlKind.ANSWER, "token"))))
    assertIs<CallCapabilityLookup.Found<String>>(registry.lookup("call:$SESSION", 2, CONTROL, CallControlKind.ANSWER))
    assertIs<CallCapabilityLookup.StaleGeneration>(registry.lookup("call:$SESSION", 1, CONTROL, CallControlKind.ANSWER))
    registry.purge("call:$SESSION")
    assertIs<CallCapabilityLookup.MissingGeneration>(registry.lookup("call:$SESSION", 2, CONTROL, CallControlKind.ANSWER))
}
```

- [x] **Step 2: Run the focused tests and observe RED**

```bash
cd mobile/android
./gradlew --no-daemon :twinotify-core:testDebugUnitTest --tests 'co.twinotify.core.call.CallCapability*'
```

Expected: FAIL because the selector and registry types do not exist.

- [x] **Step 3: Implement the pure selection and memory registry**

Create these exact core types:

```kotlin
enum class CallControlKind(val wire: String) {
    ANSWER("answer"), DECLINE("decline"), HANG_UP("hang_up");

    companion object {
        fun fromWire(value: String): CallControlKind = entries.first { it.wire == value }
    }
}

data class CallControlDescriptor(val controlId: String, val kind: CallControlKind)
data class RegisteredCallControl<T>(val kind: CallControlKind, val handle: T)
data class CallCapabilityGeneration<T>(
    val sequence: Long,
    val sourceKey: String,
    val controls: Map<String, RegisteredCallControl<T>>,
)
data class CallCapabilityCandidate<T>(
    val sourceKey: String,
    val packageName: String,
    val category: String?,
    val answer: T?,
    val decline: T?,
    val hangUp: T?,
)

sealed interface CallCapabilitySelection<out T> {
    data class Ready<T>(val sourceKey: String, val handles: Map<CallControlKind, T>) : CallCapabilitySelection<T>
    data class None(val code: String) : CallCapabilitySelection<Nothing>
}

sealed interface CallCapabilityLookup<out T> {
    data class Found<T>(val handle: T) : CallCapabilityLookup<T>
    data object MissingGeneration : CallCapabilityLookup<Nothing>
    data object StaleGeneration : CallCapabilityLookup<Nothing>
    data object MissingControl : CallCapabilityLookup<Nothing>
}
```

`CallCapabilitySelector.select` first filters for exact default-dialer package
and `Notification.CATEGORY_CALL`. It returns `None("ambiguous_call_notification")`
unless exactly one candidate remains. It accepts both ringing tokens or the one
eligible active incoming hang-up token; every other shape returns
`None("call_controls_unavailable")`.

Implement `CallCapabilityRegistry<T>` with one immutable
`ConcurrentHashMap<String, CallCapabilityGeneration<T>>`, exact sequence and
kind checks, `install`, `lookup`, `purge`, and `clear`. Copy every installed map.

- [x] **Step 4: Implement typed Android extraction and instrumentation proof**

`CallCapabilityCollector` converts one `StatusBarNotification` without reading
any caller fields:

```kotlin
fun capture(sbn: StatusBarNotification): CallCapabilityCandidate<PendingIntent> {
    val extras = sbn.notification.extras
    return CallCapabilityCandidate(
        sourceKey = sbn.key,
        packageName = sbn.packageName,
        category = sbn.notification.category,
        answer = extras.getParcelable(Notification.EXTRA_ANSWER_INTENT, PendingIntent::class.java),
        decline = extras.getParcelable(Notification.EXTRA_DECLINE_INTENT, PendingIntent::class.java),
        hangUp = extras.getParcelable(Notification.EXTRA_HANG_UP_INTENT, PendingIntent::class.java),
    )
}
```

The instrumented test builds a local incoming `Notification.CallStyle` with
three test broadcast intents, wraps it in a `StatusBarNotification`, and proves
that only the three exact `PendingIntent` objects are returned. Assert that the
collector output has no title, text, `Person`, package-derived control kind, or
phone field.

- [x] **Step 5: Run selector, registry, and Android extraction tests**

```bash
cd mobile/android
./gradlew --no-daemon :twinotify-core:testDebugUnitTest --tests 'co.twinotify.core.call.CallCapability*'
./gradlew --no-daemon :twinotify-core:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=co.twinotify.core.call.CallCapabilityCollectorTest
```

Expected: JVM tests PASS. The instrumented test PASSes on the explicitly selected
API 34+ device; if no device is attached, record it as pending without changing
the JVM result.

- [x] **Step 6: Commit capability selection**

```bash
git add mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/call mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/call mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/call
git commit -m "feat(mobile/call): select live dialer capabilities"
```

---

### Task 3: Publish capability generations through durable call state

**Files:**

- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/call/CallState.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/call/CallStateCoordinator.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/call/CallStatePersister.kt`
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/call/CallControlCaptureBridge.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener/DurableCapturePersister.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener/TwinotifyNotificationListener.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener/NotificationListenerBridge.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncService.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/ServiceConfigStore.kt` (bring forward the default-false storage/read field only; Task 5 exposes consent mutation)
- Modify: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/call/CallStateCoordinatorTest.kt`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/call/CallControlCaptureBridgeTest.kt`
- Modify: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/call/CallStatePersistenceTest.kt`

**Interfaces:**

- Consumes: `CallCapabilitySelection.Ready<PendingIntent>` and existing durable `call.state` persistence.
- Produces: `CallStateEvent.controls`, `CallStateCoordinator.refreshControls(sourceKey, handles)`, and a registry generation installed only after the matching Room commit.

- [x] **Step 1: Write RED tests for refresh, ambiguity, and commit ordering**

Add tests proving:

```kotlin
@Test
fun capabilityRefreshIncrementsTheCurrentSessionWithoutChangingState() = runTest {
    coordinator.startForDebug()
    val ringing = coordinator.injectDebugState(CallFrameworkState.RINGING)!!
    val refreshed = coordinator.refreshControls("dialer-key", mapOf(
        CallControlKind.ANSWER to "answer-token",
        CallControlKind.DECLINE to "decline-token",
    ))!!

    assertEquals(ringing.callSessionId, refreshed.callSessionId)
    assertEquals("ringing", refreshed.state)
    assertEquals(ringing.sequence + 1, refreshed.sequence)
    assertEquals(listOf("answer", "decline"), refreshed.controls.map { it.kind.wire })
}

@Test
fun failedDurableCommitNeverInstallsPendingIntents() = runTest {
    val registry = CallCapabilityRegistry<String>()
    val persister = callPersister(registry = registry, commitResult = OutboundStateCommitResult.Stale(3))
    assertFailsWith<CallStatePersistenceException> { persister.persist(controllableRingingEvent()) }
    assertIs<CallCapabilityLookup.MissingGeneration>(registry.lookup(CANON, 2, ANSWER_ID, CallControlKind.ANSWER))
}
```

Also cover notification-before-telephony, telephony-before-notification,
duplicate notification post, two candidate notifications, removal, `idle`,
disable, unpair, and process-registry clear.

- [x] **Step 2: Run focused call tests and observe RED**

```bash
cd mobile/android
./gradlew --no-daemon :twinotify-core:testDebugUnitTest --tests 'co.twinotify.core.call.*'
```

Expected: FAIL because call events and the coordinator do not carry controls.

- [x] **Step 3: Extend the call event and coordinator**

Change `CallStateEvent` to:

```kotlin
data class CallStateEvent(
    val callSessionId: String,
    val state: String,
    val direction: CallDirection,
    val sequence: Long,
    val controls: List<CallControlDescriptor> = emptyList(),
    internal val pendingGeneration: CallCapabilityGeneration<PendingIntent>? = null,
)
```

`refreshControls` runs under the existing callback mutex. It returns null
unless a live incoming session exists and the handle set is legal for the
current framework state. It mints one UUID per kind, increments the sequence,
and delivers the event through the same pending-emits retry path. A state
transition always clears the prior source key and control generation before
emitting; the source notification must refresh capabilities for the new state.

Do not serialize `pendingGeneration`. `CallStatePersister` builds wire
descriptors from `event.controls` and passes the generation only to its
post-commit hook.

- [x] **Step 4: Bind listener notifications to the current coordinator**

Implement a process bridge with explicit attach/detach:

```kotlin
interface CallControlCaptureSink {
    fun onPosted(snapshot: CallCapabilityCandidate<PendingIntent>): Boolean
    fun onRemoved(sourceKey: String)
}

object CallControlCaptureBridge {
    private val sink = AtomicReference<CallControlCaptureSink?>(null)
    fun attach(value: CallControlCaptureSink) { sink.set(value) }
    fun detach(value: CallControlCaptureSink) { sink.compareAndSet(value, null) }
    fun posted(value: CallCapabilityCandidate<PendingIntent>): Boolean = sink.get()?.onPosted(value) == true
    fun removed(sourceKey: String) { sink.get()?.onRemoved(sourceKey) }
}
```

`SyncService` attaches the sink only when both call-state and call-control
preferences are enabled. The sink gets the default dialer package from
`TelecomManager`, selects over current active listener candidates, and calls
`refreshControls`. On attach, ask `NotificationListenerBridge` for current
active notifications so a process restart can mint a fresh generation.

In `TwinotifyNotificationListener.capturePosted`, offer the typed candidate to
the call bridge before normal notification capture. Return early from normal
notification mirroring only when the bridge accepts that exact current
default-dialer call notification. Forward removal by source key before the
normal removal path. This prevents a second caller-detail notification beside
the privacy-bounded call mirror while controls are enabled.

- [x] **Step 5: Install the registry strictly after durable commit**

Serialize controls in `CallStatePersister`:

```kotlin
put("controls", JSONArray().apply {
    event.controls.forEach { descriptor ->
        put(JSONObject().put("control_id", descriptor.controlId).put("kind", descriptor.kind.wire))
    }
})
```

After `commitOutboundState` returns `Committed`, install
`pendingGeneration.copy(sequence = event.sequence)` under
`call:<callSessionId>`. On every committed control-free sequence purge the old
generation. On stale/failed commit, discard the candidate and keep the previous
committed generation until the coordinator retries or terminalizes it.

- [x] **Step 6: Run call-state and persistence tests**

```bash
cd mobile/android
./gradlew --no-daemon :twinotify-core:testDebugUnitTest --tests 'co.twinotify.core.call.*' --tests 'co.twinotify.core.listener.CaptureCoordinatorTest'
```

Expected: PASS, including proof that no handle is installed before Room commit.

- [x] **Step 7: Commit capture integration**

```bash
git add mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/call mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncService.kt mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/call mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/listener
git commit -m "feat(mobile/call): publish durable call capabilities"
```

---

### Task 4: Queue, claim, execute, and report one-use control invocations

**Files:**

- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/call/CallControlEncoder.kt`
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/call/MirrorCallControlIntent.kt`
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/call/MirrorCallControlInvoker.kt`
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/call/CallControlInvokeReceiver.kt`
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/call/CallControlInvocationProcessor.kt`
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/call/CallControlResultProcessor.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/call/CallStateReducer.kt` (preserve validated inbound control descriptors in the canonical desired payload)
- Modify: `mobile/modules/twinotify-core/android/src/main/AndroidManifest.xml`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReliableDeliveryDao.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/actions/ActionInvocationExpiryReceiver.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/actions/ActionClaimRecovery.kt` (recover claimed call controls with `call.control.result`, never the notification result family)
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/actions/ActionClaimRecoveryReceiver.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/InboundDispatcher.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncService.kt`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/call/CallControlEncoderTest.kt`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/call/MirrorCallControlInvokerTest.kt`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/call/CallControlInvocationProcessorTest.kt`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/call/CallControlResultProcessorTest.kt`
- Modify: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/call/CallStateMaterializerTest.kt` (RED proof that reducer persistence retains only `control_id`/`kind`)
- Modify: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/actions/ActionClaimRecoveryTest.kt`
- Modify: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/actions/ActionClaimRecoveryWakeTest.kt`
- Modify: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/InboundDispatcherControlTest.kt`
- Modify: `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/storage/ActionClaimTransactionTest.kt`

**Interfaces:**

- Consumes: persisted `ActionInvocation`/`ActionExecution`, `DurablePeerControlSealer`, and `CallCapabilityRegistry<PendingIntent>`.
- Produces: `CallControlEncoder.encodeInvoke/encodeResult`, unexported mirror receiver, and authenticated dispatcher branches for both call-control event types.

- [x] **Step 1: Write RED tests for identity, TTL, lock-screen dispatch, and crash windows**

Cover these exact assertions:

```kotlin
@Test
fun encoderUsesCapabilityAsInvocationAndFifteenSecondExpiry() = runTest {
    val row = encoder(clock = { 1_000L }).encodeInvoke(CallControlInvokeInput(
        canonId = CANON, callSessionId = SESSION, callSequence = 2,
        controlId = CONTROL, kind = CallControlKind.ANSWER,
    ))
    val inner = openedInner(row)
    assertEquals(CONTROL, inner.payloadObject().getString("invocation_id"))
    assertEquals(16_000L, inner.expiresAt)
    assertFalse(row.requiresPeerReceipt)
}

@Test
fun mirrorQueuesFromLockedDeviceBecauseTheTapIsExplicitCallUx() = runTest {
    val result = invoker(deviceLocked = true).invoke(identity(CONTROL, CallControlKind.ANSWER))
    assertIs<MirrorCallControlInvokeResult.Queued>(result)
    assertEquals("call.control.invoke", inserted.single().eventType)
}

@Test
fun duplicateCapabilityExecutesPendingIntentOnce() = runTest {
    processor.process(request(msgId = MESSAGE_ONE))
    transitionOrganicCallStateToActive()
    processor.process(request(msgId = MESSAGE_TWO))
    assertEquals(1, pendingIntentDispatches)
    assertEquals("dispatched", replayedResultStatus)
}

@Test
fun claimedCrashBecomesOutcomeUnknownAndNeverRedispatches() = runTest {
    journal.seedClaim(CONTROL, claimedAt = 1_000L)
    val replay = processor(clock = { 61_001L }).process(request(msgId = MESSAGE_TWO))
    assertEquals(CallControlProcessResult.Replayed("outcome_unknown"), replay)
    assertEquals(0, pendingIntentDispatches)
}
```

Also test expired, future skew, wrong session, wrong sequence, wrong kind,
purged capability, origin not paired peer, malformed payload, result replay,
result/outbox conflict, and cancellation propagation.

- [x] **Step 2: Run focused tests and observe RED**

```bash
cd mobile/android
./gradlew --no-daemon :twinotify-core:testDebugUnitTest --tests 'co.twinotify.core.call.*Control*'
```

Expected: FAIL because the control command classes do not exist.

- [x] **Step 3: Implement the control encoder and mirror invocation**

Use `DurablePeerControlSealer` with no peer receipt:

```kotlin
data class CallControlInvokeInput(
    val canonId: String,
    val callSessionId: String,
    val callSequence: Long,
    val controlId: String,
    val kind: CallControlKind,
)

suspend fun encodeInvoke(input: CallControlInvokeInput): OutboundMessage {
    val now = clock()
    val event = InnerEventV2(
        msgId = newId(), originDevice = originDevice(), type = "call.control.invoke",
        canonId = null, sequence = null, createdAt = now, expiresAt = Math.addExact(now, 15_000L),
        payloadJson = JSONObject()
            .put("invocation_id", input.controlId)
            .put("canon_id", input.canonId)
            .put("call_session_id", input.callSessionId)
            .put("call_sequence", input.callSequence)
            .put("control_id", input.controlId)
            .put("kind", input.kind.wire)
            .put("invoked_at", now).toString(),
    )
    return sealer.seal(event, requiresPeerReceipt = false)
}
```

The mirror invoker resolves the canonical call state by stable tag/id, confirms
the current sequence still advertises that control ID/kind, encodes the row,
and creates this existing-schema row before calling
`commitCallControlInvocationAndOutbound`:

```kotlin
val invocation = ActionInvocation(
    invocationId = input.controlId,
    canonId = input.canonId,
    actionId = input.kind.wire,
    notificationSequence = input.callSequence,
    replyText = null,
    state = "PENDING",
    createdAt = now,
    expiresAt = Math.addExact(now, 15_000L),
    updatedAt = now,
)
```

It does **not** check `KeyguardManager`.
After commit it signals `SyncService`, schedules the existing invocation expiry,
and reposts the call mirror so the used controls disappear.

Register `CallControlInvokeReceiver` as `android:exported="false"`. Its data URI
is exactly:

```text
twinotify://call-control/<url-encoded-mirror-tag>/<mirror-id>/<control-id>/<kind>
```

It rejects query/fragment, noncanonical UUID, nonpositive ID, and unknown kind.

- [x] **Step 4: Generalize existing Room control transactions without a migration**

Keep both existing entities unchanged. Preserve the public notification-action
entry point and add a call-specific entry point over one private body:

```kotlin
@Transaction
open suspend fun commitActionInvocationAndOutbound(
    invocation: ActionInvocation,
    invoke: OutboundMessage,
): ActionInvocationOutboxCommitResult =
    commitControlInvocationAndOutboundBody(invocation, invoke, "notif.action.invoke")

@Transaction
open suspend fun commitCallControlInvocationAndOutbound(
    invocation: ActionInvocation,
    invoke: OutboundMessage,
): ActionInvocationOutboxCommitResult =
    commitControlInvocationAndOutboundBody(invocation, invoke, "call.control.invoke")

private suspend fun commitControlInvocationAndOutboundBody(
    invocation: ActionInvocation,
    invoke: OutboundMessage,
    eventType: String,
): ActionInvocationOutboxCommitResult {
    require(invocation.state == "PENDING")
    require(invoke.eventType == eventType)
    require(invoke.canonId == null && invoke.sequence == null && !invoke.requiresPeerReceipt)

    val existingInvocation = actionInvocation(invocation.invocationId)
    if (existingInvocation != null) {
        return if (existingInvocation == invocation && outboundMessage(invoke.msgId) == invoke) {
            ActionInvocationOutboxCommitResult.AlreadyCommitted
        } else {
            ActionInvocationOutboxCommitResult.InvocationConflict
        }
    }
    if (outboundMessage(invoke.msgId) != null) {
        return ActionInvocationOutboxCommitResult.OutboundConflict
    }
    insertActionInvocation(invocation)
    insertOutbound(invoke)
    return ActionInvocationOutboxCommitResult.Committed
}
```

Apply the same wrapper-plus-private-body shape to `claimActionInvocation`,
`completeActionExecutionAndEnqueue`, and `commitActionResult`. The existing
methods continue to require `notif.action.invoke`/`notif.action.result` and
`ACTION_RESULT_STATUSES`; the new methods are exactly:

```kotlin
private val CALL_CONTROL_RESULT_STATUSES = setOf(
    "dispatched", "outcome_unknown", "capability_gone", "call_gone",
    "stale_state", "expired", "failed",
)

@Transaction
open suspend fun claimCallControlInvocation(
    row: InboundMessage,
    execution: ActionExecution,
    now: Long,
): ActionClaimDecision = claimControlInvocationBody(
    row = row,
    execution = execution,
    now = now,
    expectedEventType = "call.control.invoke",
)

@Transaction
open suspend fun completeCallControlExecutionAndEnqueue(
    invocationId: String,
    status: String,
    now: Long,
    result: OutboundMessage,
): ActionCompletionOutboxCommitResult = completeControlExecutionAndEnqueueBody(
    invocationId = invocationId,
    status = status,
    now = now,
    result = result,
    expectedEventType = "call.control.result",
    allowedStatuses = CALL_CONTROL_RESULT_STATUSES,
)

@Transaction
open suspend fun commitCallControlResult(
    request: ActionResultRequest,
): ActionResultCommitResult = commitControlResultBody(
    request = request,
    expectedEventType = "call.control.result",
    allowedStatuses = CALL_CONTROL_RESULT_STATUSES,
    terminalState = ::callControlTerminalState,
)

private fun callControlTerminalState(status: String): String = when (status) {
    "dispatched" -> "DISPATCHED"
    "outcome_unknown" -> "OUTCOME_UNKNOWN"
    "capability_gone" -> "ACTION_GONE"
    "call_gone" -> "NOTIFICATION_GONE"
    "stale_state", "failed" -> "FAILED"
    "expired" -> "EXPIRED"
    else -> error("validated call-control result status drift")
}
```

Use these complete private bodies; the existing notification wrappers call them
with `notif.action.invoke`, `notif.action.result`, `ACTION_RESULT_STATUSES`, and
the current notification terminal-state mapping:

```kotlin
private suspend fun claimControlInvocationBody(
    row: InboundMessage,
    execution: ActionExecution,
    now: Long,
    expectedEventType: String,
): ActionClaimDecision {
    require(row.eventType == expectedEventType)
    require(row.canonId == null && row.sequence == null)
    require(row.outcome == "APPLIED" && row.appliedAt != null)
    require(row.relayAckState == "READY")
    require(execution.state == "CLAIMED")

    val existingInbound = inbound(row.msgId)
    if (existingInbound != null && existingInbound.envelopeSha256 != row.envelopeSha256) {
        return ActionClaimDecision.IdConflict
    }
    val existingExecution = actionExecution(execution.invocationId)
    if (existingExecution != null && (
            existingExecution.canonId != execution.canonId ||
                existingExecution.actionId != execution.actionId
            )
    ) {
        return ActionClaimDecision.IdConflict
    }
    if (existingExecution == null) {
        if (existingInbound == null) insertInbound(row)
        insertActionExecution(execution.copy(claimedAt = now))
        return ActionClaimDecision.Execute
    }
    if (existingInbound == null) insertInbound(row)
    if (existingExecution.state == "COMPLETED") {
        return ActionClaimDecision.Replay(requireNotNull(existingExecution.resultStatus))
    }
    if (claimGraceElapsed(existingExecution.claimedAt, now)) {
        check(completeActionExecutionClaim(execution.invocationId, "outcome_unknown", now) == 1)
        return ActionClaimDecision.Replay("outcome_unknown")
    }
    return ActionClaimDecision.InFlight
}

private suspend fun completeControlExecutionAndEnqueueBody(
    invocationId: String,
    status: String,
    now: Long,
    result: OutboundMessage,
    expectedEventType: String,
    allowedStatuses: Set<String>,
): ActionCompletionOutboxCommitResult {
    require(status in allowedStatuses)
    require(result.eventType == expectedEventType)
    require(result.canonId == null && result.sequence == null)
    require(!result.requiresPeerReceipt)

    val execution = actionExecution(invocationId)
        ?: return ActionCompletionOutboxCommitResult.MissingClaim
    if (execution.state == "COMPLETED") {
        return ActionCompletionOutboxCommitResult.AlreadyCompleted(
            requireNotNull(execution.resultStatus),
        )
    }
    if (outboundMessage(result.msgId) != null) {
        return ActionCompletionOutboxCommitResult.OutboundConflict
    }
    check(completeActionExecutionClaim(invocationId, status, now) == 1)
    insertOutbound(result)
    return ActionCompletionOutboxCommitResult.Committed
}

private suspend fun commitControlResultBody(
    request: ActionResultRequest,
    expectedEventType: String,
    allowedStatuses: Set<String>,
    terminalState: (String) -> String,
): ActionResultCommitResult {
    val row = request.inbound
    require(row.eventType == expectedEventType)
    require(row.canonId == null && row.sequence == null)
    require(row.outcome == "APPLIED" && row.appliedAt != null && row.relayAckState == "READY")
    require(request.status in allowedStatuses)

    inbound(row.msgId)?.let { existing ->
        return if (existing.envelopeSha256 == row.envelopeSha256) {
            ActionResultCommitResult.Duplicate
        } else {
            ActionResultCommitResult.IdConflict
        }
    }
    insertInbound(row)

    val invocation = actionInvocation(request.invocationId)
        ?: return ActionResultCommitResult.Committed(repost = null)
    if (invocation.canonId != request.canonId || invocation.state != "PENDING") {
        return ActionResultCommitResult.Committed(repost = null)
    }
    if (terminalizeActionInvocation(
            invocation.invocationId,
            terminalState(request.status),
            row.committedAt,
        ) != 1
    ) {
        return ActionResultCommitResult.Committed(repost = null)
    }
    val canonical = canonical(invocation.canonId)
    val repost = canonical?.takeIf {
        it.state == "ACTIVE" &&
            it.latestSequence == invocation.notificationSequence &&
            it.mirrorLocalTag != null && it.mirrorLocalId != null
    }?.let {
        ActionResultRepost(
            canonId = invocation.canonId,
            notificationSequence = invocation.notificationSequence,
            localTag = requireNotNull(it.mirrorLocalTag),
            localId = requireNotNull(it.mirrorLocalId),
        )
    }
    return ActionResultCommitResult.Committed(repost)
}
```

This keeps `ActionInvocation`'s current enum-like states valid, so Room stays at
version 11. `ActionInvocation.actionId` stores the bounded control kind
(`answer`, `decline`, or `hang_up`). `ActionExecution.actionId` stores
`<kind>:<call_sequence>` so the existing conflict check binds a claim to the
sequence as well as the canonical call; the UUID remains solely in
`invocationId`. Do not weaken the notification status sets.

Also give completed-result replay and authenticated post-decode rejection the
same notification-wrapper/call-wrapper treatment; their existing DAO entry
points are notification-event-type-specific. Within the call invocation/outbox
transaction, reject a second invocation for the same canonical call and
sequence even when it uses the other control UUID. This closes the rapid
answer-plus-decline tap race without a schema migration.

The connected `ActionClaimTransactionTest` must run both event families through
the same `action_execution` table and prove that an invocation ID conflict with
a different canon/control is rejected.

- [x] **Step 5: Implement origin execution and result handling**

`CallControlInvocationProcessor` mirrors the existing action processor but its
validation is call-specific:

```kotlin
data class CallControlInvokeRequest(
    val invocationId: String,
    val canonId: String,
    val callSessionId: String,
    val callSequence: Long,
    val controlId: String,
    val kind: CallControlKind,
    val invokedAt: Long,
)

private fun validate(request: CallControlInvokeRequest, now: Long): CallControlValidation {
    if (request.invokedAt > now + 30_000L || now - request.invokedAt > 15_000L) return Failed("expired")
    if (request.canonId != "call:${request.callSessionId}") return Failed("call_gone")
    val state = currentLocalCallState(request.canonId) ?: return Failed("call_gone")
    if (state.sequence != request.callSequence) return Failed("stale_state")
    if (state.direction != CallDirection.INCOMING) return Failed("call_gone")
    val found = registry.lookup(request.canonId, request.callSequence, request.controlId, request.kind)
    return if (found is CallCapabilityLookup.Found<*>) Ready(found.handle as PendingIntent)
    else Failed("capability_gone")
}
```

Construct the durable claim identity as:

```kotlin
val execution = ActionExecution(
    invocationId = request.invocationId,
    canonId = request.canonId,
    actionId = "${request.kind.wire}:${request.callSequence}",
    state = "CLAIMED",
    resultStatus = null,
    claimedAt = now,
    completedAt = null,
)
```

Authenticate and perform closed-schema/TTL/ID validation first, then call
`claimCallControlInvocation` before any lookup of mutable call state. A replay
returns its stored result even if the organic call state has since changed; a
new claim runs `validate` and only then calls `PendingIntent.send()`. Convert
`PendingIntent.CanceledException` and other non-cancellation failures to
`failed`; rethrow coroutine cancellation. `CallControlResultProcessor` commits
the inbound result to the matching `ActionInvocation`, clears any stored
reply field, records only a bounded status, and requests a call-mirror repost.

Update `ActionInvocationExpiryReceiver` so an expired call attempt is reposted
through `postCallMirror`, while notification actions retain `postMirror`:

```kotlin
val port = DefaultAndroidNotificationPort(app, DeviceIdentity.getOrCreate(app), dao)
if (row.canonId.startsWith("call:")) {
    port.postCallMirror(state)
} else {
    port.postMirror(state)
}
```

Add `InboundDispatcher` branches before the unsupported-event branch for
`call.control.invoke` and `call.control.result`. They must authenticate
`inner.originDevice` against `PeerStore`, journal malformed authenticated
controls as rejected/accepted-for-custody using the existing rejection pattern,
and never log payload JSON.

The shared claimed-execution recovery path must distinguish call executions
from notification actions using their durable bounded identity, seal an
`outcome_unknown` `call.control.result`, and use the call-specific completion
transaction. Include both new call-control event types in the DAO's user-event
delivery-queue classification. A frame that fails authenticated protocol decode
continues through the existing authentication-failure boundary; the dispatcher
rejection journal covers authenticated inner events that fail call-specific
mapping or semantic checks after decode.

- [x] **Step 6: Run processor and Room transaction tests**

```bash
cd mobile/android
./gradlew --no-daemon :twinotify-core:testDebugUnitTest --tests 'co.twinotify.core.call.*Control*' --tests 'co.twinotify.core.actions.*'
./gradlew --no-daemon :twinotify-core:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=co.twinotify.core.storage.ActionClaimTransactionTest
```

Expected: JVM tests PASS; connected test PASSes on an explicitly selected
device or is reported pending if no device is available.

- [x] **Step 7: Commit invocation processing**

```bash
git add mobile/modules/twinotify-core/android/src/main/AndroidManifest.xml mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/call mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/actions/ActionInvocationExpiryReceiver.kt mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReliableDeliveryDao.kt mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/call mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/storage/ActionClaimTransactionTest.kt
git commit -m "feat(mobile/call): execute one-use peer call controls"
```

---

### Task 5: Render truthful native CallStyle controls and consent

**Files:**

- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/call/CallStateMaterializer.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/AndroidNotificationPort.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/NotifChannelSetup.kt`
- Modify: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/call/CallStateMaterializerTest.kt`
- Modify: `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/call/CallNotificationTest.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/ServiceConfigStore.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/TwinotifyCoreModule.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncService.kt` (serialized controls-only reconfiguration after the durable preference commit)
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncServiceStatus.kt` (truthful call-notification capability modes)
- Modify: `mobile/modules/twinotify-core/src/TwinotifyCoreModule.ts`
- Modify: `mobile/hooks/useTwinotifyCore.ts`
- Modify: `mobile/app/settings/index.tsx`
- Modify: `mobile/jest.setup.js`
- Modify: `mobile/types/twinotify.d.ts`
- Modify: `mobile/app/__tests__/callSyncProduct.test.tsx`
- Modify: `mobile/app/__tests__/systemChrome.test.tsx`
- Modify: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/CallCaptureLifecycleTest.kt` (controls-only reconfiguration and atomic disable coverage)

**Interfaces:**

- Consumes: valid controls in the current `call.state`, persisted `ActionInvocation` states, and paired-device display name.
- Produces: native incoming/ongoing `CallStyle` notifications, an action-free attempt state, and a separate durable `callControlsEnabled` preference with explicit consent.

- [x] **Step 1: Write RED tests for every visual and interaction state**

Add JVM/instrumented assertions for:

```kotlin
@Test
fun ringingWithCompleteUnusedControlsUsesNativeIncomingCallStyle() {
    val model = CallStateMaterializer.model(ringingStateWithControls(), invocations = emptyList(), peerName = "Pixel 9")
    assertIs<CallNotificationModel.IncomingControllable>(model)
    assertEquals("Call on Pixel 9", model.personName)
}

@Test
fun missingPartialUsedOrFailedControlsNeverDrawFakeButtons() {
    assertIs<CallNotificationModel.StateOnly>(CallStateMaterializer.model(ringingStateWithoutControls(), emptyList(), "Pixel 9"))
    assertIs<CallNotificationModel.StateOnly>(CallStateMaterializer.model(ringingStateWithAnswerOnly(), emptyList(), "Pixel 9"))
    assertIs<CallNotificationModel.Attempted>(CallStateMaterializer.model(ringingStateWithControls(), listOf(pendingAnswer()), "Pixel 9"))
}

@Test
fun activeHangupRequiresIncomingDirection() {
    assertIs<CallNotificationModel.OngoingControllable>(CallStateMaterializer.model(activeIncomingWithHangup(), emptyList(), "Pixel 9"))
    assertIs<CallNotificationModel.StateOnly>(CallStateMaterializer.model(activeUnknownWithHangup(), emptyList(), "Pixel 9"))
}
```

In `callSyncProduct.test.tsx`, assert a 48 dp `Let paired phone control calls` switch,
the exact consent copy from the design spec, disabled controls when call state
is off, truthful `Audio stays here` subtitle, rollback on
native failure, and no caller/audio promise.

- [x] **Step 2: Run focused UI/materializer tests and observe RED**

```bash
cd mobile
npm test -- --runInBand app/__tests__/callSyncProduct.test.tsx app/__tests__/systemChrome.test.tsx
cd android
./gradlew --no-daemon :twinotify-core:testDebugUnitTest --tests 'co.twinotify.core.call.CallStateMaterializerTest'
```

Expected: FAIL because the preference and controllable models do not exist.

- [x] **Step 3: Implement the native notification model and PendingIntents**

Use a sealed pure model before Android construction:

```kotlin
sealed interface CallNotificationModel {
    val personName: String
    data class IncomingControllable(
        override val personName: String,
        val answer: CallControlDescriptor,
        val decline: CallControlDescriptor,
    ) : CallNotificationModel
    data class OngoingControllable(
        override val personName: String,
        val hangUp: CallControlDescriptor,
    ) : CallNotificationModel
    data class StateOnly(override val personName: String, val title: String, val text: String) : CallNotificationModel
    data class Attempted(override val personName: String, val title: String, val text: String) : CallNotificationModel
}
```

For complete unused controls, build:

```kotlin
val person = Person.Builder().setName(model.personName).setImportant(true).build()
val style = when (model) {
    is CallNotificationModel.IncomingControllable -> Notification.CallStyle.forIncomingCall(
        person,
        callPendingIntent(model.decline, state, tag, id),
        callPendingIntent(model.answer, state, tag, id),
    )
    is CallNotificationModel.OngoingControllable -> Notification.CallStyle.forOngoingCall(
        person,
        callPendingIntent(model.hangUp, state, tag, id),
    )
    else -> null
}
```

Every call control `PendingIntent` is an explicit broadcast to the unexported
receiver, uses `FLAG_UPDATE_CURRENT|FLAG_IMMUTABLE`, and has a request code
derived from the full data URI. State-only/attempted uses the existing generic
builder and contains no Android action.

For `Attempted`, preserve the truthful state title (`Incoming call` or
`Call in progress`) and use the failure-safe text `Control attempted. Check the
other phone.` This remains accurate for queued, failed, expired, and
outcome-unknown attempts.

`AndroidNotificationPort.postCallMirrorOutcome` loads current
`ActionInvocation` rows for the exact call sequence and the paired display name,
then passes both to the materializer. Update the channel description to:

```text
Incoming call state and controls from your paired phone. Caller identity and audio are not shared.
```

- [x] **Step 4: Add the separate durable preference and bridge methods**

Add `callControlsEnabled: Boolean = false` to `ServiceConfig`. Its setter may
persist true only when `callCaptureEnabled` is already true. Setting call
capture false atomically writes both booleans false, detaches the capture bridge,
purges the local capability registry, and lets the existing call-state disable
path terminalize the locally originated call session. Setting only controls
false purges the registry and publishes a new control-free sequence for any
live local call; it does not terminalize attempts for a remote call.
The boolean authorizes advertising controls for calls received on this phone;
it does not gate rendering a capability already authorized by the paired
origin phone.

Expose:

```typescript
getCallControlsEnabled(): Promise<boolean>;
setCallControlsEnabled(enabled: boolean): Promise<boolean>;
```

The native setter restarts the serialized service transport/capture lifecycle
only after the DataStore edit commits. It returns the durable resulting value.

- [x] **Step 5: Add the settings interaction using existing primitives**

Add the local state and rollback-safe handlers beside the existing call-state
preference. Load `getCallControlsEnabled()` in the screen's initial
`Promise.all`, and force the rendered value false whenever call state is off:

```tsx
const [callControlsEnabled, setCallControlsEnabled] = useState(false);
const [callControlsBusy, setCallControlsBusy] = useState(false);
const peerName = pairStatus.peerDisplayName?.trim() || 'your paired phone';

const persistCallControls = useCallback(async (next: boolean) => {
  const previous = callControlsEnabled;
  setCallControlsBusy(true);
  try {
    const durable = await TwinotifyCoreModule.setCallControlsEnabled(next);
    setCallControlsEnabled(callPreferenceEnabled && durable);
    if (durable !== next) {
      Alert.alert('Call controls unavailable', 'This phone could not save the call-control setting.');
    }
  } catch {
    setCallControlsEnabled(previous);
    Alert.alert('Call controls unavailable', 'Nothing changed. Try again.');
  } finally {
    setCallControlsBusy(false);
  }
}, [callControlsEnabled, callPreferenceEnabled]);

const handleCallControlsChange = useCallback((next: boolean) => {
  if (!next) {
    void persistCallControls(false);
    return;
  }
  Alert.alert(
    `Let ${peerName} control calls?`,
    `Anyone holding ${peerName} can answer, decline, or end an incoming cellular call on this phone, including while ${peerName} is locked. Call audio stays on this phone. Twinotify never sends the caller's name or number.`,
    [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Allow', onPress: () => { void persistCallControls(true); } },
    ],
  );
}, [peerName, persistCallControls]);
```

When `persistCallCapture(false)` completes, also set
`callControlsEnabled` to false from the durable native result. Under
`Mirror call state`, render one `TwRow` and `TwSwitch`. This preference gates
advertising this phone's capabilities; do not use it to hide valid controls
that the paired origin phone already authorized in a received `call.state`:

```tsx
<TwRow
  title="Let paired phone control calls"
  subtitle={callControlsEnabled
    ? 'The paired phone can answer, decline, or end compatible incoming calls on this phone. Audio stays here.'
    : 'Off. This phone can still mirror call state without sharing controls.'}
  trailing={
    <View style={styles.controlSlot}>
      <TwSwitch
        checked={callControlsEnabled}
        onChange={handleCallControlsChange}
        size="md"
        touchTargetSize={48}
        disabled={!callPreferenceEnabled || callControlsBusy}
        accessibilityLabel={`Let paired phone control calls, ${callControlsEnabled ? 'On' : 'Off'}`}
      />
    </View>
  }
  style={styles.ledgerRow}
/>
```

Use the existing theme, type, spacing, `TwRow`, and `TwSwitch`; do not add a
card, icon tile, pill, gradient, shadow, animated entrance, or second call
screen.

- [x] **Step 6: Run UI and notification tests**

```bash
cd mobile
npm test -- --runInBand app/__tests__/callSyncProduct.test.tsx app/__tests__/systemChrome.test.tsx
cd android
./gradlew --no-daemon :twinotify-core:testDebugUnitTest --tests 'co.twinotify.core.call.CallStateMaterializerTest'
./gradlew --no-daemon :twinotify-core:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=co.twinotify.core.call.CallNotificationTest
```

Expected: JS and JVM tests PASS. Instrumentation proves the incoming
notification contains answer/decline extras, ongoing contains hang-up, fallback
contains none, stable tag/id is preserved, and `idle` cancels it.

- [x] **Step 7: Commit native call UX and consent**

```bash
git add mobile/app/settings/index.tsx mobile/app/__tests__ mobile/hooks/useTwinotifyCore.ts mobile/modules/twinotify-core
git commit -m "feat(mobile/call): render consented native call controls"
```

---

### Task 6: Prove compatibility without exposing call data

**Files:**

- Create: `mobile/modules/twinotify-core/android/src/debug/java/co/twinotify/core/e2e/CallControlFixture.kt`
- Create: `mobile/modules/twinotify-core/android/src/debug/java/co/twinotify/core/e2e/CallControlControl.kt`
- Create: `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/e2e/CallControlFixtureTest.kt`
- Modify: `e2e/internal/control/control.go`
- Create: `e2e/internal/scenario/call_control.go`
- Create: `e2e/internal/scenario/call_control_test.go`
- Create: `e2e/fixtures/call-control.json`
- Modify: `e2e/README.md`
- Modify: `docs/test-scenarios.md`
- Create: `docs/evidence/call-control/README.md`

**Interfaces:**

- Consumes: debug-authenticated synthetic call state, local test call `PendingIntent`s, and the real encrypted transport.
- Produces: deterministic host evidence plus a redacted physical OEM/dialer compatibility record.

- [x] **Step 1: Write RED host and instrumentation scenarios**

The debug fixture posts a real local `Notification.CallStyle` whose answer,
decline, and hang-up receivers append only control kind plus timestamp to an
in-memory test journal. The scenario must prove:

```go
func TestCallControlScenarioRedactsCapabilityAndCallerData(t *testing.T) {
    artifact := runSyntheticCallControl(t, "answer")
    require.Equal(t, "dispatched", artifact.Status)
    require.NotContains(t, artifact.Raw, "control_id")
    require.NotContains(t, artifact.Raw, "invocation_id")
    require.NotContains(t, artifact.Raw, "phone_number")
    require.NotContains(t, artifact.Raw, "caller")
}
```

The connected test exercises ringing -> answer -> active -> hang-up -> idle and
decline -> idle, verifies one source receiver dispatch per control, and verifies
duplicate envelope delivery does not dispatch twice.

- [x] **Step 2: Run the new tests and observe RED**

```bash
cd e2e
GOCACHE=/tmp/phone-sync-call-control-e2e-cache go test ./internal/scenario -race -count=1
cd ../mobile/android
./gradlew --no-daemon :twinotify-core:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=co.twinotify.core.e2e.CallControlFixtureTest
```

Expected: host test FAILs because the scenario is absent; connected test FAILs
because the debug fixture is absent.

- [x] **Step 3: Implement debug-only controls and host scenario**

Expose only these authenticated debug commands:

```json
{ "command": "call_control_source", "state": "ringing" }
{ "command": "call_control_await", "kind": "answer", "timeout_ms": 5000 }
{ "command": "call_control_source", "state": "active" }
{ "command": "call_control_await", "kind": "hang_up", "timeout_ms": 5000 }
{ "command": "call_control_source", "state": "idle" }
```

Return only `kind`, `count`, `status`, and timing. Reject any debug request or
response containing number, name, notification text, raw UUID, raw intent, or
package. Release builds must have no call-control debug receiver/service.

- [x] **Step 4: Run host and connected automation**

```bash
cd e2e
GOCACHE=/tmp/phone-sync-call-control-e2e-cache go test ./... -race -count=1
./scripts/preflight_test.sh
cd ../mobile/android
./gradlew --no-daemon :twinotify-core:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=co.twinotify.core.e2e.CallControlFixtureTest
```

Expected: PASS with one dispatch per control and redacted artifacts.

- [ ] **Step 5: Run the physical stock-dialer matrix**

For every project-owned target build, record under a directory named from the
sanitized `Build.MODEL`, SDK, and date:

```json
{
  "source_model": "sanitized Build.MODEL",
  "source_build_fingerprint_sha256": "64 lowercase hex characters",
  "source_sdk": 34,
  "dialer_package_sha256": "64 lowercase hex characters",
  "dialer_version": "numeric versionCode",
  "peer_model": "sanitized Build.MODEL",
  "source_lock_state": "locked",
  "peer_lock_state": "locked",
  "route": "LAN",
  "control": "answer",
  "dispatch_status": "dispatched",
  "state_transition": "active",
  "latency_ms": 420
}
```

Run answer, decline, and answered-incoming hang-up over LAN and relay with both
screens on, off, and locked. Prove outgoing and concurrent-call cases expose no
controls. A build is supported only if all required cases pass and direct p95
is under 750 ms; relay p95 is under 2,000 ms. Use SHA-256 for build/dialer
identifiers when the raw string would expose unnecessary device detail.

- [x] **Step 6: Run complete gates and inspect the release manifest**

```bash
make proto-test
make relay-test
cd mobile
npm run typecheck
npm run lint
npx expo-doctor
npm run prebuild
cd android
./gradlew --no-daemon :twinotify-core:lintDebug :twinotify-core:testDebugUnitTest :app:assembleDebug
cd ../..
git diff --check
```

Expected: all available commands PASS. Inspect the merged release manifest and
prove it contains neither the debug call-control components nor any forbidden
permission from Global Constraints. Record unavailable physical or Docker gates
as pending rather than passing.

- [x] **Step 7: Complete point-by-point interaction review and commit evidence**

Review every call interaction against the full anti-slop design law: native
controls work by real pointer tap, fallback surfaces have no dead controls,
content is always visible, text is unclipped and legible in light/dark/large
font, every target is at least 48 dp, system icons are centered, no decorative
pill/card/glow/gradient/shadow was introduced, lock-screen state is truthful,
and reduced-motion has no hidden content. Fix every failure before committing.

```bash
git add e2e mobile/modules/twinotify-core/android/src/debug mobile/modules/twinotify-core/android/src/androidTest docs/test-scenarios.md docs/evidence/call-control
git commit -m "test(mobile/call): verify incoming call controls"
```
