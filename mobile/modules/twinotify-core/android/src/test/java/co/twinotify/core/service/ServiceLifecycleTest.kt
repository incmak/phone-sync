package co.twinotify.core.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Policy-only lifecycle tests.  These deliberately do not construct an Android Service so the
 * restart/boot contract remains executable on the JVM and cannot be hidden behind framework
 * behavior.
 */
class ServiceLifecycleTest {
    private val paired = true
    private val relayUrl = "wss://relay.example.test/ws"

    @Test
    fun nullStickyRestart_enabledPairedAndConfigured_starts() {
        val decision = ServiceStartPolicy.decide(
            intentAction = null,
            persisted = ServiceConfig(enabled = true, relayUrl = relayUrl),
            paired = paired,
        )

        assertIs<ServiceStartDecision.Start>(decision)
        assertEquals(relayUrl, decision.relayUrl)
    }

    @Test
    fun nullStickyRestart_disabled_stops() {
        val decision = ServiceStartPolicy.decide(
            intentAction = null,
            persisted = ServiceConfig(enabled = false, relayUrl = relayUrl),
            paired = paired,
        )

        assertIs<ServiceStartDecision.Stop>(decision)
    }

    @Test
    fun bootWhileDisabled_doesNotStart() {
        val decision = ServiceStartPolicy.decide(
            intentAction = ServiceStartPolicy.BOOT_ACTION,
            persisted = ServiceConfig(enabled = false, relayUrl = relayUrl),
            paired = paired,
        )

        assertIs<ServiceStartDecision.Stop>(decision)
    }

    @Test
    fun userStop_persistsDisabledBeforeShutdown() {
        val stopped = ServiceStartPolicy.applyUserStop(
            ServiceConfig(enabled = true, relayUrl = relayUrl),
        )

        assertFalse(stopped.enabled)
        assertEquals(relayUrl, stopped.relayUrl)
    }

    @Test
    fun userStart_persistsUrlAndEnabledIndependentlyOfSocket() {
        val started = ServiceStartPolicy.applyUserStart(
            persisted = ServiceConfig(enabled = false, relayUrl = null),
            relayUrl = relayUrl,
        )

        assertTrue(started.enabled)
        assertEquals(relayUrl, started.relayUrl)
    }

    @Test
    fun pairingSuccess_isNotRolledBackWhenServiceStartupFails() {
        val pairedConfig = ServiceStartPolicy.applyUserStart(
            persisted = ServiceConfig(enabled = false, relayUrl = null),
            relayUrl = relayUrl,
        )

        // Pairing owns the peer record; a later socket failure only changes health.
        assertTrue(pairedConfig.enabled)
        assertEquals(relayUrl, pairedConfig.relayUrl)
        assertIs<ServiceStartDecision.Start>(
            ServiceStartPolicy.decide(null, pairedConfig, paired = true),
        )
    }

    @Test
    fun healthSnapshot_neverReportsConnectedWhenTransportIsOffline() {
        SyncServiceStatus.setState(SyncState.CONNECTED)
        SyncServiceStatus.setState(SyncState.OFFLINE_QUEUED)
        SyncServiceStatus.setQueueStats(count = 3, bytes = 42)
        SyncServiceStatus.setLastError("socket_closed")

        val health = SyncServiceStatus.health.value
        assertEquals("degraded", health.service)
        assertEquals("offline", health.transport)
        assertEquals(3, health.queuedCount)
        assertEquals(42, health.queuedBytes)
        assertEquals("socket_closed", health.lastErrorCode)
        assertEquals("OFFLINE_QUEUED", health.toEventMap()["state"])
    }

    @Test
    fun authenticatedRecovery_clearsStaleTransportError() {
        SyncServiceStatus.setLastError("invalid_relay_url")
        SyncServiceStatus.setState(SyncState.CONNECTED)

        assertEquals("connected", SyncServiceStatus.health.value.service)
        assertEquals("online", SyncServiceStatus.health.value.transport)
        assertEquals(null, SyncServiceStatus.health.value.lastErrorCode)
    }
}
