# Bluetooth Direct E2EE Route Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a user-associated secure Bluetooth Classic RFCOMM route that carries Twinotify's existing E2EE v2 envelopes when Wi-Fi is unavailable, without changing the application protocol or allowing a second outbox drainer.

**Architecture:** After the Twinotify peer identity is confirmed, both apps use generic Companion Device Manager association and a fixed BLE discovery service to select each other, then open a secure RFCOMM stream with a private service UUID. A fresh Ed25519 mutual-challenge handshake binds the Bluetooth socket to the stored Twinotify peer, a route-neutral direct-delivery engine reuses durable put/accepted semantics across LAN and Bluetooth, and `TransportCoordinator` grants exactly one route in LAN > Bluetooth > relay order when direct delivery is preferred.

**Tech Stack:** Android API 34+, Kotlin, Bluetooth LE advertising/discovery, CompanionDeviceManager, secure Bluetooth Classic RFCOMM, Ed25519/libsodium, coroutines/Flow, Room 11, DataStore, Expo native module bridge, React Native, existing v2 E2EE envelopes

## Global Constraints

- This route transports Twinotify data only. It does not implement HFP, SCO, LE Audio, microphone capture, speaker playback, or carrier-call audio.
- Do not request `DEVICE_PROFILE_WATCH`, `MANAGE_ONGOING_CALLS`, `BLUETOOTH_PRIVILEGED`, location, call-log, or audio permissions.
- Add only `BLUETOOTH_SCAN` with `neverForLocation`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`, and `FOREGROUND_SERVICE_CONNECTED_DEVICE`.
- Use generic Companion Device Manager association; both phones require explicit user approval.
- Use secure RFCOMM with private service UUID `7c6f5d5e-6f54-4f6e-9b63-5457494e4f54`; never use insecure RFCOMM or the public SPP UUID.
- Use BLE service UUID `5d7101b8-cad0-4d22-a41e-5457494e4f54` only while the user is in Bluetooth association flow; do not advertise continuously.
- Keep raw Bluetooth addresses in memory only. Persist the CDM association ID and Twinotify peer-binding facts, not the address.
- A Bluetooth link is trusted only after its fresh mutual Ed25519 challenge verifies the stored peer device ID, peer signing key, roles, protocol version 1, and route label `bluetooth-rfcomm-v1`.
- Reuse the stored E2EE envelope byte-for-byte. Bluetooth link encryption is defense in depth, not a replacement for Twinotify encryption/authentication.
- Frame body maximum remains 1,064,996 bytes, envelope maximum remains 1,048,576 bytes, control maximum remains 16,384 bytes, and the writer holds at most 4 frames / 4,260,000 bytes.
- Keep Room at version 11. Adding enum value `BLUETOOTH` to string-backed route fields requires no entity or migration.
- Exactly one coordinator-granted session may call `OutboxRepository.sendable`; candidate authentication never grants an outbox lease.
- With direct preference on, priority is LAN > Bluetooth > relay. With direct preference off, a healthy relay remains first and direct routes are fallbacks.
- Unpair must close Bluetooth sessions, clear the local binding, request CDM disassociation for the exact stored association ID, and preserve the existing cryptographic wipe ordering.
- Follow TDD: observe every focused RED failure before production code, then run the focused GREEN command before each commit.

---

### Task 1: Extract one route-neutral direct-delivery engine without changing LAN behavior

**Files:**

- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/direct/DirectDelivery.kt`
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/direct/DirectWire.kt`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/direct/DirectDeliveryTest.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/lan/LanTransport.kt`
- Modify: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/lan/LanTransportTest.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/OutboxRepository.kt`
- Modify: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/OutboxRepositoryTest.kt`

**Interfaces:**

- Consumes: authenticated LAN frames, `InboundDispatchResult`, `OutboundMessage`, and current durable custody transitions.
- Produces: `DirectWire`, `DirectCommand`, `DirectDeliveryEvent`, `DirectDelivery`, and `OutboxRepository.onDirectAccepted(msgId, CustodyRoute)` for both direct transports.

- [x] **Step 1: Write RED tests for route-neutral durable semantics**

Create a fake wire and prove commit-before-ack, digest matching, route-specific
custody, pressure, and cancellation:

```kotlin
@Test
fun inboundIsDurablyCommittedBeforeAcceptedIsWritten() = runTest {
    val order = mutableListOf<String>()
    val wire = FakeDirectWire(incoming = flowOf(DirectCommand.Put(ENVELOPE))) { frame ->
        if (frame is DirectCommand.Accepted) order += "accepted"
    }
    DirectDelivery(
        wire = wire,
        outbox = fakeOutbox(),
        custodyRoute = CustodyRoute.LAN,
        dispatch = {
            order += "committed"
            InboundDispatchResult.Accepted(MSG_ID, DIGEST)
        },
    ).run().toList()
    assertEquals(listOf("committed", "accepted"), order)
}

@Test
fun peerAcceptanceUsesTheGrantedDirectCustodyRoute() = runTest {
    val outbox = recordingOutbox(row(MSG_ID, DIGEST))
    val delivery = DirectDelivery(fakeWire(), outbox, CustodyRoute.BLUETOOTH, dispatch = { error("unused") })
    delivery.recordSent(row(MSG_ID, DIGEST))
    delivery.accept(DirectCommand.Accepted(MSG_ID, DIGEST))
    assertEquals(listOf(CustodyRoute.BLUETOOTH), outbox.acceptedRoutes)
}

@Test
fun mismatchedAcceptedDigestClosesWithoutTakingCustody() = runTest {
    val delivery = deliveryWithSentRow(DIGEST)
    val event = delivery.accept(DirectCommand.Accepted(MSG_ID, "b".repeat(64)))
    assertEquals(DirectDeliveryEvent.Closed("ack_digest_mismatch"), event)
    assertTrue(acceptedRoutes.isEmpty())
}
```

