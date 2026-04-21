package co.twinotify.core.service

import org.json.JSONObject

/** Wire format — `type:"enc"` wrapper. Matches proto/envelope-encrypted.schema.json. */
data class EncryptedEnvelope(
    val v: Int = 1,
    val type: String = "enc",
    val msgId: String,
    val originDevice: String,
    val ts: Long,
    val nonceB64: String,
    val ciphertextB64: String,
) {
    fun toJson(): String = JSONObject().apply {
        put("v", v)
        put("type", type)
        put("msg_id", msgId)
        put("origin_device", originDevice)
        put("ts", ts)
        put("nonce", nonceB64)
        put("ciphertext", ciphertextB64)
    }.toString()

    companion object {
        fun fromJson(s: String): EncryptedEnvelope {
            val o = JSONObject(s)
            return EncryptedEnvelope(
                v = o.getInt("v"),
                type = o.getString("type"),
                msgId = o.getString("msg_id"),
                originDevice = o.getString("origin_device"),
                ts = o.getLong("ts"),
                nonceB64 = o.getString("nonce"),
                ciphertextB64 = o.getString("ciphertext"),
            )
        }
    }
}
