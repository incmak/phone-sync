package expo.modules.phonesynccore.pairing

import org.json.JSONObject
import java.util.Base64
import java.util.UUID

/**
 * Payload encoded in the pairing QR code.
 * Device A generates this, displays it; Device B scans it.
 */
data class PairPayload(
    val relayUrl: String,
    val deviceId: String,
    val encPubkey: ByteArray,
    val signPubkey: ByteArray,
    val pairToken: String,
) {
    fun toJson(): String = JSONObject(mapOf(
        "relay_url" to relayUrl,
        "device_id" to deviceId,
        "enc_pubkey" to Base64.getEncoder().encodeToString(encPubkey),
        "sign_pubkey" to Base64.getEncoder().encodeToString(signPubkey),
        "pair_token" to pairToken,
    )).toString()

    companion object {
        fun newToken(): String = "pt-" + UUID.randomUUID().toString()
        fun fromJson(s: String): PairPayload {
            val j = JSONObject(s)
            return PairPayload(
                relayUrl = j.getString("relay_url"),
                deviceId = j.getString("device_id"),
                encPubkey = Base64.getDecoder().decode(j.getString("enc_pubkey")),
                signPubkey = Base64.getDecoder().decode(j.getString("sign_pubkey")),
                pairToken = j.getString("pair_token"),
            )
        }
    }
}