Port every current `LanTransportTest` invariant to `DirectDeliveryTest`; keep a
thin LAN adapter test proving exact `LanFrame` mapping.

- [x] **Step 2: Run direct and LAN tests and observe RED**

```bash
cd mobile/android
./gradlew --no-daemon :twinotify-core:testDebugUnitTest --tests 'co.twinotify.core.direct.*' --tests 'co.twinotify.core.lan.LanTransportTest' --tests 'co.twinotify.core.service.OutboxRepositoryTest'
```

Expected: FAIL because `co.twinotify.core.direct` and Bluetooth custody do not
exist.

- [x] **Step 3: Implement the route-neutral command/wire contract**

Create:

```kotlin
sealed interface DirectCommand {
    data class Put(val envelope: ByteArray) : DirectCommand
    data class Accepted(val msgId: String, val envelopeSha256: String) : DirectCommand
    data class Ping(val token: Long) : DirectCommand
    data class Pong(val token: Long) : DirectCommand
    data class Close(val code: String) : DirectCommand
}

interface DirectWire : Closeable {
    val peerDeviceId: String
    val incoming: Flow<DirectCommand>
    suspend fun send(command: DirectCommand)
}

sealed interface DirectDeliveryEvent {
    data class PeerAccepted(val msgId: String, val eventType: String?) : DirectDeliveryEvent
    data class Committed(val msgId: String, val duplicate: Boolean) : DirectDeliveryEvent
    data class Closed(val code: String) : DirectDeliveryEvent
}
```

Move the ordered inbound processor, in-flight digest map, heartbeat, and
durable accepted transition from `LanTransport` into `DirectDelivery`. Require
`custodyRoute` to be `LAN` or `BLUETOOTH`. Keep cancellation propagation and
non-cancellable close behavior identical.

- [x] **Step 4: Adapt LAN with no wire change**

Inside `LanTransport.kt`, add a private mapping adapter:

```kotlin
private class LanDirectWire(private val connection: AuthenticatedLanConnection) : DirectWire {
    override val peerDeviceId: String get() = connection.peerDeviceId
    override val incoming: Flow<DirectCommand> = connection.incoming.map { frame ->
        when (frame) {
            is LanFrame.Put -> DirectCommand.Put(frame.envelope)
            is LanFrame.Accepted -> DirectCommand.Accepted(frame.msgId, frame.envelopeSha256)
            is LanFrame.Ping -> DirectCommand.Ping(frame.token)
            is LanFrame.Pong -> DirectCommand.Pong(frame.token)
            is LanFrame.Close -> DirectCommand.Close(frame.code)
            is LanFrame.Hello, is LanFrame.HelloAck -> throw LanFrameException(LanFrameFailure.UNSUPPORTED_TYPE)
        }
    }
    override suspend fun send(command: DirectCommand) = connection.send(command.toLanFrame())
    override fun close() = connection.close()
}
```

`LanTransport` delegates to `DirectDelivery(..., CustodyRoute.LAN, ...)` and maps
events back to its current public `LanTransportEvent`, preserving every caller
and wire type.

Replace `onLanAccepted(msgId)` with:

```kotlin
suspend fun onDirectAccepted(msgId: String, route: CustodyRoute): OutboxTransition {
    require(route == CustodyRoute.LAN || route == CustodyRoute.BLUETOOTH)
    return when (onCustodyAccepted(msgId, route, clock())) {
        CustodyAcceptanceResult.DeletedReceipt -> OutboxTransition.Deleted
        CustodyAcceptanceResult.Accepted, CustodyAcceptanceResult.AlreadyAccepted -> OutboxTransition.Retained
        CustodyAcceptanceResult.Missing -> OutboxTransition.Missing
    }
}
```

Update the LAN call site to pass `CustodyRoute.LAN`; do not keep two competing
implementations.

- [x] **Step 5: Run the focused regression suite**

```bash
cd mobile/android
./gradlew --no-daemon :twinotify-core:testDebugUnitTest --tests 'co.twinotify.core.direct.*' --tests 'co.twinotify.core.lan.*' --tests 'co.twinotify.core.service.OutboxRepositoryTest'
```

Expected: PASS with all existing LAN transport behavior unchanged.

- [x] **Step 6: Commit the shared direct core**

```bash
git add mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/direct mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/lan/LanTransport.kt mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/OutboxRepository.kt mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/direct mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/lan/LanTransportTest.kt mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/OutboxRepositoryTest.kt
git commit -m "refactor(mobile/transport): share direct custody engine"
```

---

### Task 2: Add explicit Bluetooth permission, association, and pair binding

**Files:**

