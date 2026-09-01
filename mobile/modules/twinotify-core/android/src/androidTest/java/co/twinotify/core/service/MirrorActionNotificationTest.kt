package co.twinotify.core.service

import android.app.Notification
import android.app.NotificationManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import co.twinotify.core.actions.MirrorActionIntent
import co.twinotify.core.listener.NotifActionJson
import co.twinotify.core.listener.NotifConversationJson
import co.twinotify.core.listener.NotifMessageJson
import co.twinotify.core.listener.NotifPostJson
import co.twinotify.core.storage.ActionInvocation
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class MirrorActionNotificationTest {
    @Test
    fun distinctMirrorsCoexistWhileConversationUpdatesInPlaceAndCancelIndependently() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val manager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as NotificationManager
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.POST_NOTIFICATIONS)
        val firstTag = NotificationStateReducer.stableMirrorTag("peer:chat:7:first")
        val secondTag = NotificationStateReducer.stableMirrorTag("peer:chat:8:second")
        try {
            val first = post(emptyList()).copy(
                canon_id = "peer:chat:7:first",
                id = 7,
                tag = "first",
                conversation = conversation("First"),
            )
            val second = post(emptyList()).copy(
                canon_id = "peer:chat:8:second",
                id = 8,
                tag = "second",
                conversation = conversation("Other"),
            )
            manager.notify(firstTag, 71, MirrorPoster.buildNotification(context, first, 71, firstTag, detailId = DETAIL_ID))
            manager.notify(secondTag, 72, MirrorPoster.buildNotification(context, second, 72, secondTag, detailId = DETAIL_ID))
            assertEquals(2, awaitActive(manager, firstTag, secondTag).size)

            val updated = first.copy(conversation = conversation("First", "Second", "Third"))
            manager.notify(firstTag, 71, MirrorPoster.buildNotification(context, updated, 71, firstTag, detailId = DETAIL_ID))
            val active = awaitMessages(manager, firstTag, expectedCount = 3).let {
                awaitActive(manager, firstTag, secondTag)
            }
            assertEquals(2, active.size)
            val firstMessages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(
                active.getValue(firstTag).notification.extras.getParcelableArray(Notification.EXTRA_MESSAGES),
            )
            assertEquals(listOf("First", "Second", "Third"), firstMessages.map { it.text.toString() })

            manager.cancel(firstTag, 71)
            awaitAbsent(manager, firstTag)
            assertTrue(manager.activeNotifications.any { it.tag == secondTag && it.id == 72 })
            manager.cancel(secondTag, 72)
            awaitAbsent(manager, secondTag)
        } finally {
            manager.cancel(firstTag, 71)
            manager.cancel(secondTag, 72)
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    @Test
    fun conversationMirrorUsesMessagingStyleAndPreservesMessageOrder() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val notification = MirrorPoster.buildNotification(
            context,
            post(actions = emptyList()).copy(
                conversation = NotifConversationJson(
                    key = "chat-42",
                    title = "Weekend plans",
                    is_group = true,
                    messages = listOf(
                        NotifMessageJson("First", 1_000, "Ada", "ada"),
                        NotifMessageJson("Second", 1_001, "Ben", "ben"),
                        NotifMessageJson("Third", 1_002, "Ada", "ada"),
                    ),
                ),
            ),
            localId = 41,
            localTag = "mirror-tag",
            detailId = DETAIL_ID,
        )

        assertEquals("Weekend plans", notification.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE))
        val messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(
            notification.extras.getParcelableArray(Notification.EXTRA_MESSAGES),
        )
        assertEquals(listOf("First", "Second", "Third"), messages.map { it.text.toString() })
        assertEquals(listOf("Ada", "Ben", "Ada"), messages.map { it.senderPerson?.name.toString() })
    }

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
            detailId = DETAIL_ID,
        )

        assertTrue(notification.contentIntent.isActivity)
        assertTrue(notification.contentIntent.isImmutable)

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
            detailId = DETAIL_ID,
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
                detailId = DETAIL_ID,
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

    private fun conversation(vararg text: String) = NotifConversationJson(
        key = "chat-42",
        title = "Weekend plans",
        is_group = true,
        messages = text.mapIndexed { index, value ->
            NotifMessageJson(value, 1_000L + index, if (index % 2 == 0) "Ada" else "Ben", null)
        },
    )

    private fun awaitActive(
        manager: NotificationManager,
        vararg tags: String,
    ): Map<String, android.service.notification.StatusBarNotification> {
        repeat(40) {
            val active = manager.activeNotifications
                .filter { it.tag in tags }
                .associateBy { requireNotNull(it.tag) }
            if (active.size == tags.size) return active
            SystemClock.sleep(50)
        }
        return manager.activeNotifications.filter { it.tag in tags }.associateBy { requireNotNull(it.tag) }
    }

    private fun awaitAbsent(manager: NotificationManager, tag: String) {
        repeat(40) {
            if (manager.activeNotifications.none { it.tag == tag }) return
            SystemClock.sleep(50)
        }
    }

    private fun awaitMessages(
        manager: NotificationManager,
        tag: String,
        expectedCount: Int,
    ) {
        repeat(40) {
            val notification = manager.activeNotifications.firstOrNull { it.tag == tag }?.notification
            val messages = notification?.extras?.getParcelableArray(Notification.EXTRA_MESSAGES)
            if (Notification.MessagingStyle.Message.getMessagesFromBundleArray(messages).size == expectedCount) return
            SystemClock.sleep(50)
        }
    }

    private companion object {
        const val REPLY_ID = "33333333-3333-4333-8333-333333333333"
        const val BUTTON_ID = "44444444-4444-4444-8444-444444444444"
        const val DETAIL_ID = "11111111-1111-4111-8111-111111111111"
    }
}
