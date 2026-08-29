package co.twinotify.core.service

import android.app.Notification
import androidx.test.core.app.ApplicationProvider
import co.twinotify.core.actions.MirrorActionIntent
import co.twinotify.core.listener.NotifActionJson
import co.twinotify.core.listener.NotifPostJson
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
