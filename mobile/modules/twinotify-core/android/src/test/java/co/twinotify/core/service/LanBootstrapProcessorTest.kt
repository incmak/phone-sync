package co.twinotify.core.service

import co.twinotify.core.lan.LanBootstrapIdentity
import co.twinotify.core.lan.LanBootstrapMaterial
import co.twinotify.core.storage.LanBinding
import co.twinotify.core.storage.PeerRecord
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class LanBootstrapProcessorTest {
    private val local = LanBootstrapIdentity("local-device", bytes(0x10), bytes(0x20))
    private val peer = PeerRecord("peer-device", bytes(0x30), bytes(0x40), "Peer")
    private val localSecret = bytes(0x50)
    private val localPin = bytes(0x60)
    private val derivedSecret = bytes(0x70)
    private val contextDigest = bytes(0x80)

    @Test
    fun contextMismatchRejectsBeforeAnnouncementOrStoreMutation() = runTest {
        val fixture = fixture()

        val result = fixture.processor.process(payload(context = bytes(0x81)))

        assertEquals(LanBootstrapProcessResult.Rejected("lan_bootstrap_context_mismatch"), result)
        assertEquals(emptyList(), fixture.announcements)
        assertNull(fixture.committed)
    }

    @Test
    fun absentBindingDurablyAnnouncesBeforeCommittingDerivedTrust() = runTest {
        val order = mutableListOf<String>()
        val fixture = fixture(
            announce = { order += "announcement" },
            commit = { _, _ -> order += "binding" },
        )

        val result = fixture.processor.process(payload())

        assertEquals(LanBootstrapProcessResult.Applied(bindingChanged = true), result)
        assertEquals(listOf("announcement", "binding"), order)
        assertContentEquals(bytes(0x90), fixture.committed?.peerTlsSpkiSha256)
        assertContentEquals(derivedSecret, fixture.committed?.lanSecret)
        assertEquals(1, fixture.committed?.protocolVersion)
        assertEquals(5_000, fixture.committed?.pairedAtMillis)
        assertContentEquals(localPin, fixture.announcements.single().tlsSpkiSha256.hexToBytes())
        assertContentEquals(contextDigest, fixture.announcements.single().bindingContextSha256.hexToBytes())
    }

    @Test
    fun sameTrustMaterialIsIdempotentButStillEnsuresOwnAnnouncement() = runTest {
        val fixture = fixture(
            existing = LanBinding(bytes(0x90), derivedSecret, 1, pairedAtMillis = 1),
        )

        val result = fixture.processor.process(payload())

        assertEquals(LanBootstrapProcessResult.Applied(bindingChanged = false), result)
        assertEquals(1, fixture.announcements.size)
        assertNull(fixture.committed)
    }

    @Test
    fun differingNearbyBindingIsPreservedAsConflict() = runTest {
        val existing = LanBinding(bytes(0x91), bytes(0x92), 1, pairedAtMillis = 1)
        val fixture = fixture(existing = existing)

        val result = fixture.processor.process(payload())

        assertEquals(LanBootstrapProcessResult.Rejected("lan_binding_conflict"), result)
        assertTrue(fixture.existing === existing)
        assertNull(fixture.committed)
        assertEquals(emptyList(), fixture.announcements)
    }

    @Test
    fun cryptoAndStoreFailuresMapToBoundedCodesWithoutClaimingBindingChange() = runTest {
        val cryptoFailure = fixture(derive = { _, _, _ -> error("private crypto detail") })
        assertEquals(
            LanBootstrapProcessResult.Rejected("lan_bootstrap_crypto_unavailable"),
            cryptoFailure.processor.process(payload()),
        )

        val storeFailure = fixture(commit = { _, _ -> error("private store detail") })
        assertEquals(
            LanBootstrapProcessResult.Rejected("lan_bootstrap_store_failed"),
            storeFailure.processor.process(payload()),
        )
        assertNull(storeFailure.committed)
    }

    @Test
    fun malformedPayloadIsBoundedAndNeverTouchesTrust() = runTest {
        val fixture = fixture()

        val result = fixture.processor.process(payload().copy(protocolVersion = 2))

        assertEquals(LanBootstrapProcessResult.Rejected("lan_bootstrap_payload_invalid"), result)
        assertFalse(fixture.announcements.isNotEmpty())
        assertNull(fixture.committed)
    }

    private fun fixture(
        existing: LanBinding? = null,
        derive: (LanBootstrapIdentity, LanBootstrapIdentity, ByteArray) -> LanBootstrapMaterial =
            { _, _, _ -> LanBootstrapMaterial(derivedSecret, contextDigest) },
        announce: suspend (LanBootstrapPayload) -> Unit = {},
        commit: suspend (PeerRecord, LanBinding) -> Unit = { _, _ -> },
    ) = Fixture(existing, derive, announce, commit)

    private inner class Fixture(
        val existing: LanBinding?,
        derive: (LanBootstrapIdentity, LanBootstrapIdentity, ByteArray) -> LanBootstrapMaterial,
        announce: suspend (LanBootstrapPayload) -> Unit,
        commit: suspend (PeerRecord, LanBinding) -> Unit,
    ) {
        val announcements = mutableListOf<LanBootstrapPayload>()
        var committed: LanBinding? = null
        val processor = DefaultLanBootstrapProcessor(
            loadIdentities = { LanBootstrapIdentityState(local, peer, localSecret) },
            localTlsPin = { localPin.copyOf() },
            derive = derive,
            loadBinding = { existing },
            ensureAnnouncement = { value -> announcements += value; announce(value) },
            commitBinding = { actualPeer, binding ->
                commit(actualPeer, binding)
                committed = binding
            },
            clock = { 5_000 },
        )
    }

    private fun payload(
        pin: ByteArray = bytes(0x90),
        context: ByteArray = contextDigest,
    ) = LanBootstrapPayload(
        tlsSpkiSha256 = pin.toHex(),
        bindingContextSha256 = context.toHex(),
    )

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun String.hexToBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun bytes(seed: Int) = ByteArray(32) { (seed + it).toByte() }
}
