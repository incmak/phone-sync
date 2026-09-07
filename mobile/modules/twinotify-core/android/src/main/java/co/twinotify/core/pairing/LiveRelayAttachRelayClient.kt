package co.twinotify.core.pairing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Base64

/**
 * The real relay half of an attach, built entirely from the existing pairing calls.
 *
 * Every request is the same one first-time pairing already makes, with the same identity keys, so
 * the relay cannot tell an attach from an ordinary pairing and needs no new endpoint. The blocking
 * OkHttp calls in [PairProtocol] are moved onto the IO dispatcher here rather than in the
 * coordinator, which stays free of any threading concern.
 */
class LiveRelayAttachRelayClient(
    private val debug: Boolean,
    private val awaitTimeoutMs: Long = DEFAULT_AWAIT_TIMEOUT_MS,
) : RelayAttachRelayClient {

    override suspend fun initiate(
        relayUrl: String,
        pairToken: String,
        identity: RelayAttachIdentity,
    ) = withContext(Dispatchers.IO) {
        PairProtocol.initiate(
            relayUrl,
            pairToken,
            identity.deviceId,
            identity.encPubkey,
            identity.signPubkey,
            identity.displayName,
            debug = debug,
        )
    }

    override suspend fun awaitPeerHello(
        relayUrl: String,
        pairToken: String,
        identity: RelayAttachIdentity,
    ): RelayAttachPeerHello {
        val frame = PairNotifyClient.awaitAuthenticatedFrame(
            relayUrl,
            pairToken,
            role = "A",
            expectedType = "peer.hello",
            deviceId = identity.deviceId,
            signSecretKey = identity.signSecretKey,
            timeoutMs = awaitTimeoutMs,
            debug = debug,
        )
        return parsePeerHello(frame)
    }

    override suspend fun sendConfirmationSig(
        relayUrl: String,
        pairToken: String,
        sig: ByteArray,
    ) = withContext(Dispatchers.IO) {
        PairProtocol.sendConfirmationSig(relayUrl, pairToken, sig, debug = debug)
    }

    override suspend fun awaitComplete(relayUrl: String, pairToken: String, identity: RelayAttachIdentity): String {
        val frame = PairNotifyClient.awaitAuthenticatedFrame(relayUrl, pairToken, "A", "pair.complete",
            identity.deviceId, identity.signSecretKey, awaitTimeoutMs, debug)
        return PairProtocol.requirePairId(JSONObject(frame).getString("pair_id"))
    }

    companion object {
        /**
         * Shorter than the pairing screen's five minutes. An attach is a foreground action with
         * the peer already connected on a direct route, so a long wait is a failure, not patience.
         */
        const val DEFAULT_AWAIT_TIMEOUT_MS = 90_000L

        internal fun parsePeerHello(frame: String): RelayAttachPeerHello {
            val json = JSONObject(frame)
            return RelayAttachPeerHello(
                deviceId = json.getString("device_id"),
                encPubkey = Base64.getDecoder().decode(json.getString("enc_pubkey")),
                signPubkey = Base64.getDecoder().decode(json.getString("sign_pubkey")),
            )
        }
    }
}