- Modify: `mobile/modules/twinotify-core/android/src/main/AndroidManifest.xml`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncService.kt`
- Modify: `mobile/app.json`
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/bluetooth/BluetoothConstants.kt`
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/bluetooth/BluetoothBindingStore.kt`
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/bluetooth/BluetoothAssociation.kt`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/bluetooth/BluetoothBindingStoreTest.kt`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/bluetooth/BluetoothAssociationPolicyTest.kt`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/bluetooth/BluetoothPermissionContractTest.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/TwinotifyCoreModule.kt`
- Modify: `mobile/modules/twinotify-core/src/TwinotifyCoreModule.ts`
- Modify: `mobile/hooks/useTwinotifyCore.ts`

**Interfaces:**

- Consumes: the confirmed `PeerRecord`, current pair binding/generation, Android runtime permission APIs, and Companion Device Manager.
- Produces: `BluetoothBinding`, `BluetoothBindingStore.loadValidated`, association start/cancel/remove functions, and JS permission/association bridge methods.

- [x] **Step 1: Write RED permission and binding tests**

Define and test the exact contract:

```kotlin
@Test
fun bluetoothPermissionSetIsNearbyOnly() {
    assertEquals(
        setOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        ),
        BluetoothAssociationPolicy.RUNTIME_PERMISSIONS,
    )
    assertFalse(BluetoothAssociationPolicy.RUNTIME_PERMISSIONS.contains(Manifest.permission.ACCESS_FINE_LOCATION))
}

@Test
fun bindingIsRejectedAfterPeerOrAssociationChanges() = runTest {
    store.save(BluetoothBinding(associationId = 41, peerDeviceId = PEER_ID, peerSigningKeySha256 = PEER_DIGEST, protocolVersion = 1))
    assertNotNull(store.loadValidated(peer(PEER_ID, PEER_KEY), setOf(41)))
    assertNull(store.loadValidated(peer("replacement", OTHER_KEY), setOf(41)))
    assertNull(store.loadValidated(peer(PEER_ID, PEER_KEY), emptySet()))
}
```

The manifest test must assert `BLUETOOTH_SCAN` has
`android:usesPermissionFlags="neverForLocation"`, the service has
`remoteMessaging|connectedDevice`, and forbidden permissions from Global
Constraints are absent.

- [x] **Step 2: Run tests and observe RED**

```bash
cd mobile/android
./gradlew --no-daemon :twinotify-core:testDebugUnitTest --tests 'co.twinotify.core.bluetooth.*'
```

Expected: FAIL because the Bluetooth package and permissions are absent.

- [x] **Step 3: Add manifest and foreground-service declarations**

Add:

```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
```

Change the service declaration to:

```xml
android:foregroundServiceType="remoteMessaging|connectedDevice"
```

When Bluetooth route enablement is durable and permissions are granted, call
`startForeground` with:

```kotlin
ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING or
    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
```

Otherwise retain only `FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING`. Keep
`RECORD_AUDIO` in `app.json`'s blocked permissions.

- [x] **Step 4: Implement pair-bound CDM association state**

Use these exact constants and model:

```kotlin
object BluetoothConstants {
    val RFCOMM_SERVICE_UUID: UUID = UUID.fromString("7c6f5d5e-6f54-4f6e-9b63-5457494e4f54")
    val DISCOVERY_SERVICE_UUID: UUID = UUID.fromString("5d7101b8-cad0-4d22-a41e-5457494e4f54")
    const val ROUTE_LABEL = "bluetooth-rfcomm-v1"
    const val PROTOCOL_VERSION = 1
}

data class BluetoothBinding(
    val associationId: Int,
    val peerDeviceId: String,
    val peerSigningKeySha256: String,
    val protocolVersion: Int = BluetoothConstants.PROTOCOL_VERSION,
)
```

`BluetoothBindingStore` uses a dedicated DataStore preferences file. Validation
requires exact current peer ID, constant-time SHA-256 of the current peer
signing key, protocol version 1, and an association ID still present in
`CompanionDeviceManager.myAssociations`. A mismatch clears only the Bluetooth
binding and leaves LAN/relay pairing intact.

The association request uses a `BluetoothLeDeviceFilter` whose scan filter
matches only `DISCOVERY_SERVICE_UUID`; it sets no watch profile and no
single-device shortcut. A foreground association session advertises the same
service UUID and stops advertising on selection, rejection, activity stop, or
120-second timeout. Reject a selected device unless it reports
`BluetoothDevice.DEVICE_TYPE_DUAL`, because the selected BLE identity must also
support the Classic RFCOMM socket used by this route. Treat the CDM result as a
provisional in-memory association only: Task 2 persists no `BluetoothBinding`.
Task 3 authenticates that provisional device and saves the association ID only
after it proves the Twinotify peer identity.

- [x] **Step 5: Expose narrow native bridge methods**

Add:

```typescript
type BluetoothRouteSettings = { associated: boolean; enabled: boolean };

