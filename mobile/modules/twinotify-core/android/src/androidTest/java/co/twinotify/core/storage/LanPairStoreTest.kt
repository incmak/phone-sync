package co.twinotify.core.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanPairStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun cleanUp() = runBlocking {
        LanPairStore.clear(context)
        PeerStore.clear(context)
    }

    @Test
    fun sealedBindingRoundTripsWithoutPlaintextInDataStore() = runBlocking {
        val peer = peer()
        val secret = bytes(0x41)
        PeerStore.save(context, peer)

        val prepared = LanPairStore.prepare(context, peer, binding(secret))
        LanPairStore.commit(context, prepared)

        val restored = assertNotNull(LanPairStore.loadValidated(context, PeerStore.load(context)!!))
        assertTrue(secret.contentEquals(restored.lanSecret), "LAN secret must round-trip")
        assertFalse(lanPreferencesBytes().containsSubsequence(secret))
        assertFalse(lanPreferencesBytes().containsSubsequence("peer_device_id".toByteArray()))
    }

    @Test
    fun peerRecordStoresOnlyPublicLanBindingMarker() = runBlocking {
        val peer = peer()
        PeerStore.save(context, peer)
        LanPairStore.commit(context, LanPairStore.prepare(context, peer, binding()))

        val saved = assertNotNull(PeerStore.load(context))
        assertNotNull(saved.lanBindingId)
        assertEquals(null, PeerRecord::class.java.declaredFields.firstOrNull { it.name.contains("secret", ignoreCase = true) })
    }

    @Test
    fun identityDigestNormalizesDisplayNameAndCoversEveryPublicIdentityField() {
        val canonical = peer(name = "Caf\u00e9")
        assertContentEquals(
            LanPairStore.identityDigest(canonical),
            LanPairStore.identityDigest(peer(name = "Cafe\u0301")),
        )
        assertNotEquals(
            LanPairStore.identityDigest(canonical).contentHashCode(),
            LanPairStore.identityDigest(peer(deviceId = "peer-b")).contentHashCode(),
        )
        assertNotEquals(
            LanPairStore.identityDigest(canonical).contentHashCode(),
            LanPairStore.identityDigest(peer(encSeed = 0x32)).contentHashCode(),
        )
        assertNotEquals(
            LanPairStore.identityDigest(canonical).contentHashCode(),
            LanPairStore.identityDigest(peer(signSeed = 0x33)).contentHashCode(),
        )
    }

    @Test
    fun recoveryRemovesUncommittedSealedBindingAndItNeverLoads() = runBlocking {
        val peer = peer()
        PeerStore.save(context, peer)
        val prepared = LanPairStore.prepare(context, peer, binding())
        LanPairStore.writeSealedForTest(context, prepared)

        assertNull(LanPairStore.loadValidated(context, peer))
        LanPairStore.recover(context, peer)
        assertNull(LanPairStore.sealedBindingIdForTest(context))
        assertNotNull(PeerStore.load(context), "recovery must retain the relay peer")
        Unit
    }

    @Test
    fun missingCorruptOrMismatchedBindingDisablesLanButRetainsRelayPeer() = runBlocking {
        val peer = peer()
        PeerStore.save(context, peer)
        LanPairStore.commit(context, LanPairStore.prepare(context, peer, binding()))
        val marked = assertNotNull(PeerStore.load(context))

        LanPairStore.clear(context)
        assertNull(LanPairStore.loadValidated(context, marked))
        assertNull(PeerStore.load(context)?.lanBindingId)
        assertEquals(marked.deviceId, PeerStore.load(context)?.deviceId)

        PeerStore.save(context, peer)
        LanPairStore.commit(context, LanPairStore.prepare(context, peer, binding()))
        val original = assertNotNull(PeerStore.load(context))
        LanPairStore.writeCorruptForTest(context)
        assertNull(LanPairStore.loadValidated(context, original))
        assertNull(PeerStore.load(context)?.lanBindingId)
        assertEquals(original.deviceId, PeerStore.load(context)?.deviceId)

        PeerStore.save(context, peer)
        LanPairStore.commit(context, LanPairStore.prepare(context, peer, binding()))
        val changedIdentity = peer(name = "another name", lanBindingId = PeerStore.load(context)?.lanBindingId)
        PeerStore.save(context, changedIdentity)
        assertNull(LanPairStore.loadValidated(context, changedIdentity))
        assertNull(PeerStore.load(context)?.lanBindingId)
        assertEquals(changedIdentity.deviceId, PeerStore.load(context)?.deviceId)
    }

    @Test
    fun committedBindingSurvivesRecreationAndIdempotentCommit() = runBlocking {
        val peer = peer()
        PeerStore.save(context, peer)
        val prepared = LanPairStore.prepare(context, peer, binding())
        LanPairStore.commit(context, prepared)
        val firstId = PeerStore.load(context)?.lanBindingId

        LanPairStore.commit(context, prepared)
        val recreated = assertNotNull(LanPairStore.loadValidated(context, PeerStore.load(context)!!))

        assertEquals(firstId, PeerStore.load(context)?.lanBindingId)
        assertEquals(1, recreated.protocolVersion)
    }

    @Test
    fun replacementOfLiveBindingIsRejectedUnlessItIsTheSamePreparedCommit() = runBlocking {
        val peer = peer()
        PeerStore.save(context, peer)
        val first = LanPairStore.prepare(context, peer, binding())
        LanPairStore.commit(context, first)

        val replacement = LanPairStore.prepare(context, PeerStore.load(context)!!, binding(secret = bytes(0x61)))
        val error = kotlin.test.assertFailsWith<LanPairStoreException> {
            LanPairStore.commit(context, replacement)
        }
        assertEquals(LanPairStoreFailure.REPLACEMENT_REJECTED, error.failure)
        assertEquals(PeerStore.load(context)?.lanBindingId, LanPairStore.sealedBindingIdForTest(context))
    }

    @Test
    fun bindingAndPeerInputsAndAccessorsAreDefensiveCopies() = runBlocking {
        val enc = bytes(0x20)
        val sign = bytes(0x30)
        val secret = bytes(0x40)
        val pin = bytes(0x50)
        val peer = PeerRecord("peer-a", enc, sign, "phone")
        val value = LanBinding(pin, secret, 1, 123L)
        enc[0] = 0
        sign[0] = 0
        pin[0] = 0
        secret[0] = 0

        assertEquals(0x20.toByte(), peer.encPubkey[0])
        assertEquals(0x30.toByte(), peer.signPubkey[0])
        assertEquals(0x50.toByte(), value.peerTlsSpkiSha256[0])
        assertEquals(0x40.toByte(), value.lanSecret[0])
        val exposed = value.lanSecret
        exposed[0] = 0
        assertEquals(0x40.toByte(), value.lanSecret[0])

        PeerStore.save(context, peer)
        LanPairStore.commit(context, LanPairStore.prepare(context, peer, value))
        assertTrue(
            LanPairStore.loadValidated(context, PeerStore.load(context)!!)?.lanSecret?.contentEquals(bytes(0x40)) == true,
            "stored LAN secret must retain its original value",
        )
    }

    private fun peer(
        deviceId: String = "peer-a",
        encSeed: Int = 0x21,
        signSeed: Int = 0x31,
        name: String? = "phone",
        lanBindingId: String? = null,
    ) = PeerRecord(deviceId, bytes(encSeed), bytes(signSeed), name, lanBindingId)

    private fun binding(secret: ByteArray = bytes(0x41)) = LanBinding(bytes(0x51), secret, 1, 123L)

    private fun bytes(seed: Int) = ByteArray(32) { (seed + it).toByte() }

    private fun lanPreferencesBytes(): ByteArray = File(context.filesDir, "datastore/twinotify_lan_pair.preferences_pb").readBytes()

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean =
        indices.any { start -> start + needle.size <= size && needle.indices.all { this[start + it] == needle[it] } }
}
