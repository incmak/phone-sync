package co.twinotify.core.pairing

import co.twinotify.core.service.RelayUrlPolicy
import co.twinotify.core.storage.PeerRecord
import kotlinx.coroutines.CancellationException

/** What the initiator offered, already parsed out of the authenticated `relay.attach` event. */
data class RelayAttachOffer(val relayUrl: String, val pairToken: String)

sealed interface RelayAttachApplyResult {
    data object Applied : RelayAttachApplyResult
    data class Rejected(val code: String) : RelayAttachApplyResult
}

fun interface RelayAttachProcessor {
    suspend fun process(offer: RelayAttachOffer): RelayAttachApplyResult
}

/** The responder's relay calls, isolated so the processor is testable without a network. */
interface RelayAttachResponderClient {
    suspend fun sendPeerHello(relayUrl: String, pairToken: String, identity: RelayAttachIdentity)

    suspend fun awaitConfirmationSig(
        relayUrl: String,
        pairToken: String,
        identity: RelayAttachIdentity,
    ): ByteArray

    suspend fun complete(
        relayUrl: String,
        pairToken: String,
        identity: RelayAttachIdentity,
        initiatorEncPubkey: ByteArray,
        initiatorSignPubkey: ByteArray,
        confirmationSig: ByteArray,
    ): String
}

/**
 * Joins the relay the trusted peer offered, on the phone that received `relay.attach`.
 *
 * The event arrives inside the ciphertext and is authenticated, so only the paired phone can send
 * one. That is the first defence. The second is that this processor never takes the initiator's
 * identity from the relay: it verifies the confirmation signature against the **stored** peer
 * signing key and completes the pairing with the **stored** peer public keys. A relay that tried
 * to pair this phone with somebody else therefore fails at the signature check, and if it somehow
 * passed, the completion it receives names an identity it never registered.
 *
 * Nothing is persisted until the relay has accepted the completed pair, so a failure anywhere
 * leaves the existing direct route untouched.
 */
class DefaultRelayAttachProcessor(
    private val loadIdentity: suspend () -> RelayAttachIdentity,
    private val loadPeer: suspend () -> PeerRecord?,
    private val client: RelayAttachResponderClient,
    /** Ed25519 detached verify over the confirmation transcript. */
    private val verifyConfirmation: (
        transcript: ByteArray,
        signature: ByteArray,
        initiatorSignPubkey: ByteArray,
    ) -> Boolean,
    /** Persists the relay and restarts the transport generation. Runs only on success. */
    private val commit: suspend (relayUrl: String, pairId: String) -> Unit,
) : RelayAttachProcessor {

    override suspend fun process(offer: RelayAttachOffer): RelayAttachApplyResult {
        // debug = false on purpose, as on the initiator: a relay both phones must reach is never
        // loopback, so the policy's local development exception must not apply to a peer's offer.
        val canonical = try {
            RelayUrlPolicy.parse(offer.relayUrl, debug = false)
            offer.relayUrl.trim()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            return RelayAttachApplyResult.Rejected(RelayAttachCodes.RELAY_URL_INVALID)
        }

        val peer = try {
            loadPeer()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            return RelayAttachApplyResult.Rejected(RelayAttachCodes.STORE_FAILED)
        } ?: return RelayAttachApplyResult.Rejected(RelayAttachCodes.NOT_PAIRED)

        val identity = try {
            loadIdentity()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            return RelayAttachApplyResult.Rejected(RelayAttachCodes.STORE_FAILED)
        }

        try {
            client.sendPeerHello(canonical, offer.pairToken, identity)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            return RelayAttachApplyResult.Rejected(RelayAttachCodes.RELAY_UNREACHABLE)
        }

        val confirmationSig = try {
            client.awaitConfirmationSig(canonical, offer.pairToken, identity)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            return RelayAttachApplyResult.Rejected(RelayAttachCodes.PEER_TIMEOUT)
        }

        // Spec §4.7: sig_A(pair_token || A_enc || A_sign || B_enc || B_sign), A first then B. The
        // A halves come from the stored peer record, so a signature only verifies if the phone on
        // the far side of the relay is the phone this one already trusts.
        val transcript = offer.pairToken.toByteArray() +
            peer.encPubkey + peer.signPubkey +
            identity.encPubkey + identity.signPubkey
        val trusted = try {
            verifyConfirmation(transcript, confirmationSig, peer.signPubkey)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            false
        }
        if (!trusted) return RelayAttachApplyResult.Rejected(RelayAttachCodes.PEER_IDENTITY_MISMATCH)

        val pairId = try {
            client.complete(
                canonical,
                offer.pairToken,
                identity,
                peer.encPubkey,
                peer.signPubkey,
                confirmationSig,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            return RelayAttachApplyResult.Rejected(RelayAttachCodes.RELAY_UNREACHABLE)
        }

        return try {
            commit(canonical, pairId)
            RelayAttachApplyResult.Applied
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            RelayAttachApplyResult.Rejected(RelayAttachCodes.STORE_FAILED)
        }
    }
}
