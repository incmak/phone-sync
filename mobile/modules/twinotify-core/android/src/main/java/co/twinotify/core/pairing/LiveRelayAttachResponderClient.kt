package co.twinotify.core.pairing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Base64

/**
 * The real relay half of the responder, built from the same pairing calls device B already makes
 * after scanning a QR code. The difference is upstream, not here: the initiator identity passed to
 * [complete] comes from the stored peer record rather than from a scanned payload.
 */
class LiveRelayAttachResponderClient(
    private val debug: Boolean,
    private val awaitTimeoutMs: Long = DEFAULT_AWAIT_TIMEOUT_MS,
) : RelayAttachResponderClient {

    override suspend fun sendPeerHello(
        relayUrl: String,
        pairToken: String,
        identity: RelayAttachIdentity,
    ) = withContext(Dispatchers.IO) {
        PairProtocol.sendPeerHello(
            relayUrl,
            pairToken,
            identity.deviceId,
            identity.encPubkey,
            identity.signPubkey,
            identity.displayName,
            debug = debug,
        )
    }

    override suspend fun awaitConfirmationSig(
        relayUrl: String,
        pairToken: String,
        identity: RelayAttachIdentity,
    ): ByteArray {
        val frame = PairNotifyClient.awaitAuthenticatedFrame(
            relayUrl,
            pairToken,
            role = "B",
            expectedType = "pair.sig",
            deviceId = identity.deviceId,
            signSecretKey = identity.signSecretKey,
            timeoutMs = awaitTimeoutMs,
            debug = debug,
        )
        return decodeConfirmationSig(frame)
    }

    override suspend fun complete(
        relayUrl: String,
        pairToken: String,
        identity: RelayAttachIdentity,
        initiatorEncPubkey: ByteArray,
        initiatorSignPubkey: ByteArray,
        confirmationSig: ByteArray,
    ) = withContext(Dispatchers.IO) {
        PairProtocol.deviceBCompletePair(
            relayUrl,
            pairToken,
            identity.deviceId,
            initiatorEncPubkey,
            initiatorSignPubkey,
            identity.encPubkey,
            identity.signPubkey,
            identity.signSecretKey,
            confirmationSig,
            debug = debug,
        )
    }

    companion object {
        /** Matches the initiator's wait; both phones are attached to the same short exchange. */
        const val DEFAULT_AWAIT_TIMEOUT_MS = 90_000L

        internal fun decodeConfirmationSig(frame: String): ByteArray =
            Base64.getDecoder().decode(JSONObject(frame).getString("confirmation_sig"))
    }
}
