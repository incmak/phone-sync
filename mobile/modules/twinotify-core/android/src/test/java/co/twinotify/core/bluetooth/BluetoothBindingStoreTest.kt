package co.twinotify.core.bluetooth

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import co.twinotify.core.storage.PeerRecord
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BluetoothBindingStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var stores = 0

    private fun newStore(): BluetoothBindingStore {
        val file = File(temporaryFolder.root, "bluetooth-${stores++}.preferences_pb")
        return BluetoothBindingStore(PreferenceDataStoreFactory.create(scope = storeScope) { file })
    }

    @After
    fun tearDown() {
        storeScope.cancel()
    }

    @Test
    fun savedBindingValidatesAgainstTheCurrentPeerAndLiveAssociation() = runTest {
        val store = newStore()

        store.save(BluetoothBinding(associationId = 41, peerDeviceId = PEER_ID, peerSigningKeySha256 = PEER_DIGEST, protocolVersion = 1))
        val loaded = store.loadValidated(peer(PEER_ID, PEER_KEY), setOf(7, 41))

        assertNotNull(loaded)
        assertEquals(41, loaded.associationId)
        assertEquals(PEER_ID, loaded.peerDeviceId)
        assertEquals(PEER_DIGEST, loaded.peerSigningKeySha256)
        assertEquals(BluetoothConstants.PROTOCOL_VERSION, loaded.protocolVersion)
        assertEquals(41, store.storedAssociationId())
    }

    @Test
    fun bindingIsRejectedAfterPeerOrAssociationChanges() = runTest {
        val store = newStore()

        store.save(BluetoothBinding(associationId = 41, peerDeviceId = PEER_ID, peerSigningKeySha256 = PEER_DIGEST, protocolVersion = 1))
        assertNotNull(store.loadValidated(peer(PEER_ID, PEER_KEY), setOf(41)))
        assertNull(store.loadValidated(peer("replacement", OTHER_KEY), setOf(41)))
        assertNull(store.loadValidated(peer(PEER_ID, PEER_KEY), emptySet()))
    }

    @Test
    fun staleAssociationClearsTheBindingAndRouteEnablement() = runTest {
        val store = newStore()
        store.save(BluetoothBinding(associationId = 41, peerDeviceId = PEER_ID, peerSigningKeySha256 = PEER_DIGEST))
        store.setRouteEnabled(true)
        assertTrue(store.routeEnabled())

        assertNull(store.loadValidated(peer(PEER_ID, PEER_KEY), setOf(40)))

        assertNull(store.storedAssociationId())
        assertFalse(store.routeEnabled())
        assertNull(store.loadValidated(peer(PEER_ID, PEER_KEY), setOf(41)))
    }

    @Test
    fun sameDeviceIdWithARotatedSigningKeyIsRejected() = runTest {
        val store = newStore()
        store.save(BluetoothBinding(associationId = 41, peerDeviceId = PEER_ID, peerSigningKeySha256 = PEER_DIGEST))

        assertNull(store.loadValidated(peer(PEER_ID, OTHER_KEY), setOf(41)))
        assertNull(store.storedAssociationId())
    }

    @Test
    fun unsupportedProtocolVersionIsRejected() = runTest {
        val store = newStore()
        store.save(BluetoothBinding(associationId = 41, peerDeviceId = PEER_ID, peerSigningKeySha256 = PEER_DIGEST, protocolVersion = 2))

        assertNull(store.loadValidated(peer(PEER_ID, PEER_KEY), setOf(41)))
        assertNull(store.storedAssociationId())
    }

    @Test
    fun disablingTheRouteKeepsTheBinding() = runTest {
        val store = newStore()
        store.save(BluetoothBinding(associationId = 41, peerDeviceId = PEER_ID, peerSigningKeySha256 = PEER_DIGEST))
        store.setRouteEnabled(true)

        store.setRouteEnabled(false)

        assertFalse(store.routeEnabled())
        assertNotNull(store.loadValidated(peer(PEER_ID, PEER_KEY), setOf(41)))
    }

    @Test
    fun clearRemovesBindingAndEnablement() = runTest {
        val store = newStore()
        store.save(BluetoothBinding(associationId = 41, peerDeviceId = PEER_ID, peerSigningKeySha256 = PEER_DIGEST))
        store.setRouteEnabled(true)

        store.clear()

        assertNull(store.storedAssociationId())
        assertFalse(store.routeEnabled())
    }

    @Test
    fun signingKeyDigestIsLowercaseHexSha256() {
        assertEquals(PEER_DIGEST, BluetoothBinding.signingKeyDigest(PEER_KEY))
    }

    private fun peer(deviceId: String, signingKey: ByteArray) =
        PeerRecord(deviceId, ByteArray(32) { 1 }, signingKey, "Peer")

    private companion object {
        const val PEER_ID = "peer-device"
        val PEER_KEY = ByteArray(32) { (it + 3).toByte() }
        val OTHER_KEY = ByteArray(32) { (it + 90).toByte() }
        val PEER_DIGEST: String = MessageDigest.getInstance("SHA-256").digest(PEER_KEY)
            .joinToString("") { "%02x".format(it) }
    }
}
