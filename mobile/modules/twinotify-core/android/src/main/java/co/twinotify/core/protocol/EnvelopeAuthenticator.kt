package co.twinotify.core.protocol

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Opens a v2 envelope only after its relay-visible metadata has been checked against the
 * authenticated plaintext. Callers may use [envelopeSha256] as the immutable Room idempotency
 * digest; no replay decision occurs before this boundary succeeds.
 */
class EnvelopeAuthenticator(
    private val decryptor: PayloadDecryptor,
    private val peerDeviceId: String,
) {
    init {
        require(peerDeviceId.isNotEmpty()) { "paired peer device ID is required" }
    }

    fun open(rawEnvelope: String): AuthenticatedEnvelope {
        val outer = ProtocolJson.decodeEnvelope(rawEnvelope)
        if (outer.originDevice != peerDeviceId) {
            throw EnvelopeMismatchException("outer origin_device is not the paired peer")
        }
        val plaintext = try {
            decryptor.decrypt(outer)
        } catch (error: ProtocolException) {
            throw error
        } catch (error: Throwable) {
            throw ProtocolException("unable to decrypt v2 envelope", error)
        }
        if (plaintext.size > ProtocolJson.MAX_ENVELOPE_BYTES) {
            throw ProtocolException("decrypted event exceeds ${ProtocolJson.MAX_ENVELOPE_BYTES} bytes")
        }
        val inner = ProtocolJson.decodeInner(decodeUtf8(plaintext))
        validateOuterAndInner(outer, inner)
        return AuthenticatedEnvelope(
            outer = outer,
            inner = inner,
            envelopeSha256 = sha256Hex(rawEnvelope.encodeToByteArray()),
        )
    }

    private fun validateOuterAndInner(outer: EncryptedEnvelope, inner: InnerEventV2) {
        if (inner.originDevice != peerDeviceId) {
            throw EnvelopeMismatchException("inner origin_device is not the paired peer")
        }
        if (outer.msgId != inner.msgId) {
            throw EnvelopeMismatchException("outer and inner msg_id differ")
        }
        if (outer.originDevice != inner.originDevice) {
            throw EnvelopeMismatchException("outer and inner origin_device differ")
        }
        if (outer.createdAt != inner.createdAt) {
            throw EnvelopeMismatchException("outer and inner created_at differ")
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (error: CharacterCodingException) {
        throw ProtocolException("decrypted event is not UTF-8", error)
    }

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