getBluetoothRoutePermissionAsync(): Promise<PermissionResponse>;
requestBluetoothRoutePermissionAsync(): Promise<PermissionResponse>;
startBluetoothAssociation(): Promise<{ associated: boolean }>;
getBluetoothRouteSettings(): Promise<BluetoothRouteSettings>;
getBluetoothRouteEnabled(): Promise<boolean>;
setBluetoothRouteEnabled(enabled: boolean): Promise<boolean>;
removeBluetoothAssociation(): Promise<void>;
```

`setBluetoothRouteEnabled(true)` returns false unless all three permissions,
a confirmed peer, and a validated CDM association exist. The settings getter
returns only booleans, never an association ID or address. Setting false closes
the route and clears only the enabled preference; `removeBluetoothAssociation`
is a separate explicit user action because it invokes CDM disassociation.
Until Task 3 completes the authenticated association, the getter reports false
and the provisional association cannot open a route.

- [x] **Step 6: Run permission and binding tests**

```bash
cd mobile/android
./gradlew --no-daemon :twinotify-core:testDebugUnitTest --tests 'co.twinotify.core.bluetooth.*'
```

Expected: PASS, including forbidden-permission and stale-association checks.

- [x] **Step 7: Commit association foundations**

```bash
git add mobile/app.json mobile/hooks/useTwinotifyCore.ts mobile/modules/twinotify-core
git commit -m "feat(mobile/bluetooth): bind a companion association to the peer"
```

---

### Task 3: Authenticate and frame a bounded RFCOMM stream

**Files:**

- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/bluetooth/BluetoothHandshake.kt`
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/bluetooth/BluetoothFrame.kt`
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/bluetooth/BluetoothFrameCodec.kt`
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/bluetooth/BluetoothSocketWire.kt`
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/bluetooth/BluetoothConnector.kt`
- Modify (created in Task 2): `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/bluetooth/BluetoothAssociation.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/TwinotifyCoreModule.kt`
- Modify: `mobile/modules/twinotify-core/src/TwinotifyCoreModule.ts`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/bluetooth/BluetoothHandshakeTest.kt`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/bluetooth/BluetoothFrameCodecTest.kt`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/bluetooth/BluetoothConnectorTest.kt`
- Create: `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/bluetooth/BluetoothSocketWireTest.kt`

**Interfaces:**

- Consumes: resolved associated `BluetoothDevice`, local and peer Twinotify signing identities, and `DirectWire`.
- Produces: `BluetoothConnector.connect(): AuthenticatedBluetoothWire`, which returns only after mutual challenge verification and bounds every read/write.

- [x] **Step 1: Write RED handshake and frame tests**

Cover exact transcript agreement and failure codes:

```kotlin
@Test
fun clientAndServerAuthenticateOneTranscript() = runTest {
    val pair = inMemoryDuplex()
    val client = async { clientHandshake.authenticate(pair.client) }
    val server = async { serverHandshake.authenticate(pair.server) }
    assertContentEquals(client.await().sessionId, server.await().sessionId)
    assertEquals(PEER_ID, client.await().peerDeviceId)
}

@Test
fun wrongPeerReplayRoleAndProtocolFailClosed() = runTest {
    assertFailsWithCode("bluetooth_identity_mismatch") { handshake(peerDeviceId = "wrong") }
    assertFailsWithCode("bluetooth_replayed_nonce") { replayCapturedHandshake() }
    assertFailsWithCode("bluetooth_role_mismatch") { bothPeersClaimClient() }
    assertFailsWithCode("bluetooth_protocol_downgrade") { handshake(protocolVersion = 0) }
}

@Test
fun codecRejectsOversizeTruncationDuplicatesAndPrivateUnknownFields() {
    assertFailsWithCode("bluetooth_frame_too_large") { decode(frameWithLength(1_064_997)) }
    assertFailsWithCode("bluetooth_frame_truncated") { decode(byteArrayOf(0, 0, 0, 10, 1)) }
    assertFailsWithCode("bluetooth_frame_duplicate_key") { decodeJson("{\"v\":1,\"v\":1,\"type\":\"bt.ping\",\"token\":1}") }
    assertFailsWithCode("bluetooth_frame_invalid_fields") { decodeJson("{\"v\":1,\"type\":\"bt.ping\",\"token\":1,\"address\":\"private\"}") }
}
```

Also prove a single collector, mutexed writer, write timeout, read timeout,
close-unblocks-read, noncanonical UUID rejection, lowercase digest requirement,
and that `toString()` redacts handshake bytes and envelopes. Android's
`BluetoothSocket` has no public socket-read-timeout setter: enforce the read
deadline with `withTimeout`, close the socket on timeout, and join the blocking
reader so `InputStream.read()` cannot leak a worker.

- [x] **Step 2: Run focused Bluetooth tests and observe RED**

```bash
cd mobile/android
./gradlew --no-daemon :twinotify-core:testDebugUnitTest --tests 'co.twinotify.core.bluetooth.BluetoothHandshakeTest' --tests 'co.twinotify.core.bluetooth.BluetoothFrameCodecTest' --tests 'co.twinotify.core.bluetooth.BluetoothConnectorTest'
```

Expected: FAIL because handshake, frame, and connector types are absent.

- [x] **Step 3: Implement the three-message mutual challenge**

Use 32-byte random nonces and one canonical transcript:

```text
"twinotify-bluetooth-handshake-v1" ||
length(client_device_id) || client_device_id ||
length(server_device_id) || server_device_id ||
client_nonce[32] || server_nonce[32] ||
protocol_version_u32 ||
length("bluetooth-rfcomm-v1") || "bluetooth-rfcomm-v1"
```

Wire sequence:

```kotlin
sealed interface BluetoothHello {
    data class ClientHello(val deviceId: String, val nonce: ByteArray, val protocolVersion: Int) : BluetoothHello
    data class ServerHello(val deviceId: String, val nonce: ByteArray, val signature: ByteArray) : BluetoothHello
    data class ClientFinish(val signature: ByteArray) : BluetoothHello
}

