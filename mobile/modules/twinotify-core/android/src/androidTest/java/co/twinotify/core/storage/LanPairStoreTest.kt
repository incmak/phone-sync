package co.twinotify.core.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import co.twinotify.core.pairing.lan.AndroidOfflinePairingCommitter
import co.twinotify.core.pairing.lan.OfflinePairingCommit
import co.twinotify.core.pairing.lan.OfflinePairingCommitFence
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
    fun freshOfflineCommitCreatesPeerAndMarkerOnlyAfterSealedVerification() = runBlocking {
        val peer = peer(deviceId = "dev-00000000-0000-0000-0000-000000000011", name = "Fresh phone")
        assertNull(PeerStore.load(context))

        LanPairStore.commit(context, LanPairStore.prepare(context, peer, binding()))

        val committed = assertNotNull(PeerStore.load(context))
        assertEquals(peer.deviceId, committed.deviceId)
        assertContentEquals(peer.encPubkey, committed.encPubkey)
        assertContentEquals(peer.signPubkey, committed.signPubkey)
        assertEquals(peer.displayName, committed.displayName)
        assertNotNull(committed.lanBindingId)
        assertNotNull(LanPairStore.loadValidated(context, committed))
        Unit
    }

    @Test
    fun unpairFenceWaitsForPausedProductionCommitThenWipeCannotBeResurrected() = runBlocking {
        val enteredStoreBoundary = CountDownLatch(1)
        val releaseStoreBoundary = CountDownLatch(1)
        val fence = OfflinePairingCommitFence()
        val committer = AndroidOfflinePairingCommitter(context, null, fence) {
            enteredStoreBoundary.countDown()
            check(releaseStoreBoundary.await(5, TimeUnit.SECONDS))
        }
        val value = OfflinePairingCommit(
            "dev-00000000-0000-0000-0000-000000000099",
            "Paused peer",
            bytes(0x21), bytes(0x31), bytes(0x51), bytes(0x61), 1,
        )
        val commit = async(Dispatchers.IO) { committer.commit(value) }
        assertTrue(enteredStoreBoundary.await(5, TimeUnit.SECONDS))

        val unpair = async(Dispatchers.IO) {
            fence.close()
            PeerStore.clear(context)
            LanPairStore.clear(context)
        }
        delay(100)
        assertFalse(unpair.isCompleted, "wipe must wait until an entered commit leaves the fence")

        releaseStoreBoundary.countDown()
        assertTrue(commit.await())
        unpair.await()

        assertNull(PeerStore.load(context))
        assertNull(LanPairStore.sealedBindingIdForTest(context))
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
        assertFalse(LanPairStore.identityDigest(canonical).contentEquals(LanPairStore.identityDigest(peer(deviceId = "peer-b"))))
        assertFalse(LanPairStore.identityDigest(canonical).contentEquals(LanPairStore.identityDigest(peer(encSeed = 0x32))))
        assertFalse(LanPairStore.identityDigest(canonical).contentEquals(LanPairStore.identityDigest(peer(signSeed = 0x33))))
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
    fun recoverWithMissingOuterClearsCapturedLanMarkerAndRetainsRelayPeer() = runBlocking {
        val peer = peer()
        PeerStore.save(context, peer)
        LanPairStore.commit(context, LanPairStore.prepare(context, peer, binding()))
        val marked = assertNotNull(PeerStore.load(context))

        LanPairStore.clear(context)
        LanPairStore.recover(context, marked)

        val recovered = assertNotNull(PeerStore.load(context))
        assertEquals(marked.deviceId, recovered.deviceId)
        assertContentEquals(marked.encPubkey, recovered.encPubkey)
        assertContentEquals(marked.signPubkey, recovered.signPubkey)
        assertNull(recovered.lanBindingId)
        assertNull(LanPairStore.sealedBindingIdForTest(context))
    }

    @Test
    fun recoverWithStructurallyCorruptOuterClearsCapturedLanMarkerAndRetainsRelayPeer() = runBlocking {
        val peer = peer()
        PeerStore.save(context, peer)
        LanPairStore.commit(context, LanPairStore.prepare(context, peer, binding()))
        val marked = assertNotNull(PeerStore.load(context))

        LanPairStore.writeStructurallyCorruptForTest(context)
        LanPairStore.recover(context, marked)

        val recovered = assertNotNull(PeerStore.load(context))
        assertEquals(marked.deviceId, recovered.deviceId)
        assertContentEquals(marked.encPubkey, recovered.encPubkey)
        assertContentEquals(marked.signPubkey, recovered.signPubkey)
        assertNull(recovered.lanBindingId)
        assertNull(LanPairStore.sealedBindingIdForTest(context))
    }

    @Test
    fun missingCorruptOrMismatchedBindingDisablesLanButRetainsRelayPeer() = runBlocking {
        val peer = peer()
        PeerStore.save(context, peer)
        LanPairStore.commit(context, LanPairStore.prepare(context, peer, binding()))
        val marked = assertNotNull(PeerStore.load(context))

        LanPairStore.clear(context)
        LanPairStore.recover(context, marked)
        assertNull(PeerStore.load(context)?.lanBindingId)
        assertEquals(marked.deviceId, PeerStore.load(context)?.deviceId)

        PeerStore.save(context, peer)
        LanPairStore.commit(context, LanPairStore.prepare(context, peer, binding()))
        val original = assertNotNull(PeerStore.load(context))
        LanPairStore.writeCorruptForTest(context)
        LanPairStore.recover(context, original)
        assertNull(PeerStore.load(context)?.lanBindingId)
        assertEquals(original.deviceId, PeerStore.load(context)?.deviceId)

        PeerStore.save(context, peer)
        LanPairStore.commit(context, LanPairStore.prepare(context, peer, binding()))
        val changedIdentity = peer(name = "another name", lanBindingId = PeerStore.load(context)?.lanBindingId)
        PeerStore.save(context, changedIdentity)
        LanPairStore.recover(context, changedIdentity)
        assertNull(PeerStore.load(context)?.lanBindingId)
        assertEquals(changedIdentity.deviceId, PeerStore.load(context)?.deviceId)
    }

    @Test
    fun completeCommitLoadsAfterDedicatedDataStoreRecreation() = runBlocking {
        val peer = peer()
        PeerStore.save(context, peer)
        val prepared = LanPairStore.prepare(context, peer, binding())
        val file = File(context.filesDir, "datastore/lan-pair-recreate-${UUID.randomUUID()}.preferences_pb")
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val initial = LanPairStore.openForTest(
            context,
            PreferenceDataStoreFactory.create(scope = firstScope, produceFile = { file }),
        )
        val committedId = try {
            initial.commit(prepared)
            assertNotNull(PeerStore.load(context)?.lanBindingId)
        } finally {
            firstScope.coroutineContext[Job]!!.cancelAndJoin()
        }

        val recreatedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val recreated = LanPairStore.openForTest(
                context,
                PreferenceDataStoreFactory.create(scope = recreatedScope, produceFile = { file }),
            )
            val restored = assertNotNull(recreated.loadValidated(PeerStore.load(context)!!))

            assertEquals(committedId, PeerStore.load(context)?.lanBindingId)
            assertEquals(1, restored.protocolVersion)
        } finally {
            recreatedScope.coroutineContext[Job]!!.cancelAndJoin()
            file.delete()
        }
    }

    @Test
    fun idempotentCommitRetainsTheExistingBinding() = runBlocking {
        val peer = peer()
        PeerStore.save(context, peer)
        val prepared = LanPairStore.prepare(context, peer, binding())
        LanPairStore.commit(context, prepared)
        val firstId = PeerStore.load(context)?.lanBindingId

        LanPairStore.commit(context, prepared)
        val restored = assertNotNull(LanPairStore.loadValidated(context, PeerStore.load(context)!!))

        assertEquals(firstId, PeerStore.load(context)?.lanBindingId)
        assertEquals(1, restored.protocolVersion)
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
