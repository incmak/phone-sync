package co.twinotify.core.e2e

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import co.twinotify.core.storage.PeerStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/**
 * Closed-world contract for the Bluetooth route debug controls.
 *
 * These assertions do not need an associated Bluetooth peer, and deliberately
 * do not pretend to have one: the "matched" branch of AWAIT_ROUTE and the
 * durable half of ENQUEUE_FIXTURE are the parts that require two associated
 * phones, and each is either asserted through its timeout branch or skipped
 * with a recorded reason rather than faked.
 */
@RunWith(AndroidJUnit4::class)
class BluetoothRouteTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val receiver = E2eControlReceiver()

    @After
    fun clearFaults() {
        val prefs = context.getSharedPreferences("e2e-control", Context.MODE_PRIVATE)
        prefs.edit().apply {
            for (route in listOf("LAN", "BLUETOOTH", "RELAY")) remove(BluetoothRouteControl.faultKey(route))
        }.apply()
    }

    @Test
    fun routeFaultWritesTheBoundedKeyEachLiveRouteReads() {
        val token = E2eSessionToken.forTest(context, "route-fault")
        val prefs = context.getSharedPreferences("e2e-control", Context.MODE_PRIVATE)
        for (route in listOf("LAN", "BLUETOOTH", "RELAY")) {
            val key = BluetoothRouteControl.faultKey(route)
            assertEquals(route.lowercase() + "_fault_until_ms", key)
            assertEquals(
                "ok",
                receiver.executeForTest(
                    context,
                    E2eCommand("route-fault-$route-on", "ROUTE_FAULT", token = token, params = mapOf("route" to route, "enabled" to "true")),
                ).code,
            )
            assertTrue(prefs.getLong(key, 0L) > System.currentTimeMillis(), "fault window for $route")
            assertEquals(
                "ok",
                receiver.executeForTest(
                    context,
                    E2eCommand("route-fault-$route-off", "ROUTE_FAULT", token = token, params = mapOf("route" to route, "enabled" to "false")),
                ).code,
            )
            assertFalse(prefs.contains(key), "restored fault for $route")
        }
        // LAN must stay one flag: SET_LAN_AVAILABLE and ROUTE_FAULT can never disagree.
        assertEquals(
            "ok",
            receiver.executeForTest(
                context,
                E2eCommand("lan-legacy-fault", "SET_LAN_AVAILABLE", token = token, params = mapOf("available" to "false")),
            ).code,
        )
        assertTrue(prefs.getLong(BluetoothRouteControl.faultKey("LAN"), 0L) > System.currentTimeMillis())
    }

    @Test
    fun routeControlsRejectEverythingOutsideTheClosedContract() {
        val token = E2eSessionToken.forTest(context, "route-closed-world")
        assertEquals(
            "unauthorized",
            receiver.executeForTest(
                context,
                E2eCommand("route-wrong-token", "ROUTE_FAULT", token = "wrong", params = mapOf("route" to "BLUETOOTH", "enabled" to "true")),
            ).code,
        )
        val rejected = listOf(
            E2eCommand("route-missing", "ROUTE_FAULT", token = token),
            E2eCommand("route-lowercase", "ROUTE_FAULT", token = token, params = mapOf("route" to "bluetooth", "enabled" to "true")),
            E2eCommand("route-unknown", "ROUTE_FAULT", token = token, params = mapOf("route" to "WIFI", "enabled" to "true")),
            E2eCommand("route-enabled", "ROUTE_FAULT", token = token, params = mapOf("route" to "LAN", "enabled" to "maybe")),
            E2eCommand("route-extra", "ROUTE_FAULT", token = token, params = mapOf("route" to "LAN", "enabled" to "true", "address" to "AA:BB:CC:DD:EE:FF")),
            E2eCommand("await-unknown-phase", "AWAIT_ROUTE", token = token, params = mapOf("route" to "BLUETOOTH", "phase" to "CONNECTED", "timeout_ms" to "50")),
            E2eCommand("await-unbounded", "AWAIT_ROUTE", token = token, params = mapOf("route" to "BLUETOOTH", "phase" to "AUTHENTICATED", "timeout_ms" to "60000")),
            E2eCommand("await-zero", "AWAIT_ROUTE", token = token, params = mapOf("route" to "BLUETOOTH", "phase" to "AUTHENTICATED", "timeout_ms" to "0")),
            E2eCommand("await-receipt-unbounded", "AWAIT_PEER_RECEIPT", token = token, params = mapOf("timeout_ms" to "60000")),
            E2eCommand("fixture-zero", "ENQUEUE_FIXTURE", token = token, params = mapOf("bytes" to "0")),
            E2eCommand("fixture-oversize", "ENQUEUE_FIXTURE", token = token, params = mapOf("bytes" to "1048577")),
            E2eCommand("fixture-extra", "ENQUEUE_FIXTURE", token = token, params = mapOf("bytes" to "1024", "association_id" to "7")),
        )
        for (command in rejected) {
            val result = receiver.executeForTest(context, command)
            assertEquals("invalid", result.code, "${command.requestId} -> ${result.toJson()}")
        }
    }

    @Test
    fun blockingRouteControlsReleaseTheBroadcastQueueBeforeWaiting() {
        // Android delivers this process's broadcasts one at a time, so any command
        // that waits must be in the early-release set or the receiver deadlocks.
        for (command in listOf("AWAIT_ROUTE", "AWAIT_PEER_RECEIPT", "ENQUEUE_FIXTURE")) {
            assertTrue(
                E2eControlReceiver.releasesBroadcastEarlyForTest(command),
                "$command must release the broadcast queue before it waits",
            )
        }
    }

    @Test
    fun awaitRouteReportsABoundedTimeoutAndNoDeviceIdentifiers() {
        val token = E2eSessionToken.forTest(context, "await-route")
        val result = receiver.executeForTest(
            context,
            E2eCommand("await-route", "AWAIT_ROUTE", token = token, params = mapOf("route" to "BLUETOOTH", "phase" to "AUTHENTICATED", "timeout_ms" to "50")),
        )
        assertEquals("ok", result.code)
        val payload = assertNotNull(result.payload)
        assertClosedKeys(payload, setOf("route", "phase", "status", "elapsed_ms"))
        // Without an associated peer the only reachable branch is the timeout, and
        // that is exactly what must be observable: nothing here can claim a route.
        assertEquals("timeout", payload.getString("status"))
        assertTrue(payload.getLong("elapsed_ms") in 0..50)
        assertNoDeviceIdentifiers(payload)
    }

    @Test
    fun awaitPeerReceiptReportsABoundedCountAndNoDeviceIdentifiers() {
        val token = E2eSessionToken.forTest(context, "await-receipt")
        val result = receiver.executeForTest(
            context,
            E2eCommand("await-receipt", "AWAIT_PEER_RECEIPT", token = token, params = mapOf("timeout_ms" to "50")),
        )
        assertEquals("ok", result.code)
        val payload = assertNotNull(result.payload)
        assertClosedKeys(payload, setOf("status", "awaiting_peer_count", "elapsed_ms"))
        assertTrue(payload.getInt("awaiting_peer_count") in 0..2_000)
        assertTrue(payload.getLong("elapsed_ms") in 0..50)
        assertNoDeviceIdentifiers(payload)
    }

    @Test
    fun fixturePaddingStaysUnderTheProtocolEnvelopeMaximum() {
        assertEquals(1_048_576, BluetoothRouteControl.MAX_FIXTURE_BYTES)
        val padding = BluetoothRouteControl.paddingFor(BluetoothRouteControl.MAX_FIXTURE_BYTES)
        assertTrue(padding in 1 until BluetoothRouteControl.MAX_FIXTURE_BYTES)
        // Base64 expansion of the padding alone must still leave room for the
        // inner event, nonce, and envelope framing.
        assertTrue((padding.toLong() * 4L / 3L) < BluetoothRouteControl.MAX_FIXTURE_BYTES)
        assertEquals(1, BluetoothRouteControl.paddingFor(1))
    }

    @Test
    fun maximumFixtureAuthorsOneBoundedEnvelopeWhenPaired() {
        val paired = runBlocking { PeerStore.load(context) } != null
        // Recorded reason: authoring a real envelope needs a paired peer, which
        // only the two-phone physical run has. Nothing here fabricates one.
        assumeTrue("no paired peer on this device; see docs/evidence/bluetooth-route", paired)
        val token = E2eSessionToken.forTest(context, "enqueue-fixture")
        val result = receiver.executeForTest(
            context,
            E2eCommand("enqueue-fixture", "ENQUEUE_FIXTURE", token = token, params = mapOf("bytes" to "1048576")),
        )
        assertEquals("ok", result.code, result.toJson().toString())
        val payload = assertNotNull(result.payload)
        assertClosedKeys(payload, setOf("bytes", "status", "elapsed_ms"))
        assertEquals("enqueued", payload.getString("status"))
        assertTrue(payload.getLong("bytes") in 1..BluetoothRouteControl.MAX_FIXTURE_BYTES.toLong())
        assertNoDeviceIdentifiers(payload)
    }

    private fun assertClosedKeys(payload: JSONObject, allowed: Set<String>) {
        val keys = payload.keys().asSequence().toSet()
        assertEquals(allowed, keys, "payload keys $keys")
    }

    private fun assertNoDeviceIdentifiers(payload: JSONObject) {
        val text = payload.toString().lowercase()
        for (forbidden in listOf(
            "address", "mac", "name", "ssid", "bssid", "association",
            "uuid", "key", "envelope", "ciphertext", "title",
        )) {
            assertFalse(text.contains(forbidden), "payload leaked $forbidden: $payload")
        }
    }
}