class AuthenticatedBluetoothWire(
    override val peerDeviceId: String,
    val sessionId: ByteArray,
    private val delegate: BluetoothSocketWire,
) : DirectWire by delegate {
    override fun toString(): String =
        "AuthenticatedBluetoothWire(peerDeviceId=$peerDeviceId, sessionId=<redacted>)"
}
```

The server signature is Ed25519 over `"server" || transcript`; client finish
is over `"client" || transcript`. Verify exact stored peer ID/key, distinct IDs,
roles, version 1, nonce length, canonical field set, and signature before
returning. Record `(peerDeviceId, peerNonce, sessionId)` in a 256-entry replay
guard. `sessionId = SHA-256(transcript)`.

- [x] **Step 4: Implement closed-world frames and stream bounds**

Use these frame types after handshake:

```text
bt.put       { v, type, envelope }
bt.accepted  { v, type, msg_id, envelope_sha256 }
bt.ping      { v, type, token }
bt.pong      { v, type, token }
bt.close     { v, type, code }
```

`BluetoothFrameCodec` uses a four-byte big-endian body length and the exact
limits in Global Constraints. Reject duplicate top-level JSON keys before
`JSONObject` parsing. `BluetoothSocketWire` exposes neutral `DirectCommand`s,
has exactly one flow collector, uses a `Mutex` for writes, and runs blocking
socket operations through `runInterruptible(Dispatchers.IO)`. Any timeout or
frame failure closes the socket.

- [x] **Step 5: Implement deterministic secure RFCOMM connection**

The lexicographically smaller Twinotify device ID is the normal client. The
server calls:

```kotlin
adapter.listenUsingRfcommWithServiceRecord("Twinotify", BluetoothConstants.RFCOMM_SERVICE_UUID)
```

The client calls:

```kotlin
device.createRfcommSocketToServiceRecord(BluetoothConstants.RFCOMM_SERVICE_UUID)
```

Cancel discovery before connect. Use a 12-second socket-connect ceiling, a
10-second handshake ceiling, and a delayed 15-second reverse attempt if the
normal client direction cannot connect. Accept only the CDM-resolved device;
close sockets from any other address before parsing bytes. Return only after
the signed handshake succeeds.

Complete the public association operation in this task: after CDM returns a
provisional association ID, resolve that exact device, run the signed handshake,
then save `BluetoothBinding(associationId, peer.deviceId,
sha256(peer.signingPublicKey))` and resolve `startBluetoothAssociation()`.
On timeout, cancellation, non-dual device, or identity/signature failure,
close the socket, discard all provisional state, request disassociation of that
exact provisional ID, and reject with one bounded error code. Never leave an
unverified association enabled.

- [x] **Step 6: Run JVM and socket instrumentation tests**

```bash
cd mobile/android
./gradlew --no-daemon :twinotify-core:testDebugUnitTest --tests 'co.twinotify.core.bluetooth.*'
./gradlew --no-daemon :twinotify-core:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=co.twinotify.core.bluetooth.BluetoothSocketWireTest
```

Expected: JVM tests PASS. The connected test PASSes on selected API 34+
hardware or is recorded pending if no device is attached.

- [x] **Step 7: Commit the authenticated RFCOMM wire**

```bash
git add mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/bluetooth mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/TwinotifyCoreModule.kt mobile/modules/twinotify-core/src/TwinotifyCoreModule.ts mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/bluetooth mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/bluetooth
git commit -m "feat(mobile/bluetooth): authenticate a bounded rfcomm stream"
```

---

### Task 4: Grant Bluetooth the single route lease and preserve fallback

**Files:**

- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/bluetooth/BluetoothTransport.kt`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/bluetooth/BluetoothTransportTest.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/TransportCoordinator.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/LiveTransportRoutes.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncService.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncServiceStatus.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/UnpairCustodyTracker.kt`
- Modify: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/TransportCoordinatorTest.kt`
- Modify: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/LiveTransportRoutesTest.kt`
- Modify: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/LiveServiceTransportTest.kt`
- Modify: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/UnpairCustodyTrackerTest.kt`

**Interfaces:**

- Consumes: `AuthenticatedBluetoothWire`, `DirectDelivery`, binding validation, and existing route health/custody reporting.
- Produces: `RouteKind.BLUETOOTH`, `CustodyRoute.BLUETOOTH`, `LiveTransportRoutes.bluetooth`, and coordinator promotion/fallback with a single drainer.

- [x] **Step 1: Write RED route-order and ownership tests**

Add:

```kotlin
@Test
fun directPreferenceOrdersLanThenBluetoothThenRelay() = runTest {
    val coordinator = coordinator(
        lan = FakeRoute(RouteKind.LAN, failOpen = true),
        bluetooth = FakeRoute(RouteKind.BLUETOOTH),
        relay = FakeRoute(RouteKind.RELAY),
        preferDirect = true,
    )
    val job = backgroundScope.launch { coordinator.run() }
    runCurrent()
    assertEquals(RouteKind.BLUETOOTH, coordinator.health.value.active)
    assertEquals(0, relay.opens)
    job.cancelAndJoin()
}

@Test
fun relayPromotesToBluetoothThenLanWithoutConcurrentDrain() = runTest {
    val fixture = promotionFixture(lanAvailable = false, bluetoothAvailable = false)
    fixture.start()
    fixture.makeBluetoothAvailable()
    fixture.requestDirectAttempt()
    fixture.awaitRoute(RouteKind.BLUETOOTH)
    fixture.makeLanAvailable()
    fixture.requestDirectAttempt()
    fixture.awaitRoute(RouteKind.LAN)
    assertTrue(fixture.store.maxConcurrentDrains <= 1)
    assertTrue(fixture.closedBeforeNextGrant)
}

