package co.twinotify.core.listener

import android.app.Notification
import android.app.Person
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotifPostBuilderAndroidTest {
    @Test
    fun captureCopiesBoundedMessagingHistoryInSourceOrder() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val style = Notification.MessagingStyle(Person.Builder().setName("Me").setKey("self").build())
            .setConversationTitle("Weekend plans")
            .setGroupConversation(true)
        repeat(30) { index ->
            val sender = Person.Builder()
                .setName(if (index % 2 == 0) "Ada" else "Ben")
                .setKey(if (index % 2 == 0) "ada" else "ben")
                .build()
            style.addMessage("Message $index", 1_000L + index, sender)
        }
        val notification = Notification.Builder(context, "test")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setShortcutId("chat-42")
            .setStyle(style)
            .build()
        val sbn = StatusBarNotification(
            "com.example.chat",
            "com.example.chat",
            7,
            "conversation",
            Process.myUid(),
            Process.myPid(),
            0,
            notification,
            Process.myUserHandle(),
            2_000L,
        )

        val conversation = assertNotNull(
            NotifPostBuilder.captureSnapshot(sbn, context, emptySet())?.conversation,
        )

        assertEquals("chat-42", conversation.key)
        assertEquals("Weekend plans", conversation.title)
        assertEquals(true, conversation.isGroup)
        assertEquals(25, conversation.messages.size)
        assertEquals("Message 5", conversation.messages.first().text)
        assertEquals("Ben", conversation.messages.first().senderName)
        assertEquals("Message 29", conversation.messages.last().text)
        assertEquals("Ben", conversation.messages.last().senderName)
    }
}
