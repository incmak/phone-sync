package co.twinotify.core.call

import android.app.Notification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CallCapabilitySelectorTest {
    private val selector = CallCapabilitySelector()

    @Test
    fun ringingRequiresOneDefaultDialerCandidateWithBothCapabilities() {
        val ready = assertIs<CallCapabilitySelection.Ready<String>>(
            selector.select(
                defaultDialerPackage = DEFAULT_DIALER,
                state = CallFrameworkState.RINGING,
                direction = CallDirection.INCOMING,
                candidates = listOf(candidate(answer = "answer", decline = "decline")),
            ),
        )

        assertEquals("source", ready.sourceKey)
        assertEquals(
            mapOf(
                CallControlKind.ANSWER to "answer",
                CallControlKind.DECLINE to "decline",
            ),
            ready.handles,
        )

        for (incomplete in listOf(
            candidate(answer = "answer", decline = null),
            candidate(answer = null, decline = "decline"),
            candidate(answer = "answer", decline = "decline", hangUp = "hang-up"),
        )) {
            val none = assertIs<CallCapabilitySelection.None>(
                selector.select(
                    DEFAULT_DIALER,
                    CallFrameworkState.RINGING,
                    CallDirection.INCOMING,
                    listOf(incomplete),
                ),
            )
            assertEquals("call_controls_unavailable", none.code)
        }
    }

    @Test
    fun candidateMustExactlyMatchTheDefaultDialerAndCallCategory() {
        val ready = assertIs<CallCapabilitySelection.Ready<String>>(
            selector.select(
                DEFAULT_DIALER,
                CallFrameworkState.RINGING,
                CallDirection.INCOMING,
                listOf(
                    candidate(sourceKey = "wrong-package", packageName = "$DEFAULT_DIALER.beta"),
                    candidate(sourceKey = "wrong-category", category = Notification.CATEGORY_MESSAGE),
                    candidate(sourceKey = "eligible"),
                ),
            ),
        )

        assertEquals("eligible", ready.sourceKey)
        assertEquals(
            setOf(CallControlKind.ANSWER, CallControlKind.DECLINE),
            ready.handles.keys,
        )
    }

    @Test
    fun multipleEligibleCallNotificationsFailClosedAsAmbiguous() {
        val none = assertIs<CallCapabilitySelection.None>(
            selector.select(
                DEFAULT_DIALER,
                CallFrameworkState.RINGING,
                CallDirection.INCOMING,
                listOf(
                    candidate(sourceKey = "first"),
                    candidate(sourceKey = "second", answer = "answer-2", decline = "decline-2"),
                ),
            ),
        )

        assertEquals("ambiguous_call_notification", none.code)
    }

    @Test
    fun hangupIsAvailableOnlyForAnObservedIncomingSession() {
        val ready = assertIs<CallCapabilitySelection.Ready<String>>(
            selector.select(
                DEFAULT_DIALER,
                CallFrameworkState.OFFHOOK,
                CallDirection.INCOMING,
                listOf(candidate(answer = null, decline = null, hangUp = "hang-up")),
            ),
        )
        assertEquals(mapOf(CallControlKind.HANG_UP to "hang-up"), ready.handles)

        for (direction in listOf(CallDirection.OUTGOING, CallDirection.UNKNOWN)) {
            assertIs<CallCapabilitySelection.None>(
                selector.select(
                    DEFAULT_DIALER,
                    CallFrameworkState.OFFHOOK,
                    direction,
                    listOf(candidate(answer = null, decline = null, hangUp = "hang-up")),
                ),
            )
        }
        assertIs<CallCapabilitySelection.None>(
            selector.select(
                DEFAULT_DIALER,
                CallFrameworkState.OFFHOOK,
                CallDirection.INCOMING,
                listOf(candidate(answer = "answer", decline = null, hangUp = "hang-up")),
            ),
        )
        assertIs<CallCapabilitySelection.None>(
            selector.select(
                DEFAULT_DIALER,
                CallFrameworkState.IDLE,
                CallDirection.INCOMING,
                listOf(candidate(answer = null, decline = null, hangUp = "hang-up")),
            ),
        )
    }

    @Test
    fun noEligibleCallNotificationReportsAmbiguityWithoutInspectingHandles() {
        val none = assertIs<CallCapabilitySelection.None>(
            selector.select(
                DEFAULT_DIALER,
                CallFrameworkState.RINGING,
                CallDirection.INCOMING,
                listOf(candidate(packageName = "com.example.not-default", answer = null, decline = null)),
            ),
        )

        assertEquals("ambiguous_call_notification", none.code)
    }

    private fun candidate(
        sourceKey: String = "source",
        packageName: String = DEFAULT_DIALER,
        category: String? = Notification.CATEGORY_CALL,
        answer: String? = "answer",
        decline: String? = "decline",
        hangUp: String? = null,
    ) = CallCapabilityCandidate(
        sourceKey = sourceKey,
        packageName = packageName,
        category = category,
        answer = answer,
        decline = decline,
        hangUp = hangUp,
    )

    private companion object {
        const val DEFAULT_DIALER = "com.android.dialer"
    }
}
