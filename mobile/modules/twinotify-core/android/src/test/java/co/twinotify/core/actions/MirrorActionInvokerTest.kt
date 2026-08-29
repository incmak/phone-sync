package co.twinotify.core.actions

import co.twinotify.core.listener.NotifActionJson
import co.twinotify.core.storage.ActionInvocation
import co.twinotify.core.storage.ActionInvocationOutboxCommitResult
import co.twinotify.core.storage.OutboundMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class MirrorActionInvokerTest {
    @Test
    fun lockedDeviceDoesNotResolveEncodeOrCommit() = runTest {
        val fixture = Fixture(locked = true)

        assertEquals(MirrorActionInvokeResult.Locked, fixture.invoker.invoke(IDENTITY, null))
        assertEquals(0, fixture.loads)
        assertNull(fixture.committedInvocation)
    }

    @Test
    fun missingOrCancelledTargetDoesNotTransmit() = runTest {
        val fixture = Fixture(target = null)

        assertEquals(MirrorActionInvokeResult.Gone, fixture.invoker.invoke(IDENTITY, null))
        assertNull(fixture.committedInvocation)
    }

    @Test
    fun replyShapeAndUtf8LimitAreValidatedBeforeEncoding() = runTest {
        val nonReply = Fixture(target = target(reply = false))
        assertEquals(MirrorActionInvokeResult.InvalidReply, nonReply.invoker.invoke(IDENTITY, "hello"))
        assertNull(nonReply.committedInvocation)

        val missingReply = Fixture(target = target(reply = true))
        assertEquals(MirrorActionInvokeResult.InvalidReply, missingReply.invoker.invoke(IDENTITY, null))
        assertNull(missingReply.committedInvocation)

        val oversized = Fixture(target = target(reply = true))
        assertEquals(
            MirrorActionInvokeResult.InvalidReply,
            oversized.invoker.invoke(IDENTITY, "€".repeat(1_366)),
        )
        assertNull(oversized.committedInvocation)
    }

    @Test
    fun queuedInvokeCommitsThenSignalsSchedulesAndReposts() = runTest {
        val events = mutableListOf<String>()
        val fixture = Fixture(target = target(reply = true), events = events)

        assertEquals(MirrorActionInvokeResult.Queued(INVOCATION_ID), fixture.invoker.invoke(IDENTITY, "hello"))

        assertEquals(listOf("encode", "commit", "signal", "schedule", "repost"), events)
        assertEquals(
            ActionInvocation(
                invocationId = INVOCATION_ID,
                canonId = CANON_ID,
                actionId = ACTION_ID,
                notificationSequence = 7,
                replyText = "hello",
                state = "PENDING",
                createdAt = 2_000,
                expiresAt = 122_000,
                updatedAt = 2_000,
            ),
            fixture.committedInvocation,
        )
        assertEquals(122_000, fixture.scheduledAt)
    }

    private class Fixture(
        locked: Boolean = false,
        target: MirrorActionTarget? = target(reply = false),
        events: MutableList<String> = mutableListOf(),
    ) {
        var loads = 0
        var committedInvocation: ActionInvocation? = null
        var scheduledAt: Long? = null

        val invoker = MirrorActionInvoker(
            isDeviceLocked = { locked },
            loadTarget = MirrorActionTargetLoader {
                loads += 1
                target
            },
            encode = ActionInvokeRowEncoder {
                events += "encode"
                outbound()
            },
            commit = MirrorActionCommitter { invocation, _ ->
                events += "commit"
                committedInvocation = invocation
                ActionInvocationOutboxCommitResult.Committed
            },
            signalTransport = { events += "signal" },
            scheduleExpiry = ActionInvocationExpiryScheduler { dueAt ->
                events += "schedule"
                scheduledAt = dueAt
            },
            repost = MirrorActionReposter {
                events += "repost"
            },
            newId = { INVOCATION_ID },
        )
    }

    private companion object {
        const val ACTION_ID = "33333333-3333-4333-8333-333333333333"
        const val INVOCATION_ID = "22222222-2222-4222-8222-222222222222"
        const val CANON_ID = "peer:com.example:7:tag"
        val IDENTITY = ActionInvokeIdentity("mirror-tag", 41, ACTION_ID)

        fun target(reply: Boolean) = MirrorActionTarget(
            canonId = CANON_ID,
            notificationSequence = 7,
            localTag = "mirror-tag",
            localId = 41,
            action = NotifActionJson(ACTION_ID, "Reply", 1, reply, "Message"),
        )

        fun outbound() = OutboundMessage(
            msgId = "11111111-1111-4111-8111-111111111111",
            canonId = null,
            sequence = null,
            eventType = "notif.action.invoke",
            protocolVersion = 2,
            envelopeJson = "{}",
            envelopeSha256 = "0".repeat(64),
            byteSize = 2,
            createdAt = 2_000,
            expiresAt = 122_000,
            custodyAcceptedAt = null,
            custodyRoute = null,
            attempts = 0,
            nextAttemptAt = 2_000,
            state = "NEW",
            lastError = null,
            requiresPeerReceipt = false,
        )
    }
}
