package co.twinotify.core.actions

import co.twinotify.core.protocol.InnerEventV2
import co.twinotify.core.storage.OutboundMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.json.JSONObject

class ActionControlEncoderTest {
    @Test
    fun invokeInputDebugStringNeverContainsReplyContent() {
        val input = ActionInvokeInput(
            invocationId = "22222222-2222-4222-8222-222222222222",
            canonId = "origin:pkg:1:tag",
            actionId = "33333333-3333-4333-8333-333333333333",
            notificationSequence = 7,
            replyText = "private reply",
        )

        assertFalse(input.toString().contains("private reply"))
    }

    @Test
    fun invokeUsesTwoMinuteControlLaneEnvelopeWithoutPeerReceipt() = runTest {
        val captured = mutableListOf<InnerEventV2>()
        val encoder = ActionControlEncoder(
            seal = ActionControlSealer { event -> captured += event; rowFor(event) },
            clock = { 1_000L },
            newId = { "11111111-1111-4111-8111-111111111111" },
        )

        val row = encoder.encodeInvoke(
            ActionInvokeInput(
                invocationId = "22222222-2222-4222-8222-222222222222",
                canonId = "origin:pkg:1:tag",
                actionId = "33333333-3333-4333-8333-333333333333",
                notificationSequence = 7,
                replyText = "private reply",
            ),
        )

        val event = captured.single()
        assertEquals("notif.action.invoke", event.type)
        assertEquals(121_000L, event.expiresAt)
        assertNull(event.canonId)
        assertNull(event.sequence)
        assertEquals("private reply", JSONObject(event.payloadJson).getString("reply_text"))
        assertFalse(row.requiresPeerReceipt)
        assertEquals(121_000L, row.expiresAt)
    }

    @Test
    fun resultUsesTenMinuteControlLaneEnvelopeAndSafeStatus() = runTest {
        val captured = mutableListOf<InnerEventV2>()
        val encoder = ActionControlEncoder(
            seal = ActionControlSealer { event -> captured += event; rowFor(event) },
            clock = { 2_000L },
            newId = { "44444444-4444-4444-8444-444444444444" },
        )

        val row = encoder.encodeResult(
            ActionResultInput(
                invocationId = "22222222-2222-4222-8222-222222222222",
                canonId = "origin:pkg:1:tag",
                status = "outcome_unknown",
            ),
        )

        val event = captured.single()
        assertEquals("notif.action.result", event.type)
        assertEquals(602_000L, event.expiresAt)
        assertEquals("outcome_unknown", JSONObject(event.payloadJson).getString("status"))
        assertFalse(row.requiresPeerReceipt)
        assertEquals("notif.action.result", row.eventType)
    }

    private fun rowFor(event: InnerEventV2) = OutboundMessage(
        msgId = event.msgId,
        canonId = event.canonId,
        sequence = event.sequence,
        eventType = event.type,
        protocolVersion = 2,
        envelopeJson = "{}",
        envelopeSha256 = "0".repeat(64),
        byteSize = 2,
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
