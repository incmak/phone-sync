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

/**
 * HTTP client for /pair/init and /pair/complete.
 *
 * Role split:
 *   - Device A calls `initiate()` to register with the relay and display QR.
 *   - Device A produces the confirmation_sig via `deviceASignConfirmation()`
 *     after seeing B's fingerprint and user approval.
 *   - Device B calls `deviceBCompletePair()` with A's confirmation_sig
 *     (conveyed out-of-band in v1; Phase 3 adds WebSocket push).
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

    /** Called on Device A. Registers pending pair with relay. */
    fun initiate(relayUrl: String, token: String, deviceId: String, encPub: ByteArray, signPub: ByteArray) {
        val body = JSONObject(mapOf(
            "pair_token" to token,
            "device_id" to deviceId,
            "enc_pubkey" to Base64.getEncoder().encodeToString(encPub),
            "sign_pubkey" to Base64.getEncoder().encodeToString(signPub),
        )).toString().toRequestBody(JSON)
        val resp = http.newCall(Request.Builder().url("$relayUrl/pair/init").post(body).build()).execute()
        check(resp.isSuccessful) { "init HTTP ${resp.code}" }
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
        val resp = http.newCall(Request.Builder().url("$relayUrl/pair/complete").post(body).build()).execute()
        check(resp.isSuccessful) { "complete HTTP ${resp.code}" }
        resp.close()
    }
}
