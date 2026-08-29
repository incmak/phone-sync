package co.twinotify.core.actions

import android.content.Context
import android.util.Base64
import co.twinotify.core.crypto.CryptoStore
import co.twinotify.core.crypto.Encrypter
import co.twinotify.core.crypto.NonceSource
import co.twinotify.core.protocol.EncryptedEnvelope
import co.twinotify.core.protocol.InnerEventV2
import co.twinotify.core.protocol.ProtocolJson
import co.twinotify.core.storage.DeviceIdentity
import co.twinotify.core.storage.OutboundMessage
import co.twinotify.core.storage.PeerStore
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

data class ActionInvokeInput(
    val invocationId: String,
    val canonId: String,
    val actionId: String,
    val notificationSequence: Long,
    val replyText: String?,
) {
    override fun toString(): String =
        "ActionInvokeInput(invocationId=$invocationId, canonId=$canonId, actionId=$actionId, " +
            "notificationSequence=$notificationSequence, replyText=${if (replyText == null) "null" else "<redacted>"})"
}

data class ActionResultInput(
    val invocationId: String,
    val canonId: String,
    val status: String,
)

fun interface ActionControlSealer {
    suspend fun seal(event: InnerEventV2): OutboundMessage
}

class ActionControlEncoder(
    private val seal: ActionControlSealer,
    private val clock: () -> Long = { System.currentTimeMillis().coerceAtLeast(0L) },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val originDevice: suspend () -> String = { "local-device" },
) {
    constructor(context: Context) : this(
        seal = DurableActionControlSealer(context.applicationContext),
        originDevice = { DeviceIdentity.getOrCreate(context.applicationContext) },
    )

    suspend fun encodeInvoke(input: ActionInvokeInput): OutboundMessage {
        val createdAt = clock()
        return encode(
            type = "notif.action.invoke",
            createdAt = createdAt,
            expiresAt = createdAt + INVOKE_TTL_MS,
            payload = JSONObject().apply {
                put("invocation_id", input.invocationId)
                put("canon_id", input.canonId)
                put("action_id", input.actionId)
                put("notification_sequence", input.notificationSequence)
                input.replyText?.let { put("reply_text", it) }
                put("invoked_at", createdAt)
            },
        )
    }

    suspend fun encodeResult(input: ActionResultInput): OutboundMessage {
        val createdAt = clock()
        return encode(
            type = "notif.action.result",
            createdAt = createdAt,
            expiresAt = createdAt + RESULT_TTL_MS,
            payload = JSONObject()
                .put("invocation_id", input.invocationId)
                .put("canon_id", input.canonId)
                .put("status", input.status),
        )
    }

    private suspend fun encode(
        type: String,
        createdAt: Long,
        expiresAt: Long,
        payload: JSONObject,
    ): OutboundMessage {
        val event = InnerEventV2(
            msgId = newId(),
            originDevice = originDevice(),
            type = type,
            canonId = null,
            sequence = null,
            createdAt = createdAt,
            expiresAt = expiresAt,
            payloadJson = payload.toString(),
        )
        ProtocolJson.encodeInner(event)
        return seal.seal(event)
    }

    private companion object {
        const val INVOKE_TTL_MS = 120_000L
        const val RESULT_TTL_MS = 600_000L
    }
}

private class DurableActionControlSealer(private val context: Context) : ActionControlSealer {
    override suspend fun seal(event: InnerEventV2): OutboundMessage {
        val peer = PeerStore.load(context) ?: throw IllegalStateException("action control requires a paired peer")
        val (box, _) = CryptoStore.loadOrGenerate(context)
        val nonce = NonceSource.next(context)
        val ciphertext = Encrypter.encrypt(
            plain = ProtocolJson.encodeInner(event).toByteArray(Charsets.UTF_8),
            nonce = nonce,
            peerPubkey = peer.encPubkey,
            ownSecret = box.secretKey,
        )
        val envelope = ProtocolJson.encodeEnvelope(
            EncryptedEnvelope(
                version = ProtocolJson.VERSION,
                msgId = event.msgId,
                originDevice = event.originDevice,
                createdAt = event.createdAt,
                nonceB64 = Base64.encodeToString(nonce, Base64.NO_WRAP),
                ciphertextB64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            ),
        )
        return OutboundMessage(
            msgId = event.msgId,
            canonId = null,
            sequence = null,
            eventType = event.type,
            protocolVersion = ProtocolJson.VERSION,
            envelopeJson = envelope,
            envelopeSha256 = sha256(envelope),
            byteSize = envelope.toByteArray(Charsets.UTF_8).size.toLong(),
            createdAt = event.createdAt,
            expiresAt = event.expiresAt,
            custodyAcceptedAt = null,
            custodyRoute = null,
            attempts = 0,
            nextAttemptAt = event.createdAt,
            state = "NEW",
            lastError = null,
            requiresPeerReceipt = false,
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
