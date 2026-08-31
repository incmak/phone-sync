package co.twinotify.core.actions

import android.content.Context
import co.twinotify.core.protocol.InnerEventV2
import co.twinotify.core.protocol.ProtocolJson
import co.twinotify.core.service.DurablePeerControlSealer
import co.twinotify.core.storage.DeviceIdentity
import co.twinotify.core.storage.OutboundMessage
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
    private val delegate = DurablePeerControlSealer(context)

    override suspend fun seal(event: InnerEventV2): OutboundMessage =
        delegate.seal(event, requiresPeerReceipt = false)
}
