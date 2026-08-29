package co.twinotify.core.service

import android.app.Notification
import androidx.test.core.app.ApplicationProvider
import co.twinotify.core.actions.MirrorActionIntent
import co.twinotify.core.listener.NotifActionJson
import co.twinotify.core.listener.NotifPostJson
import co.twinotify.core.storage.ActionInvocation
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class MirrorActionNotificationTest {
    @Test
    fun mirrorBuildsStandaloneAuthenticatedReplyAndButtonActions() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val notification = MirrorPoster.buildNotification(
            context,
            post(
                actions = listOf(
                    NotifActionJson(REPLY_ID, "Reply", Notification.Action.SEMANTIC_ACTION_REPLY, true, "Message"),
                    NotifActionJson(BUTTON_ID, "Archive", Notification.Action.SEMANTIC_ACTION_ARCHIVE, false, null),
                ),
            ),
            localId = 41,
            localTag = "mirror-tag",
        )

        val reply = notification.actions[0]
        assertTrue(reply.isAuthenticationRequired)
        assertFalse(reply.allowGeneratedReplies)
        assertFalse(reply.actionIntent.isImmutable)
        assertEquals(MirrorActionIntent.REMOTE_INPUT_KEY, reply.remoteInputs.single().resultKey)
        assertEquals("Message", reply.remoteInputs.single().label)

        val button = notification.actions[1]
        assertTrue(button.isAuthenticationRequired)
        assertFalse(button.allowGeneratedReplies)
        assertTrue(button.actionIntent.isImmutable)
        assertTrue(button.remoteInputs.isNullOrEmpty())
    }

    @Test
    fun pendingReplyIsVisibleAndCannotBeInvokedTwice() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val notification = MirrorPoster.buildNotification(
            context,
            post(
                actions = listOf(
                    NotifActionJson(REPLY_ID, "Reply", Notification.Action.SEMANTIC_ACTION_REPLY, true, "Message"),
                    NotifActionJson(BUTTON_ID, "Archive", Notification.Action.SEMANTIC_ACTION_ARCHIVE, false, null),
                ),
            ),
            localId = 41,
            localTag = "mirror-tag",
            invocations = listOf(invocation(REPLY_ID, "PENDING", "private reply")),
        )

        assertEquals("Sending\u2026", notification.extras.getString(Notification.EXTRA_SUB_TEXT))
        assertEquals("private reply", notification.extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY)?.single())
        assertEquals(1, notification.actions.size)
        assertEquals("Archive", notification.actions.single().title)
    }

    @Test
    fun terminalResultUsesSafeLocalizedPresentation() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val expected = mapOf(
            "DISPATCHED" to "Sent",
            "OUTCOME_UNKNOWN" to "Unconfirmed",
            "FAILED" to "Could not send",
            "ACTION_GONE" to "Action unavailable",
            "NOTIFICATION_GONE" to "Notification unavailable",
            "EXPIRED" to "Timed out",
        )

        expected.forEach { (state, label) ->
            val notification = MirrorPoster.buildNotification(
                context,
                post(actions = listOf(NotifActionJson(BUTTON_ID, "Archive", 0, false, null))),
                localId = 41,
                localTag = "mirror-tag",
                invocations = listOf(invocation(BUTTON_ID, state, null)),
            )
            assertEquals(label, notification.extras.getString(Notification.EXTRA_SUB_TEXT))
        }
    }

    private fun invocation(actionId: String, state: String, replyText: String?) = ActionInvocation(
        invocationId = "55555555-5555-4555-8555-555555555555",
        canonId = "peer:com.example:7:tag",
        actionId = actionId,
        notificationSequence = 7,
        replyText = replyText,
        state = state,
        createdAt = 1_000,
        expiresAt = 121_000,
        updatedAt = 2_000,
    )

    private fun post(actions: List<NotifActionJson>) = NotifPostJson(
        type = "notif.post",
        canon_id = "peer:com.example:7:tag",
        app_name = "Example",
        package_name = "com.example",
        id = 7,
        tag = "tag",
        title = "Title",
        text = "Body",
        sub_text = null,
        big_text = null,
        visibility = "private",
        is_group_summary = false,
        is_ongoing = false,
        is_clearable = true,
        small_icon_png_b64 = null,
        large_icon_png_b64 = null,
        ts = 1_000,
        actions = actions,
    )

    private companion object {
        const val REPLY_ID = "33333333-3333-4333-8333-333333333333"
        const val BUTTON_ID = "44444444-4444-4444-8444-444444444444"
    }
}
