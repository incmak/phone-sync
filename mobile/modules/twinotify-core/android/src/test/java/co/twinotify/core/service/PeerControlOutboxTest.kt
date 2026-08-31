package co.twinotify.core.service

import co.twinotify.core.protocol.EncryptedEnvelope
import co.twinotify.core.protocol.InnerEventV2
import co.twinotify.core.protocol.ProtocolJson
import co.twinotify.core.storage.OutboundMessage
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.json.JSONObject

class PeerControlOutboxTest {
    private var now = 1_000L
    private var nextId = 0
    private val events = mutableListOf<Pair<InnerEventV2, Boolean>>()
    private val store = FakePeerControlStore()
    private val sealer = PeerControlSealer { event, requiresPeerReceipt ->
        events += event to requiresPeerReceipt
        row(event, requiresPeerReceipt)
    }
    private val outbox by lazy {
        PeerControlOutbox(
            store = store,
            sealer = sealer,
            originDevice = { "local-device" },
            clock = { now },
            newId = { (++nextId).canonicalUuid() },
        )
    }

    @Test
    fun bootstrapUsesTenMinuteReceiptBackedEnvelopeAndReusesGeneration() = runTest {
        val payload = LanBootstrapPayload(
            tlsSpkiSha256 = "1".repeat(64),
            bindingContextSha256 = "2".repeat(64),
        )

        val first = outbox.ensureBootstrap(generation = 7, payload)
        val duplicate = outbox.ensureBootstrap(generation = 7, payload)

        assertSame(first, duplicate)
        assertEquals(1, store.inserted.size)
        assertEquals("lan.bootstrap", first.eventType)
        assertTrue(first.requiresPeerReceipt)
        val (event, requiresReceipt) = events.single()
        assertTrue(requiresReceipt)
        assertEquals(601_000L, event.expiresAt)
        assertNull(event.canonId)
        assertNull(event.sequence)
        assertEquals(1, JSONObject(event.payloadJson).getInt("protocol_version"))
    }

    @Test
    fun probeUsesTwoMinuteTtlMatchesMessageIdAndAllowsOnlyOneUnexpiredRow() = runTest {
        val first = assertNotNull(outbox.ensureProbe(generation = 3, requestDirect = true))

        assertNull(outbox.ensureProbe(generation = 3, requestDirect = false))
        assertEquals(1, store.inserted.size)
        val event = events.single().first
        assertEquals("peer.probe", event.type)
        assertEquals(event.msgId, JSONObject(event.payloadJson).getString("probe_id"))
        assertEquals(121_000L, event.expiresAt)
        assertEquals(true, JSONObject(event.payloadJson).getBoolean("request_direct"))
        assertTrue(first.requiresPeerReceipt)
    }

    @Test
    fun recoveredUnexpiredProbeBlocksOnlyUntilItsInnerExpiry() = runTest {
        store.recovered = row(
            InnerEventV2(
                msgId = 99.canonicalUuid(),
                originDevice = "local-device",
                type = "peer.probe",
                canonId = null,
                sequence = null,
                createdAt = 500,
                expiresAt = 2_000,
                payloadJson = JSONObject()
                    .put("probe_id", 99.canonicalUuid())
                    .put("sent_at", 500)
                    .put("request_direct", false)
                    .toString(),
            ),
            true,
        )

        assertNull(outbox.ensureProbe(generation = 4, requestDirect = false))
        now = 2_001
        assertNotNull(outbox.ensureProbe(generation = 4, requestDirect = false))
    }

    @Test
    fun probeEvidenceRequiresCurrentGenerationDigestAndUnexpiredReceipt() = runTest {
        val probe = assertNotNull(outbox.ensureProbe(generation = 8, requestDirect = true))

        assertFalse(outbox.acceptProbeReceipt(probe.msgId, "f".repeat(64), generation = 8, now = 2_000))
        assertFalse(outbox.acceptProbeReceipt(probe.msgId, probe.envelopeSha256, generation = 9, now = 2_000))
        assertEquals(PeerEvidence.UNKNOWN, outbox.peerEvidence(generation = 8, now = 2_000))
        assertTrue(outbox.acceptProbeReceipt(probe.msgId, probe.envelopeSha256, generation = 8, now = 2_000))
        store.inserted.clear() // The ordinary receipt transition deletes this durable row.
        assertEquals(PeerEvidence.RECENT, outbox.peerEvidence(generation = 8, now = 152_000))
        assertEquals(PeerEvidence.STALE, outbox.peerEvidence(generation = 8, now = 152_001))
        assertEquals(PeerEvidence.UNKNOWN, outbox.peerEvidence(generation = 9, now = 2_001))

        now = 61_999
        assertNull(outbox.ensureProbe(generation = 8, requestDirect = false))
        now = 62_000
        assertNotNull(outbox.ensureProbe(generation = 8, requestDirect = false))
    }

