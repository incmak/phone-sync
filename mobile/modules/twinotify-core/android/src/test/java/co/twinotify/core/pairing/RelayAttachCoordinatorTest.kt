package co.twinotify.core.pairing

import co.twinotify.core.storage.PeerRecord
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The initiator half of adding a relay to a pair that already trusts itself.
 *
 * The property under test throughout is that an attach either completes fully or changes
 * nothing: the LAN binding, the Bluetooth association and the peer identity that make the
 * existing direct route work must survive every failure path.
 */
class RelayAttachCoordinatorTest {
    private val peerEnc = ByteArray(32) { 0x11 }
    private val peerSign = ByteArray(32) { 0x22 }
    private val localEnc = ByteArray(32) { 0x33 }
    private val localSign = ByteArray(32) { 0x44 }
    private val localSecret = ByteArray(64) { 0x55 }

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

    private class RecordingRelay(
        var hello: RelayAttachPeerHello? = null,
        var failOn: String? = null,
    ) : RelayAttachRelayClient {
        val calls = mutableListOf<String>()
        var sentSig: ByteArray? = null

        override suspend fun initiate(relayUrl: String, pairToken: String, identity: RelayAttachIdentity) {
            calls += "initiate"
            if (failOn == "initiate") error("relay refused init")
        }

        override suspend fun awaitPeerHello(
            relayUrl: String,
            pairToken: String,
            identity: RelayAttachIdentity,
        ): RelayAttachPeerHello {
            calls += "await"
            if (failOn == "await") error("peer never answered")
            return checkNotNull(hello)
        }

        override suspend fun sendConfirmationSig(relayUrl: String, pairToken: String, sig: ByteArray) {
            calls += "sig"
            sentSig = sig
            if (failOn == "sig") error("relay refused sig")
        }
    }

    private fun coordinator(
        relay: RelayAttachRelayClient,
        peerRecord: PeerRecord? = peer,
        announced: MutableList<Pair<String, String>> = mutableListOf(),
        committed: MutableList<String> = mutableListOf(),
    ) = RelayAttachCoordinator(
        loadIdentity = { identity },
        loadPeer = { peerRecord },
        relayClient = relay,
        announce = { url, token -> announced += url to token },
        signConfirmation = { _, _, _, _, _, _ -> ByteArray(64) { 0x66 } },
        commit = { url -> committed += url },
        newToken = { "0123456789abcdef0123" },
    )

    private fun matchingHello() =
        RelayAttachPeerHello(deviceId = "peer-device", encPubkey = peerEnc, signPubkey = peerSign)

    @Test
    fun attach_registersAnnouncesVerifiesAndCommitsInOrder() = runTest {
        val relay = RecordingRelay(hello = matchingHello())
        val announced = mutableListOf<Pair<String, String>>()
        val committed = mutableListOf<String>()

        val result = coordinator(relay, announced = announced, committed = committed)
            .attach("https://relay.example.test")

        assertIs<RelayAttachResult.Attached>(result)
        // The peer cannot answer a handshake it has not been told about, so the announcement
        // has to sit between init and the wait.
        assertEquals(listOf("initiate", "await", "sig"), relay.calls)
        assertEquals(listOf("https://relay.example.test" to "0123456789abcdef0123"), announced)
        assertEquals(listOf("https://relay.example.test"), committed)
    }

    @Test
    fun attach_refusesCleartextAndMalformedRelayUrlsBeforeTouchingAnything() = runTest {
        listOf("http://relay.example.test", "ws://relay.example.test", "not a url", "").forEach { url ->
            val relay = RecordingRelay(hello = matchingHello())
            val announced = mutableListOf<Pair<String, String>>()
            val committed = mutableListOf<String>()

            val result = coordinator(relay, announced = announced, committed = committed).attach(url)

            assertEquals(RelayAttachResult.Rejected("relay_url_invalid"), result, url)
            assertTrue(relay.calls.isEmpty(), url)
            assertTrue(announced.isEmpty(), url)
            assertTrue(committed.isEmpty(), url)
        }
    }

    @Test
    fun attach_abortsWhenThePeerIdentityDoesNotMatchTheTrustedRecord() = runTest {
        val impostors = listOf(
            matchingHello().copy(deviceId = "someone-else"),
            matchingHello().copy(encPubkey = ByteArray(32) { 0x77 }),
            matchingHello().copy(signPubkey = ByteArray(32) { 0x77 }),
        )

        impostors.forEach { hello ->
            val relay = RecordingRelay(hello = hello)
            val committed = mutableListOf<String>()

            val result = coordinator(relay, committed = committed).attach("https://relay.example.test")

            assertEquals(RelayAttachResult.Rejected("peer_identity_mismatch"), result)
            // Never sign a confirmation for an identity we do not already trust.
            assertEquals(listOf("initiate", "await"), relay.calls)
            assertTrue(committed.isEmpty())
        }
    }

    @Test
    fun attach_reportsUnpairedWithoutContactingTheRelay() = runTest {
        val relay = RecordingRelay(hello = matchingHello())

        val result = coordinator(relay, peerRecord = null).attach("https://relay.example.test")

        assertEquals(RelayAttachResult.Rejected("not_paired"), result)
        assertTrue(relay.calls.isEmpty())
    }

    @Test
    fun attach_leavesNothingCommittedWhenTheRelayFailsAtAnyStage() = runTest {
        mapOf(
            "initiate" to "relay_unreachable",
            "await" to "peer_timeout",
            "sig" to "relay_unreachable",
        ).forEach { (stage, expected) ->
            val relay = RecordingRelay(hello = matchingHello(), failOn = stage)
            val committed = mutableListOf<String>()

            val result = coordinator(relay, committed = committed).attach("https://relay.example.test")

            assertEquals(RelayAttachResult.Rejected(expected), result, stage)
            assertTrue(committed.isEmpty(), stage)
        }
    }
}