@Test
fun relayPreferenceKeepsHealthyRelayAheadOfBothDirectRoutes() = runTest {
    val fixture = coordinatorFixture(preferDirect = false, allRoutesAvailable = true)
    fixture.start()
    assertEquals(RouteKind.RELAY, fixture.health().active)
    assertEquals(0, fixture.lan.opens)
    assertEquals(0, fixture.bluetooth.opens)
}
```

Also cover Bluetooth send failure to relay, unaccepted resend on LAN/relay,
accepted `BLUETOOTH` custody, candidate authentication cancellation, LAN
promotion closing Bluetooth before granting LAN, route health serialization,
and unpair custody over Bluetooth.

- [x] **Step 2: Run coordinator tests and observe RED**

```bash
cd mobile/android
./gradlew --no-daemon :twinotify-core:testDebugUnitTest --tests 'co.twinotify.core.service.TransportCoordinatorTest' --tests 'co.twinotify.core.service.LiveTransportRoutesTest' --tests 'co.twinotify.core.bluetooth.BluetoothTransportTest'
```

Expected: FAIL because route and custody enums do not contain Bluetooth and the
coordinator accepts only LAN/relay.

- [x] **Step 3: Build the Bluetooth route adapter**

`BluetoothTransport` delegates to:

```kotlin
DirectDelivery(
    wire = authenticatedWire,
    outbox = outbox,
    custodyRoute = CustodyRoute.BLUETOOTH,
    dispatch = dispatch,
)
```

`BluetoothRoute.open()` resolves and validates the current binding, connects
and authenticates, starts one delivery collector owned by the returned session,
and returns `RouteKind.BLUETOOTH`. It is not self-draining. Close sends bounded
`bt.close`, closes the socket, cancels/joins the worker in `NonCancellable`, and
completes one stable close code.

- [x] **Step 4: Generalize direct-route preference without parallel pumps**

Change live routes to:

```kotlin
data class LiveTransportRoutes(
    val lan: TransportRoute?,
    val bluetooth: TransportRoute?,
    val relay: TransportRoute?,
)
```

Change `TransportCoordinator` to receive `directRoutes` in preference order:

```kotlin
private val directRoutes: List<TransportRoute> = listOfNotNull(lan, bluetooth).also { routes ->
    require(routes.map { it.kind }.distinct().size == routes.size)
    require(routes.all { it.kind == RouteKind.LAN || it.kind == RouteKind.BLUETOOTH })
}
```

Rename its private constructor flag from `preferLan` to `preferDirect`; the
live factory passes the existing durable `ServiceConfig.preferLan` value into
that renamed parameter so no DataStore key or native bridge migration is
needed. Test fixtures use the same `preferDirect` name.

Replace the LAN-specific promotion state with one `DirectRetryState` per route.
Probe candidates in list order. A candidate may open while relay owns the lease;
after authentication, signal the relay owner to close, fully join its pump, and
only then publish/grant the direct session. While Bluetooth owns the lease,
periodically probe only the higher-priority LAN route. On any direct loss, try
relay immediately before the failed direct route's cooldown. Keep all current
backoff ceilings and direct-attempt request conflation.

When direct preference is false, use route order relay, LAN, Bluetooth and do
not probe direct candidates while relay remains authenticated.

- [x] **Step 5: Wire live factory, health, custody, and unpair**

`LiveTransportRoutesFactory` loads Bluetooth independently from LAN. A corrupt
LAN binding must not disable Bluetooth/relay; a stale Bluetooth association must
not disable LAN/relay. `SyncServiceStatus` serializes route as lowercase
`bluetooth`, presents `Direct Bluetooth`, and treats it as direct peer evidence.

Add:

```kotlin
fun acceptBluetoothUnpairCustody(
    event: DirectDeliveryEvent.PeerAccepted,
    tracker: UnpairCustodyTracker,
): Boolean = tracker.accept(event.msgId, CustodyRoute.BLUETOOTH)
```

On local or authenticated peer unpair, close the granted session before key
wipe, clear the local Bluetooth binding, and call CDM `disassociate` only for
the exact recorded association ID. Failure to remove the system association is
reported as bounded `bluetooth_disassociation_required` without restoring keys.

- [x] **Step 6: Run transport and service regression tests**

```bash
cd mobile/android
./gradlew --no-daemon :twinotify-core:testDebugUnitTest --tests 'co.twinotify.core.bluetooth.*' --tests 'co.twinotify.core.service.TransportCoordinatorTest' --tests 'co.twinotify.core.service.LiveTransportRoutesTest' --tests 'co.twinotify.core.service.LiveServiceTransportTest' --tests 'co.twinotify.core.service.UnpairCustodyTrackerTest'
```

Expected: PASS with `maxConcurrentDrains == 1` in every handoff case.

- [x] **Step 7: Commit route ownership and fallback**

```bash
git add mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/bluetooth mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/bluetooth mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service
git commit -m "feat(mobile/transport): add bluetooth route fallback"
```

---

### Task 5: Add truthful association UI and physical route evidence

**Files:**

- Modify: `mobile/app/settings/pair.tsx`
- Modify: `mobile/app/settings/index.tsx`
- Modify: `mobile/modules/twinotify-core/src/TwinotifyCoreModule.ts`
- Modify: `mobile/hooks/useRouteStatus.ts`
- Modify: `mobile/components/home/ConnectionSurface.tsx`
- Modify: `mobile/app/__tests__/settingsHandoffTrace.test.tsx`
- Modify: `mobile/app/__tests__/routeProductTruth.test.tsx`
- Modify: `mobile/components/home/__tests__/ConnectionSurface.test.tsx`
- Create: `mobile/app/pair/__tests__/bluetoothAssociationFlow.test.tsx`
- Create: `mobile/modules/twinotify-core/android/src/debug/java/co/twinotify/core/e2e/BluetoothRouteControl.kt`
- Create: `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/e2e/BluetoothRouteTest.kt`
- Create: `e2e/internal/scenario/bluetooth_route.go`
- Create: `e2e/internal/scenario/bluetooth_route_test.go`
- Modify: `e2e/internal/control/control.go`
- Modify: `e2e/README.md`
- Modify: `docs/test-scenarios.md`
- Create: `docs/evidence/bluetooth-route/README.md`

**Interfaces:**

- Consumes: native permission/association methods and route status `BLUETOOTH`.
- Produces: one functional pair-settings association row, truthful connection status, deterministic debug evidence, and a physical two-phone release gate.

- [x] **Step 1: Write RED product-truth and association-flow tests**

Assert these behaviors:

```tsx
it('explains that Bluetooth is data fallback and never promises call audio', async () => {
  render(<PairDetailScreen />);
  expect(await screen.findByText('Bluetooth fallback')).toBeTruthy();
  expect(screen.getByText('Keeps encrypted sync working nearby when Wi-Fi is unavailable. Call audio is not routed.')).toBeTruthy();
  expect(screen.queryByText(/talk on this phone/i)).toBeNull();
});

