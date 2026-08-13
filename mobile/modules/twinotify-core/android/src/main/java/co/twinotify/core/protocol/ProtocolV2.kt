package co.twinotify.core.protocol

import org.json.JSONObject

/** Immutable, authenticated plaintext carried inside a version 2 envelope. */
data class InnerEventV2(
    val msgId: String,
    val originDevice: String,
    val type: String,
    val canonId: String?,
    val sequence: Long?,
    val createdAt: Long,
    val expiresAt: Long,
    val payloadJson: String,
) {
    fun payloadObject(): JSONObject = JSONObject(payloadJson)
}

/** Relay-visible wrapper for an encrypted version 2 event. */
data class EncryptedEnvelope(
    val version: Int,
    val msgId: String,
    val originDevice: String,
    val createdAt: Long,
    val nonceB64: String,
    val ciphertextB64: String,
)

/** A v2 envelope whose encrypted metadata has been decrypted and cross-checked. */
data class AuthenticatedEnvelope(
    val outer: EncryptedEnvelope,
    val inner: InnerEventV2,
    val envelopeSha256: String,
)

fun interface PayloadDecryptor {
    fun decrypt(envelope: EncryptedEnvelope): ByteArray
}

open class ProtocolException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

class EnvelopeMismatchException(message: String) : ProtocolException(message)
