package co.twinotify.core.pairing

import co.twinotify.core.service.RelayUrlPolicy
import co.twinotify.core.storage.PeerRecord
import kotlinx.coroutines.CancellationException

/** This phone's long-term identity. Attach reuses it and never regenerates a key. */
data class RelayAttachIdentity(
    val deviceId: String,
    val encPubkey: ByteArray,
    val signPubkey: ByteArray,
    val signSecretKey: ByteArray,
    val displayName: String?,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/** The peer identity a relay handshake reported back, before it has been trusted. */
data class RelayAttachPeerHello(
    val deviceId: String,
    val encPubkey: ByteArray,
    val signPubkey: ByteArray,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

sealed interface RelayAttachResult {
    data object Attached : RelayAttachResult

    /** Bounded reason, safe to log and to map to copy. Never carries a URL or a key. */
    data class Rejected(val code: String) : RelayAttachResult
}

/** The relay calls an attach needs, isolated so the coordinator is testable without a network. */
interface RelayAttachRelayClient {
    suspend fun initiate(relayUrl: String, pairToken: String, identity: RelayAttachIdentity)

    /** Takes the identity because the relay's notify channel is itself authenticated. */
    suspend fun awaitPeerHello(
        relayUrl: String,
        pairToken: String,
        identity: RelayAttachIdentity,
    ): RelayAttachPeerHello

    suspend fun sendConfirmationSig(relayUrl: String, pairToken: String, sig: ByteArray)
}

/**
 * Adds a relay to a pair that already trusts itself, from the initiating phone.
 *
 * This is the mirror of `lan.bootstrap`: that event adds a direct path to a relay pair, and
 * `relay.attach` adds a relay path to a direct pair. The relay handshake is the ordinary
 * four-step pairing flow (init, hello, send_sig, complete) with the identity keys both phones
 * already hold, so the relay learns nothing it would not have learned at first-time pairing and
 * needs no new endpoint.
 *
 * Where first-time pairing asks a human to compare fingerprints, an attach requires the identity
 * returned by the handshake to be byte-identical to the peer already stored. That is a stronger
 * check than human comparison, and it is why an attach leaves the verified fingerprint unchanged.
 *
 * Every failure path is inert: nothing is persisted until the peer has been proven to be the
 * peer, so an aborted attach leaves the existing direct route exactly as it was.
 */
class RelayAttachCoordinator(
    private val loadIdentity: suspend () -> RelayAttachIdentity,
    private val loadPeer: suspend () -> PeerRecord?,
    private val relayClient: RelayAttachRelayClient,
    /** Hands the peer the relay URL and token over whichever direct route is granted. */
    private val announce: suspend (relayUrl: String, pairToken: String) -> Unit,
    private val signConfirmation: (
        pairToken: String,
        aEncPub: ByteArray,
        aSignPub: ByteArray,
        bEncPub: ByteArray,
        bSignPub: ByteArray,
        aSignSecret: ByteArray,
    ) -> ByteArray = PairProtocol::deviceASignConfirmation,
    /** Persists the relay and restarts the transport generation. Runs only on success. */
    private val commit: suspend (relayUrl: String) -> Unit,
    private val newToken: () -> String = { PairPayload.newToken() },
) {
    suspend fun attach(relayUrl: String): RelayAttachResult {
        // Parsed with debug = false on purpose: RelayUrlPolicy's loopback exception is a local
        // development affordance, and a relay a peer will also have to reach is never loopback.
        val canonical = try {
            RelayUrlPolicy.parse(relayUrl, debug = false)
            relayUrl.trim()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            return RelayAttachResult.Rejected(RELAY_URL_INVALID)
        }

        val peer = try {
            loadPeer()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            return RelayAttachResult.Rejected(STORE_FAILED)
        } ?: return RelayAttachResult.Rejected(NOT_PAIRED)

        val identity = try {
            loadIdentity()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            return RelayAttachResult.Rejected(STORE_FAILED)
        }

        val pairToken = newToken()

        try {
            relayClient.initiate(canonical, pairToken, identity)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            return RelayAttachResult.Rejected(RELAY_UNREACHABLE)
        }

        // The peer cannot answer a handshake it has not been told about, so the announcement has
        // to reach it before we start waiting.
        try {
            announce(canonical, pairToken)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            return RelayAttachResult.Rejected(NO_DIRECT_ROUTE)
        }

        val hello = try {
            relayClient.awaitPeerHello(canonical, pairToken, identity)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            return RelayAttachResult.Rejected(PEER_TIMEOUT)
        }

        if (!isAlreadyTrusted(hello, peer)) return RelayAttachResult.Rejected(PEER_IDENTITY_MISMATCH)

        val signature = signConfirmation(
            pairToken,
            identity.encPubkey,
            identity.signPubkey,
            hello.encPubkey,
            hello.signPubkey,
            identity.signSecretKey,
        )

        try {
            relayClient.sendConfirmationSig(canonical, pairToken, signature)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            return RelayAttachResult.Rejected(RELAY_UNREACHABLE)
        }

        return try {
            commit(canonical)
            RelayAttachResult.Attached
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            RelayAttachResult.Rejected(STORE_FAILED)
        }
    }

    /**
     * The whole security argument for skipping the fingerprint screen. An attach may only ever
     * confirm the peer this phone already verified, byte for byte.
     */
    private fun isAlreadyTrusted(hello: RelayAttachPeerHello, peer: PeerRecord): Boolean =
        hello.deviceId == peer.deviceId &&
            hello.encPubkey.contentEquals(peer.encPubkey) &&
            hello.signPubkey.contentEquals(peer.signPubkey)

    companion object {
        const val RELAY_URL_INVALID = "relay_url_invalid"
        const val NOT_PAIRED = "not_paired"
        const val NO_DIRECT_ROUTE = "no_direct_route"
        const val RELAY_UNREACHABLE = "relay_unreachable"
        const val PEER_TIMEOUT = "peer_timeout"
        const val PEER_IDENTITY_MISMATCH = "peer_identity_mismatch"
        const val STORE_FAILED = "store_failed"
    }
}