it('requests nearby permission before opening the system association picker', async () => {
  global.__TWINOTIFY_CORE__.requestBluetoothRoutePermissionAsync.mockResolvedValue(grantedPermission);
  fireEvent.press(await screen.findByRole('button', { name: 'Set up Bluetooth fallback' }));
  await waitFor(() => expect(global.__TWINOTIFY_CORE__.startBluetoothAssociation).toHaveBeenCalledTimes(1));
});

it('renders an authenticated Bluetooth route as direct Bluetooth', () => {
  renderConnection({ route: 'BLUETOOTH', phase: 'AUTHENTICATED' });
  expect(screen.getByText('Direct Bluetooth')).toBeTruthy();
});
```

Also test denial with and without `canAskAgain`, picker cancellation, stale
association, enable/disable rollback, exact remove confirmation, route handoff,
48 dp targets, and absence of a watch/audio claim.

- [x] **Step 2: Run UI tests and observe RED**

```bash
cd mobile
npm test -- --runInBand app/pair/__tests__/bluetoothAssociationFlow.test.tsx app/__tests__/settingsHandoffTrace.test.tsx app/__tests__/routeProductTruth.test.tsx components/home/__tests__/ConnectionSurface.test.tsx
```

Expected: FAIL because Bluetooth association and route presentation do not
exist.

- [x] **Step 3: Implement the settings interaction with existing primitives**

In `settings/pair.tsx`, use one `TwRow` for status and one text action only when
setup/removal is available:

```tsx
<TwRow
  title="Bluetooth fallback"
  subtitle={bluetoothAssociated
    ? 'Associated. Keeps encrypted sync working nearby when Wi-Fi is unavailable. Call audio is not routed.'
    : 'Keeps encrypted sync working nearby when Wi-Fi is unavailable. Call audio is not routed.'}
  onPress={bluetoothAssociated ? undefined : handleBluetoothAssociation}
  accessibilityLabel={bluetoothAssociated ? 'Bluetooth fallback, associated' : 'Set up Bluetooth fallback'}
  trailing={!bluetoothAssociated ? disclosure('pair-bluetooth-disclosure') : undefined}
  style={styles.ledgerRow}
/>
```

Rename only the visible copy of the existing Settings route preference; retain
the stored/native `preferLan` key for compatibility:

```tsx
<TwRow
  title="Prefer direct delivery"
  subtitle={preferLan
    ? 'Uses direct Wi-Fi first, then Bluetooth nearby, before the relay.'
    : 'Uses the relay first, with direct Wi-Fi and Bluetooth as backups.'}
  trailing={
    <View style={styles.controlSlot}>
      <TwSwitch
        checked={preferLan ?? false}
        onChange={handlePreferLanChange}
        size="md"
        disabled={preferLan === null}
        touchTargetSize={48}
        accessibilityLabel="Prefer direct delivery"
      />
    </View>
  }
  style={styles.ledgerRow}
/>
```

Extend every route union in `TwinotifyCoreModule.ts` and `useRouteStatus.ts`
from `LAN | RELAY` to `LAN | BLUETOOTH | RELAY`, mapping only the authenticated
native value to the label `Direct Bluetooth`. Unknown values continue to map to
the existing safe disconnected/unknown state.

Use an existing `TwSwitch` in Settings only after association to enable or
disable route attempts. Use rollback-safe persistence and keep the row absent
before association:

```tsx
const handleBluetoothRouteChange = useCallback(async (next: boolean) => {
  const previous = bluetoothRouteEnabled;
  setBluetoothRouteBusy(true);
  try {
    const durable = await TwinotifyCoreModule.setBluetoothRouteEnabled(next);
    setBluetoothRouteEnabled(durable);
    if (durable !== next) {
      Alert.alert('Bluetooth fallback unavailable', 'Nothing changed. Check Nearby devices permission and try again.');
    }
  } catch {
    setBluetoothRouteEnabled(previous);
    Alert.alert('Bluetooth fallback unavailable', 'Nothing changed. Try again.');
  } finally {
    setBluetoothRouteBusy(false);
  }
}, [bluetoothRouteEnabled]);

