package co.twinotify.core.pairing

import co.twinotify.core.storage.PeerRecord
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The responder half of an attach.
 *
 * Its security rests on one rule: the responder completes the relay pairing with the initiator
 * identity it *already stores*, never one the relay hands it. A relay that tried to pair this
 * phone with somebody else therefore cannot succeed, because the confirmation signature is checked
 * against the stored peer key and the completion is sent with the stored peer keys.
 */
class RelayAttachResponderTest {
    private val peerEnc = ByteArray(32) { 0x11 }
    private val peerSign = ByteArray(32) { 0x22 }
    private val localEnc = ByteArray(32) { 0x33 }
    private val localSign = ByteArray(32) { 0x44 }
    private val localSecret = ByteArray(64) { 0x55 }
    private val goodSig = ByteArray(64) { 0x66 }

    private val peer = PeerRecord(
        deviceId = "peer-device",
        encPubkey = peerEnc,
        signPubkey = peerSign,
        displayName = "Peer",
        lanBindingId = "lan-binding-1",
    )

    private val identity = RelayAttachIdentity(
        deviceId = "local-device",
        encPubkey = localEnc,
        signPubkey = localSign,
        signSecretKey = localSecret,
        displayName = "Local",
    )

    private class RecordingClient(
        var sig: ByteArray,
        var failOn: String? = null,
    ) : RelayAttachResponderClient {
        val calls = mutableListOf<String>()
        var completedWithPeerEnc: ByteArray? = null
        var completedWithPeerSign: ByteArray? = null

        override suspend fun sendPeerHello(
            relayUrl: String,
            pairToken: String,
            identity: RelayAttachIdentity,
        ) {
            calls += "hello"
            if (failOn == "hello") error("relay refused hello")
        }

        override suspend fun awaitConfirmationSig(
            relayUrl: String,
            pairToken: String,
            identity: RelayAttachIdentity,
        ): ByteArray {
            calls += "await"
            if (failOn == "await") error("initiator never signed")
            return sig
        }

        override suspend fun complete(
            relayUrl: String,
            pairToken: String,
            identity: RelayAttachIdentity,
            initiatorEncPubkey: ByteArray,
            initiatorSignPubkey: ByteArray,
            confirmationSig: ByteArray,
        ) {
            calls += "complete"
            completedWithPeerEnc = initiatorEncPubkey
            completedWithPeerSign = initiatorSignPubkey
            if (failOn == "complete") error("relay refused complete")
        }
    }

    private fun processor(
        client: RelayAttachResponderClient,
        peerRecord: PeerRecord? = peer,
        committed: MutableList<String> = mutableListOf(),
        verified: MutableList<ByteArray> = mutableListOf(),
        signatureValid: Boolean = true,
    ) = DefaultRelayAttachProcessor(
        loadIdentity = { identity },
        loadPeer = { peerRecord },
        client = client,
        verifyConfirmation = { _, _, signingKey -> verified += signingKey; signatureValid },
        commit = { url -> committed += url },
    )

    private fun offer(relayUrl: String = "https://relay.example.test") =
        RelayAttachOffer(relayUrl = relayUrl, pairToken = "0123456789abcdef0123")

    @Test
    fun applyRunsHelloAwaitVerifyCompleteThenCommits() = runTest {
        val client = RecordingClient(sig = goodSig)
        val committed = mutableListOf<String>()

        val result = processor(client, committed = committed).process(offer())

        assertIs<RelayAttachApplyResult.Applied>(result)
        assertEquals(listOf("hello", "await", "complete"), client.calls)
        assertEquals(listOf("https://relay.example.test"), committed)
    }

    @Test
    fun completionUsesTheStoredInitiatorKeysNotAnythingTheRelaySupplied() = runTest {
        val client = RecordingClient(sig = goodSig)

        processor(client).process(offer())

        assertContentEquals(peerEnc, client.completedWithPeerEnc)
        assertContentEquals(peerSign, client.completedWithPeerSign)
    }

    @Test
    fun confirmationSignatureIsCheckedAgainstTheStoredPeerSigningKey() = runTest {
        val client = RecordingClient(sig = goodSig)
        val verified = mutableListOf<ByteArray>()

        processor(client, verified = verified).process(offer())

        assertContentEquals(peerSign, verified.single())
    }

    @Test
    fun aBadConfirmationSignatureAbortsBeforeCompletingOrCommitting() = runTest {
        val client = RecordingClient(sig = ByteArray(64) { 0x77 })
        val committed = mutableListOf<String>()

        val result = processor(client, committed = committed, signatureValid = false).process(offer())

        assertEquals(RelayAttachApplyResult.Rejected("peer_identity_mismatch"), result)
        assertEquals(listOf("hello", "await"), client.calls)
        assertTrue(committed.isEmpty())
    }

    @Test
    fun cleartextOrMalformedRelayIsRefusedBeforeAnyNetworkCall() = runTest {
        listOf("http://relay.example.test", "ws://relay.example.test", "nonsense", "").forEach { url ->
            val client = RecordingClient(sig = goodSig)
            val committed = mutableListOf<String>()

            val result = processor(client, committed = committed).process(offer(relayUrl = url))

            assertEquals(RelayAttachApplyResult.Rejected("relay_url_invalid"), result, url)
            assertTrue(client.calls.isEmpty(), url)
            assertTrue(committed.isEmpty(), url)
        }
    }

    @Test
    fun anUnpairedPhoneRefusesWithoutContactingTheRelay() = runTest {
        val client = RecordingClient(sig = goodSig)

        val result = processor(client, peerRecord = null).process(offer())

        assertEquals(RelayAttachApplyResult.Rejected("not_paired"), result)
        assertTrue(client.calls.isEmpty())
    }

    @Test
    fun aRelayFailureAtAnyStageCommitsNothing() = runTest {
        mapOf(
            "hello" to "relay_unreachable",
            "await" to "peer_timeout",
            "complete" to "relay_unreachable",
        ).forEach { (stage, expected) ->
            val client = RecordingClient(sig = goodSig, failOn = stage)
            val committed = mutableListOf<String>()

            val result = processor(client, committed = committed).process(offer())

            assertEquals(RelayAttachApplyResult.Rejected(expected), result, stage)
            assertTrue(committed.isEmpty(), stage)
        }
    }
}
