package co.twinotify.core.service

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import co.twinotify.core.storage.PeerRecord
import co.twinotify.core.storage.PeerStore
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Two-phase emulator proof for an in-place APK replacement. Invoke once with
 * `-e upgradePhase seed`, reinstall the signed test APK with `adb install -r`, then invoke
 * with `-e upgradePhase verify`. Without that explicit argument these tests skip,
 * so the ordinary instrumentation suite remains hermetic.
 */
class TransportRecoveryPersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val phase: String?
        get() = InstrumentationRegistry.getArguments().getString("upgradePhase")

    @Test
    fun seedUpgradeFixture() = runBlocking {
        assumeTrue("explicit seed phase required", phase == "seed")
        ServiceConfigStore.clear(context)
        PeerStore.clear(context)
        ServiceConfigStore.setRelayUrl(context, RELAY_URL)
        ServiceConfigStore.setEnabled(context, true, now = CHANGE_AT)
        PeerStore.save(
            context,
            PeerRecord(
                deviceId = PEER_ID,
                encPubkey = ENC_KEY,
                signPubkey = SIGN_KEY,
                displayName = PEER_NAME,
            ),
        )

        assertFixture()
    }

    @Test
    fun verifyUpgradeFixture() = runBlocking {
        assumeTrue("explicit verify phase required", phase == "verify")
        assertFixture()
    }

    @Test
    fun recoverEnabledFixtureIsIdempotent() = runBlocking {
        assumeTrue("explicit recovery phase required", phase == "recover")
        assertFixture()

        val first = TransportRecoveryAuthority.recover(
            context,
            RecoveryTrigger.APP_FOREGROUND,
        )
        assertTrue(
            first == RecoveryExecution.Started ||
                first == RecoveryExecution.Coalesced ||
                first == RecoveryExecution.AlreadyRunning,
            "enabled fixture did not enter a recoverable start path: $first",
        )
        repeat(100) {
            if (SyncService.isActive()) return@repeat
            SystemClock.sleep(20)
        }
        assertTrue(SyncService.isActive(), "recovery did not create the service")

        assertEquals(
            RecoveryExecution.AlreadyRunning,
            TransportRecoveryAuthority.recover(context, RecoveryTrigger.APP_FOREGROUND),
        )
        assertTrue(ServiceConfigStore.read(context).enabled)
    }

    @Test
    fun seedPausedUpgradeFixture() = runBlocking {
        assumeTrue("explicit paused seed phase required", phase == "pausedSeed")
        ServiceConfigStore.clear(context)
        PeerStore.clear(context)
        ServiceConfigStore.setRelayUrl(context, RELAY_URL)
        ServiceConfigStore.setEnabled(context, false, now = CHANGE_AT)
        PeerStore.save(
            context,
            PeerRecord(
                deviceId = PEER_ID,
                encPubkey = ENC_KEY,
                signPubkey = SIGN_KEY,
                displayName = PEER_NAME,
            ),
        )
        context.stopService(Intent(context, SyncService::class.java))
        repeat(100) {
            if (!SyncService.isActive()) return@repeat
            SystemClock.sleep(20)
        }
        assertPausedFixture()
        assertTrue(!SyncService.isActive(), "paused fixture retained a transport service")
    }

    @Test
    fun verifyPausedUpgradeFixture() = runBlocking {
        assumeTrue("explicit paused verify phase required", phase == "pausedVerify")
        assertPausedFixture()
        assertEquals(
            RecoveryExecution.NoAction("disabled"),
            TransportRecoveryAuthority.recover(context, RecoveryTrigger.PACKAGE_REPLACED),
        )
        assertTrue(!SyncService.isActive(), "paused intent created a transport service")
    }

    private suspend fun assertFixture() {
        val config = ServiceConfigStore.read(context)
        val peer = assertNotNull(PeerStore.load(context))
        assertTrue(config.enabled)
        assertEquals(RELAY_URL, config.relayUrl)
        assertEquals(CHANGE_AT, config.lastUserChangeAt)
        assertEquals(PEER_ID, peer.deviceId)
        assertEquals(PEER_NAME, peer.displayName)
        assertContentEquals(ENC_KEY, peer.encPubkey)
        assertContentEquals(SIGN_KEY, peer.signPubkey)
    }

    private suspend fun assertPausedFixture() {
        val config = ServiceConfigStore.read(context)
        val peer = assertNotNull(PeerStore.load(context))
        assertTrue(!config.enabled)
        assertEquals(RELAY_URL, config.relayUrl)
        assertEquals(CHANGE_AT, config.lastUserChangeAt)
        assertEquals(PEER_ID, peer.deviceId)
    }

    private companion object {
        const val RELAY_URL = "wss://127.0.0.1:9"
        const val CHANGE_AT = 1_788_192_000_000L
        const val PEER_ID = "pb008-emulator-peer"
        const val PEER_NAME = "Upgrade fixture"
        val ENC_KEY = ByteArray(32) { (it + 1).toByte() }
        val SIGN_KEY = ByteArray(32) { (it + 65).toByte() }
    }
}
