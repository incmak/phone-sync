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
 *   - Device B calls `deviceBCompletePair()` after receiving the sig from relay.
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

    /**
     * Normalizes the user-entered relay URL (e.g. "ws://host:8080/ws") into a plain HTTP origin
     * suitable for POSTs (e.g. "http://host:8080"). Strips any /ws suffix and swaps ws(s) schemes.
     */
    internal fun relayOrigin(relayUrl: String): String {
        var u = relayUrl.trim().trimEnd('/')
        u = u.replaceFirst(Regex("^wss://"), "https://")
        u = u.replaceFirst(Regex("^ws://"), "http://")
        // Drop a trailing "/ws" path segment if present (user-entered WS URL).
        u = u.removeSuffix("/ws")
        return u
    }

    /** Called on Device A. Registers pending pair with relay. */
    fun initiate(
        relayUrl: String, token: String, deviceId: String,
        encPub: ByteArray, signPub: ByteArray,
        displayName: String? = null,
    ) {
        val map = mutableMapOf<String, Any>(
            "pair_token" to token,
            "device_id" to deviceId,
            "enc_pubkey" to Base64.getEncoder().encodeToString(encPub),
            "sign_pubkey" to Base64.getEncoder().encodeToString(signPub),
        )
        if (!displayName.isNullOrBlank()) map["display_name"] = displayName
        val body = JSONObject(map.toMap()).toString().toRequestBody(JSON)
        val resp = http.newCall(Request.Builder().url("${relayOrigin(relayUrl)}/pair/init").post(body).build()).execute()
        check(resp.isSuccessful) { "init HTTP ${resp.code}" }
        resp.close()
    }

    /** Device B → relay: announce own pubkeys + optional display name. Relay forwards to A. */
    fun sendPeerHello(
        relayUrl: String, token: String, deviceId: String,
        bEncPub: ByteArray, bSignPub: ByteArray,
        displayName: String? = null,
    ) {
        val map = mutableMapOf<String, Any>(
            "pair_token" to token,
            "device_id" to deviceId,
            "enc_pubkey" to Base64.getEncoder().encodeToString(bEncPub),
            "sign_pubkey" to Base64.getEncoder().encodeToString(bSignPub),
        )
        if (!displayName.isNullOrBlank()) map["display_name"] = displayName
        val body = JSONObject(map.toMap()).toString().toRequestBody(JSON)
        val resp = http.newCall(Request.Builder().url("${relayOrigin(relayUrl)}/pair/hello").post(body).build()).execute()
        check(resp.isSuccessful) { "pair/hello HTTP ${resp.code}" }
        resp.close()
    }

    /** Device A → relay: push confirmation_sig. Relay forwards to B. */
    fun sendConfirmationSig(relayUrl: String, token: String, sig: ByteArray) {
        val body = JSONObject(mapOf(
            "pair_token" to token,
            "confirmation_sig" to Base64.getEncoder().encodeToString(sig),
        )).toString().toRequestBody(JSON)
        val resp = http.newCall(Request.Builder().url("${relayOrigin(relayUrl)}/pair/send_sig").post(body).build()).execute()
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
        val sig = ByteArray(Sign.BYTES)
        val rc = sodium.crypto_sign_detached(sig, null, msg, msg.size.toLong(), aSignSecret)
        check(rc == 0) { "crypto_sign_detached rc=$rc" }
        return sig
    }

    /** Called on Device B once it has A's confirmation sig. */
    fun deviceBCompletePair(
        relayUrl: String, token: String, deviceId: String,
        bEncPub: ByteArray, bSignPub: ByteArray,
        confirmationSig: ByteArray,
    ) {
        val body = JSONObject(mapOf(
            "pair_token" to token,
            "device_id" to deviceId,
            "enc_pubkey" to Base64.getEncoder().encodeToString(bEncPub),
            "sign_pubkey" to Base64.getEncoder().encodeToString(bSignPub),
            "confirmation_sig" to Base64.getEncoder().encodeToString(confirmationSig),
        )).toString().toRequestBody(JSON)
        val resp = http.newCall(Request.Builder().url("${relayOrigin(relayUrl)}/pair/complete").post(body).build()).execute()
        check(resp.isSuccessful) { "complete HTTP ${resp.code}" }
        resp.close()
    }
}
