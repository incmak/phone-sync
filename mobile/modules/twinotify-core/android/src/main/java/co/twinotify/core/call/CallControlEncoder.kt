package co.twinotify.core.call

import android.content.Context
import co.twinotify.core.protocol.InnerEventV2
import co.twinotify.core.protocol.ProtocolJson
import co.twinotify.core.service.DurablePeerControlSealer
import co.twinotify.core.storage.DeviceIdentity
import co.twinotify.core.storage.OutboundMessage
import java.util.UUID
import org.json.JSONObject

data class CallControlInvokeInput(
    val canonId: String,
    val callSessionId: String,
    val callSequence: Long,
    val controlId: String,
    val kind: CallControlKind,
)

data class CallControlResultInput(
    val invocationId: String,
    val canonId: String,
    val kind: CallControlKind,
    val status: String,
)

fun interface CallControlSealer {
    suspend fun seal(event: InnerEventV2): OutboundMessage
}

class CallControlEncoder(
    private val seal: CallControlSealer,
    private val clock: () -> Long = { System.currentTimeMillis().coerceAtLeast(0L) },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val originDevice: suspend () -> String = { "local-device" },
) {
    constructor(context: Context) : this(
        seal = DurableCallControlSealer(context.applicationContext),
        originDevice = { DeviceIdentity.getOrCreate(context.applicationContext) },
    )

    suspend fun encodeInvoke(input: CallControlInvokeInput): OutboundMessage {
        require(input.canonId == "call:${input.callSessionId}")
        require(input.callSequence > 0)
        requireCanonicalUuid(input.controlId)
        val now = clock()
        return encode(
            type = "call.control.invoke",
            createdAt = now,
            expiresAt = Math.addExact(now, INVOKE_TTL_MS),
            payload = JSONObject()
                .put("invocation_id", input.controlId)
                .put("canon_id", input.canonId)
                .put("call_session_id", input.callSessionId)
                .put("call_sequence", input.callSequence)
                .put("control_id", input.controlId)
                .put("kind", input.kind.wire)
                .put("invoked_at", now),
        )
    }

    suspend fun encodeResult(input: CallControlResultInput): OutboundMessage {
        requireCanonicalUuid(input.invocationId)
        require(input.status in RESULT_STATUSES)
        val now = clock()
        return encode(
            type = "call.control.result",
            createdAt = now,
            expiresAt = Math.addExact(now, RESULT_TTL_MS),
            payload = JSONObject()
                .put("invocation_id", input.invocationId)
                .put("canon_id", input.canonId)
                .put("kind", input.kind.wire)
                .put("status", input.status),
        )
    }

    private suspend fun encode(type: String, createdAt: Long, expiresAt: Long, payload: JSONObject): OutboundMessage {
        val event = InnerEventV2(newId(), originDevice(), type, null, null, createdAt, expiresAt, payload.toString())
        ProtocolJson.encodeInner(event)
        return seal.seal(event)
    }

    private fun requireCanonicalUuid(value: String) {
        require(runCatching { UUID.fromString(value).toString() }.getOrNull() == value)
    }

    companion object {
        const val INVOKE_TTL_MS = 15_000L
        const val RESULT_TTL_MS = 5 * 60_000L
        val RESULT_STATUSES = setOf(
            "dispatched", "outcome_unknown", "capability_gone", "call_gone",
            "stale_state", "expired", "failed",
        )
    }
}

private class DurableCallControlSealer(context: Context) : CallControlSealer {
    private val delegate = DurablePeerControlSealer(context)
    override suspend fun seal(event: InnerEventV2): OutboundMessage =
        delegate.seal(event, requiresPeerReceipt = false)
}
