package co.twinotify.core.call

import co.twinotify.core.protocol.InnerEventV2
import co.twinotify.core.storage.OutboundMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.json.JSONObject

class CallControlEncoderTest {
    @Test
    fun encoderUsesCapabilityAsInvocationAndFifteenSecondExpiry() = runTest {
        val captured = mutableListOf<InnerEventV2>()
        val encoder = CallControlEncoder(
            seal = CallControlSealer { event -> captured += event; rowFor(event) },
            clock = { 1_000L },
            newId = { MESSAGE },
            originDevice = { "mirror-device" },
        )

        val row = encoder.encodeInvoke(
            CallControlInvokeInput(CANON, SESSION, 2, CONTROL, CallControlKind.ANSWER),
        )

        val inner = captured.single()
        val payload = JSONObject(inner.payloadJson)
        assertEquals(CONTROL, payload.getString("invocation_id"))
        assertEquals(CONTROL, payload.getString("control_id"))
        assertEquals(CANON, payload.getString("canon_id"))
        assertEquals(SESSION, payload.getString("call_session_id"))
        assertEquals(2, payload.getLong("call_sequence"))
        assertEquals("answer", payload.getString("kind"))
        assertEquals(1_000L, payload.getLong("invoked_at"))
        assertEquals(16_000L, inner.expiresAt)
        assertNull(inner.canonId)
        assertNull(inner.sequence)
        assertFalse(row.requiresPeerReceipt)
    }

    @Test
    fun resultUsesFiveMinuteExpiryAndOnlyBoundedFields() = runTest {
        val captured = mutableListOf<InnerEventV2>()
        val encoder = CallControlEncoder(
            seal = CallControlSealer { event -> captured += event; rowFor(event) },
            clock = { 1_000L },
            newId = { MESSAGE },
        )

        encoder.encodeResult(CallControlResultInput(CONTROL, CANON, CallControlKind.ANSWER, "dispatched"))

        val event = captured.single()
        val payload = JSONObject(event.payloadJson)
        assertEquals("call.control.result", event.type)
        assertEquals(301_000L, event.expiresAt)
        assertEquals(setOf("invocation_id", "canon_id", "kind", "status"), payload.keys().asSequence().toSet())
        assertFalse(event.payloadJson.contains("caller", ignoreCase = true))
    }

    private fun rowFor(event: InnerEventV2) = OutboundMessage(
        msgId = event.msgId, canonId = null, sequence = null, eventType = event.type,
        protocolVersion = 2, envelopeJson = "{}", envelopeSha256 = "0".repeat(64), byteSize = 2,
        createdAt = event.createdAt, expiresAt = event.expiresAt, custodyAcceptedAt = null,
        custodyRoute = null, attempts = 0, nextAttemptAt = event.createdAt, state = "NEW",
        lastError = null, requiresPeerReceipt = false,
    )

    private companion object {
        const val SESSION = "11111111-1111-4111-8111-111111111111"
        const val CONTROL = "22222222-2222-4222-8222-222222222222"
        const val MESSAGE = "33333333-3333-4333-8333-333333333333"
        const val CANON = "call:$SESSION"
    }
}
