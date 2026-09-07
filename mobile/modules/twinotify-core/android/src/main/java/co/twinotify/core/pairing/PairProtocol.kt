package co.twinotify.core.pairing

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Sign
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit
import co.twinotify.core.service.RelayUrlPolicy

/**
 * HTTP client for /pair/init, /pair/hello, /pair/send_sig, and /pair/complete.
 *
 * Role split:
 *   - Device A calls `initiate()` to register with the relay and display QR.
 *   - Device A produces the confirmation_sig via `deviceASignConfirmation()`
 *     after seeing B's fingerprint and user approval.
 *   - Device A calls `sendConfirmationSig()` to push the sig to B via relay.
 *   - Device B calls `sendPeerHello()` after scanning QR to send its own pubkeys.
 *   - Device B signs the domain-separated transcript plus A's signature and calls
 *     `deviceBCompletePair()` after receiving A's signature from the relay.
 *
 * Spec §4.7: sig_A(pair_token || A_enc || A_sign || B_enc || B_sign) — 5 fields, A first then B.
 */
object PairProtocol {
    private val ls = LazySodiumAndroid(SodiumAndroid())
    private val sodium = ls.sodium
    private val JSON = "application/json".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** Authenticated pair revocation. 401 is terminal only for a caller that persisted intent. */
    fun revoke(
        relayUrl: String,
        bearerJwt: String,
        debug: Boolean = false,
        revocationMarkerPresent: Boolean = false,
    ): RevokeOutcome {
        val endpoints = RelayUrlPolicy.parse(relayUrl, debug = debug)
        val request = Request.Builder()
            .url(endpoints.http.newBuilder().addPathSegment("pair").addPathSegment("revoke").build())
            .header("Authorization", "Bearer $bearerJwt")
            .post("{}".toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { response ->
            return RevocationPolicy.classify(response.code, revocationMarkerPresent)
        }
    }

    /** Called on Device A. Registers pending pair with relay. */
    fun initiate(
        relayUrl: String, token: String, deviceId: String,
        encPub: ByteArray, signPub: ByteArray,
        displayName: String? = null,
        debug: Boolean = false,
    ) {
        val map = mutableMapOf<String, Any>(
            "pair_token" to token,
            "device_id" to deviceId,
            "enc_pubkey" to Base64.getEncoder().encodeToString(encPub),
            "sign_pubkey" to Base64.getEncoder().encodeToString(signPub),
        )
        if (!displayName.isNullOrBlank()) map["display_name"] = displayName
        val body = JSONObject(map.toMap()).toString().toRequestBody(JSON)
        val resp = http.newCall(Request.Builder().url(PairingRelayEndpoint.http(relayUrl, "pair", "init", debug = debug)).post(body).build()).execute()
        check(resp.isSuccessful) { "init HTTP ${resp.code}" }
        resp.close()
    }

    /** Device B → relay: announce own pubkeys + optional display name. Relay forwards to A. */
    fun sendPeerHello(
        relayUrl: String, token: String, deviceId: String,
        bEncPub: ByteArray, bSignPub: ByteArray,
        displayName: String? = null,
        debug: Boolean = false,
    ) {
        val map = mutableMapOf<String, Any>(
            "pair_token" to token,
            "device_id" to deviceId,
            "enc_pubkey" to Base64.getEncoder().encodeToString(bEncPub),
            "sign_pubkey" to Base64.getEncoder().encodeToString(bSignPub),
        )
        if (!displayName.isNullOrBlank()) map["display_name"] = displayName
        val body = JSONObject(map.toMap()).toString().toRequestBody(JSON)
        val resp = http.newCall(Request.Builder().url(PairingRelayEndpoint.http(relayUrl, "pair", "hello", debug = debug)).post(body).build()).execute()
        check(resp.isSuccessful) { "pair/hello HTTP ${resp.code}" }
        resp.close()
    }

    /** Device A → relay: push confirmation_sig. Relay forwards to B. */
    fun sendConfirmationSig(relayUrl: String, token: String, sig: ByteArray, debug: Boolean = false) {
        val body = JSONObject(mapOf(
            "pair_token" to token,
            "confirmation_sig" to Base64.getEncoder().encodeToString(sig),
        )).toString().toRequestBody(JSON)
        val resp = http.newCall(Request.Builder().url(PairingRelayEndpoint.http(relayUrl, "pair", "send_sig", debug = debug)).post(body).build()).execute()
        check(resp.isSuccessful) { "pair/send_sig HTTP ${resp.code}" }
        resp.close()
    }

    /** Device A signs the canonical confirmation message. */
    fun deviceASignConfirmation(
        token: String,
        aEncPub: ByteArray, aSignPub: ByteArray,
        bEncPub: ByteArray, bSignPub: ByteArray,
        aSignSecret: ByteArray,
    ): ByteArray {
        require(aSignSecret.size == Sign.SECRETKEYBYTES) {
            "Ed25519 secret key must be ${Sign.SECRETKEYBYTES} bytes (libsodium format)"
        }
        val msg = token.toByteArray() + aEncPub + aSignPub + bEncPub + bSignPub
        return signDetached(msg, aSignSecret)
    }

    /** Called on Device B once it has A's confirmation sig. */
    fun deviceBCompletePair(
        relayUrl: String, token: String, deviceId: String,
        aEncPub: ByteArray, aSignPub: ByteArray,
        bEncPub: ByteArray, bSignPub: ByteArray,
        bSignSecret: ByteArray,
        confirmationSig: ByteArray,
        debug: Boolean = false,
    ): String {
        val confirmationMessage = token.toByteArray() + aEncPub + aSignPub + bEncPub + bSignPub
        require(confirmationSig.size == Sign.BYTES && aSignPub.size == Sign.PUBLICKEYBYTES &&
            sodium.crypto_sign_verify_detached(confirmationSig, confirmationMessage,
                confirmationMessage.size.toLong(), aSignPub) == 0) { "invalid_initiator_confirmation" }
        val responderSig = signDetached(
            PairConfirmation.responderMessage(
                token,
                aEncPub,
                aSignPub,
                bEncPub,
                bSignPub,
                confirmationSig,
            ),
            bSignSecret,
        )
        val body = JSONObject(mapOf(
            "pair_token" to token,
            "device_id" to deviceId,
            "enc_pubkey" to Base64.getEncoder().encodeToString(bEncPub),
            "sign_pubkey" to Base64.getEncoder().encodeToString(bSignPub),
            "confirmation_sig" to Base64.getEncoder().encodeToString(confirmationSig),
            "responder_confirmation_sig" to Base64.getEncoder().encodeToString(responderSig),
        )).toString().toRequestBody(JSON)
        val resp = http.newCall(Request.Builder().url(PairingRelayEndpoint.http(relayUrl, "pair", "complete", debug = debug)).post(body).build()).execute()
        return resp.use {
            check(it.isSuccessful) { "complete HTTP ${it.code}" }
            requirePairId(JSONObject(checkNotNull(it.body).string()).getString("pair_id"))
        }
    }

    internal fun requirePairId(value: String): String {
        require(java.util.UUID.fromString(value).toString() == value.lowercase()) { "invalid_pair_id" }
        return value
    }

    /** Resolve legacy membership before another pair makes implicit selection ambiguous. */
    fun sessionPairId(relayUrl: String, bearerJwt: String, deviceId: String, peerDeviceId: String,
        debug: Boolean = false): String {
        val request = Request.Builder()
            .url(PairingRelayEndpoint.http(relayUrl, "pair", "session", debug = debug))
            .header("Authorization", "Bearer $bearerJwt").get().build()
        return http.newCall(request).execute().use {
            check(it.isSuccessful) { "pair/session HTTP ${it.code}" }
            val body = JSONObject(checkNotNull(it.body).string())
            check(body.getString("device_id") == deviceId && body.getString("peer_device_id") == peerDeviceId) {
                "pair_session_identity_mismatch"
            }
            requirePairId(body.getString("pair_id"))
        }
    }

    private fun signDetached(message: ByteArray, secretKey: ByteArray): ByteArray {
        require(secretKey.size == Sign.SECRETKEYBYTES) {
            "Ed25519 secret key must be ${Sign.SECRETKEYBYTES} bytes (libsodium format)"
        }
        val signature = ByteArray(Sign.BYTES)
        val rc = sodium.crypto_sign_detached(signature, null, message, message.size.toLong(), secretKey)
        check(rc == 0) { "crypto_sign_detached rc=$rc" }
        return signature
    }
}