{bluetoothAssociated ? (
  <TwRow
    title="Use Bluetooth fallback"
    subtitle={bluetoothRouteEnabled
      ? 'On. Encrypted sync can use Bluetooth when higher-priority delivery is unavailable.'
      : 'Off. The association is kept until you remove it.'}
    trailing={
      <View style={styles.controlSlot}>
        <TwSwitch
          checked={bluetoothRouteEnabled}
          onChange={handleBluetoothRouteChange}
          size="md"
          touchTargetSize={48}
          disabled={bluetoothRouteBusy}
          accessibilityLabel={`Use Bluetooth fallback, ${bluetoothRouteEnabled ? 'On' : 'Off'}`}
        />
      </View>
    }
    style={styles.ledgerRow}
  />
) : null}
```

Removing the association requires:

```tsx
Alert.alert(
  'Remove Bluetooth fallback?',
  'Twinotify will stop nearby Bluetooth sync with this paired phone. Wi-Fi and relay pairing stay unchanged.',
  [
    { text: 'Cancel', style: 'cancel' },
    { text: 'Remove', style: 'destructive', onPress: removeBluetoothAssociation },
  ],
);
```

Use current Twinotify type, colors, spacing, `TwRow`, `TwSwitch`, and disclosure
mark. Add no icon tile, card, pill, gradient, glow, shadow, entrance animation,
or transport diagram to the product UI.

- [x] **Step 4: Add debug-only route controls and host tests**

The debug control surface exposes:

```json
{ "command": "route_fault", "route": "LAN", "enabled": true }
{ "command": "route_fault", "route": "RELAY", "enabled": true }
{ "command": "await_route", "route": "BLUETOOTH", "phase": "AUTHENTICATED", "timeout_ms": 15000 }
{ "command": "enqueue_fixture", "bytes": 1048576 }
{ "command": "await_peer_receipt", "timeout_ms": 15000 }
```

Release builds contain none of these components. Debug responses include only
route, phase, bounded error code, byte count, duration, and receipt status; no
Bluetooth address, device name, SSID, peer key, raw envelope, association ID,
or payload appears.

The Go scenario forces LAN and relay unavailable, waits for Bluetooth, sends a
small notification and a maximum-size fixture, verifies digest-backed custody
and peer receipt, restores LAN, and proves promotion closes Bluetooth before
LAN drains.

- [x] **Step 5: Run automated route gates**

```bash
cd mobile
npm test -- --runInBand app/pair/__tests__/bluetoothAssociationFlow.test.tsx app/__tests__/settingsHandoffTrace.test.tsx app/__tests__/routeProductTruth.test.tsx components/home/__tests__/ConnectionSurface.test.tsx
npm run typecheck
npm run lint
cd android
./gradlew --no-daemon :twinotify-core:lintDebug :twinotify-core:testDebugUnitTest :app:assembleDebug
cd ../../e2e
GOCACHE=/tmp/phone-sync-bluetooth-e2e-cache go test ./... -race -count=1
./scripts/preflight_test.sh
```

Expected: all commands PASS and no test observes concurrent outbox draining.

- [ ] **Step 6: Run the physical two-phone Bluetooth matrix**

On every project-owned target pair:

1. Pair Twinotify normally, associate Bluetooth on both phones, then disable
   Wi-Fi and make relay unreachable.
2. Verify state, notification, action invoke/result, call state, call control,
   snapshot, receipt, and unpair envelopes cross Bluetooth.
3. Test screens on/off, both apps backgrounded, force-stop/reopen, Bluetooth
   off/on, out-of-range/re-entry, reboot of each phone, permission revoke/grant,
   association removal, and competing headphones/car connection.
4. Send the exact maximum legal envelope and prove bounded memory, no GATT
   fragmentation path, and no silent drop.
5. Collect 100 small control round trips and require p95 under 750 ms.
6. Hold an idle authenticated route for 8 hours and record reconnect count,
   service survival, battery delta, and bounded logs.
7. Re-enable Wi-Fi and prove LAN promotion closes/joins Bluetooth before LAN
   owns the outbox.

Record model, build fingerprint hash, SDK, Bluetooth chipset/stack version when
available, app version, route transitions, counts, latency, and stable error
codes under `docs/evidence/bluetooth-route/`. Never record addresses, names,
keys, payloads, or caller data.

- [x] **Step 7: Perform the full interaction and security review**

Point-by-point anti-slop review: every visible control works by real pointer
tap, system picker cancellation returns cleanly, no dead switch is shown before
association, content is visible without animation, light/dark/large-text layouts
do not clip, targets are at least 48 dp, route text remains legible, no new
decorative pill/card/glow/gradient/shadow exists, and copy never implies HFP or
audio.

Security review: inspect the merged release manifest, verify `neverForLocation`,
prove no raw address is persisted/logged, verify the signed peer mismatch path,
prove the maximum frame bound, confirm one drainer during all promotions, and
confirm unpair names the exact CDM association before disassociation.

- [x] **Step 8: Commit UI and evidence**

```bash
git add mobile/app mobile/components mobile/hooks mobile/modules/twinotify-core/src e2e docs/test-scenarios.md docs/evidence/bluetooth-route
git commit -m "test(mobile/bluetooth): verify direct route behavior"
```
