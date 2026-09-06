package co.twinotify.core.pairing.lan

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A Wi-Fi network request cannot be satisfied while the radio is off, so waiting the full
 * rendezvous timeout for a callback that can never fire only delays the LAN route reporting what
 * it already knows.
 *
 * Measured on two phones: with Wi-Fi off, every LAN open failed with `wifi_unavailable` after
 * 15,010ms, once per retry cycle, forever. The condition was known in microseconds.
 *
 * The ordering is the part that can regress silently, and neither a JVM nor an instrumented test
 * can toggle a real radio, so it is pinned here the same way the nearby and Bluetooth permission
 * contracts are.
 */
class PairingWifiPreconditionTest {
    private val projectDir = File(requireNotNull(System.getProperty("user.dir")))
    private val source = File(
        projectDir,
        "src/main/java/co/twinotify/core/pairing/lan/PairingWifiNetworkSelector.kt",
    ).readText()

    @Test
    fun radioOffIsUnavailableAndRadioOnStillWaits() {
        assertEquals(
            PairingWifiNetworkFailure.UNAVAILABLE,
            PairingWifiPrecondition.failureOrNull(radioEnabled = false),
        )
        // On but not yet associated must still wait: the network may be seconds from arriving,
        // and failing early would push out the route's retry cooldown for nothing.
        assertNull(PairingWifiPrecondition.failureOrNull(radioEnabled = true))
    }

    @Test
    fun theRadioIsCheckedBeforeAnyCallbackIsRegistered() {
        val guardAt = source.indexOf("PairingWifiPrecondition.failureOrNull")
        val registerAt = source.indexOf("registerNetworkCallback")

        assertTrue(guardAt > 0, "the precondition guard is gone")
        assertTrue(registerAt > 0)
        assertTrue(
            guardAt < registerAt,
            "the radio must be checked before a callback is registered, or the timeout is spent anyway",
        )
    }

    @Test
    fun readingTheRadioStateIsDeclaredInTheManifest() {
        val manifest = File(projectDir, "src/main/AndroidManifest.xml").readText()

        assertTrue(
            manifest.contains("android.permission.ACCESS_WIFI_STATE"),
            "isWifiEnabled returns false without this permission, which would disable the LAN route",
        )
    }
}
