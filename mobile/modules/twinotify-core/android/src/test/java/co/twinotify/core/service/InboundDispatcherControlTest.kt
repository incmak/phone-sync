package co.twinotify.core.service

import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class InboundDispatcherControlTest {
    @Test
    fun authenticatedV2UnpairCompletesProductionHandlerBeforeAcceptance() = runTest {
        val order = mutableListOf<String>()
        val result = dispatchAuthenticatedV2Unpair(
            eventType = "unpair",
            msgId = "11111111-1111-4111-8111-111111111111",
            envelopeSha256 = "a".repeat(64),
            preparePeerUnpair = { order += "handled" },
            finalizeServiceStop = { order += "stopped" },
        )

        order += "returned"
        assertEquals(listOf("handled", "returned"), order)
        val accepted = result as InboundDispatchResult.AcceptedAfterCustody
        assertEquals("11111111-1111-4111-8111-111111111111", accepted.msgId)
        assertEquals("a".repeat(64), accepted.envelopeSha256)
        accepted.finalizeAfterCustody()
        assertEquals(listOf("handled", "returned", "stopped"), order)
    }

    @Test
    fun authenticatedV2UnpairPropagatesCancellationWithoutAcceptance() = runTest {
        val expected = CancellationException("caller cancelled")
        val actual = assertFailsWith<CancellationException> {
            dispatchAuthenticatedV2Unpair(
                eventType = "unpair",
                msgId = "11111111-1111-4111-8111-111111111111",
                envelopeSha256 = "a".repeat(64),
                preparePeerUnpair = { throw expected },
                finalizeServiceStop = { error("must not run") },
            )
        }
        assertSame(expected, actual)
    }

    @Test
    fun authenticatedV2ControlHelperDoesNotClaimOtherEventTypes() = runTest {
        assertNull(
            dispatchAuthenticatedV2Unpair(
                eventType = "notif.post",
                msgId = "11111111-1111-4111-8111-111111111111",
                envelopeSha256 = "a".repeat(64),
                preparePeerUnpair = { error("must not run") },
                finalizeServiceStop = { error("must not run") },
            ),
        )
    }
}