    @Test
    fun failedDurableInsertDoesNotRegisterProbe() = runTest {
        store.failNextInsert = true
        kotlin.test.assertFails { outbox.ensureProbe(generation = 1, requestDirect = false) }

        assertNotNull(outbox.ensureProbe(generation = 1, requestDirect = false))
        assertEquals(1, store.inserted.size)
    }

    @Test
    fun durableSealerEncryptsForStoredPeerAndBuildsLowercaseDigest() = runTest {
        val peer = ByteArray(32) { 0x21 }
        val own = ByteArray(32) { 0x31 }
        val nonce = ByteArray(24) { 0x41 }
        var capturedPlain = ByteArray(0)
        var capturedNonce = ByteArray(0)
        var capturedPeer = ByteArray(0)
        var capturedOwn = ByteArray(0)
        val durable = DurablePeerControlSealer(
            inputs = PeerControlSealInputsProvider { PeerControlSealInputs(peer, own, nonce) },
            encryptor = PeerControlEncryptor { plain, actualNonce, actualPeer, actualOwn ->
                capturedPlain = plain.copyOf()
                capturedNonce = actualNonce.copyOf()
                capturedPeer = actualPeer.copyOf()
                capturedOwn = actualOwn.copyOf()
                byteArrayOf(1, 2, 3)
            },
        )
        val event = InnerEventV2(
            msgId = 1.canonicalUuid(),
            originDevice = "local-device",
            type = "peer.probe",
            canonId = null,
            sequence = null,
            createdAt = 1_000,
            expiresAt = 121_000,
            payloadJson = JSONObject()
                .put("probe_id", 1.canonicalUuid())
                .put("sent_at", 1_000)
                .put("request_direct", true)
                .toString(),
        )

        val row = durable.seal(event, requiresPeerReceipt = true)
        val envelope = ProtocolJson.decodeEnvelope(row.envelopeJson)

        assertEquals(ProtocolJson.encodeInner(event), capturedPlain.toString(Charsets.UTF_8))
        assertContentEquals(nonce, capturedNonce)
        assertContentEquals(peer, capturedPeer)
        assertContentEquals(own, capturedOwn)
        assertEquals(Base64.getEncoder().encodeToString(nonce), envelope.nonceB64)
        assertEquals(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)), envelope.ciphertextB64)
        assertTrue(row.envelopeSha256.matches(Regex("^[0-9a-f]{64}$")))
        assertTrue(row.requiresPeerReceipt)
    }

    private fun row(event: InnerEventV2, requiresPeerReceipt: Boolean) = OutboundMessage(
        msgId = event.msgId,
        canonId = null,
        sequence = null,
        eventType = event.type,
        protocolVersion = 2,
        envelopeJson = "{}",
        envelopeSha256 = event.msgId.replace("-", "").repeat(2),
        byteSize = 2,
        createdAt = event.createdAt,
        expiresAt = event.expiresAt,
        custodyAcceptedAt = null,
        custodyRoute = null,
        attempts = 0,
        nextAttemptAt = event.createdAt,
        state = "NEW",
        lastError = null,
        requiresPeerReceipt = requiresPeerReceipt,
    )

    private fun Int.canonicalUuid() = "%08d-0000-4000-8000-000000000000".format(this)

    private class FakePeerControlStore : PeerControlStore {
        val inserted = mutableListOf<OutboundMessage>()
        var recovered: OutboundMessage? = null
        var failNextInsert = false

        override suspend fun insert(row: OutboundMessage) {
            if (failNextInsert) {
                failNextInsert = false
                error("insert failed")
            }
            inserted += row
        }

        override suspend fun active(eventType: String, now: Long): OutboundMessage? =
            inserted.lastOrNull { it.eventType == eventType && it.expiresAt > now } ?:
                recovered?.takeIf { it.eventType == eventType && it.expiresAt > now }
    }
}
