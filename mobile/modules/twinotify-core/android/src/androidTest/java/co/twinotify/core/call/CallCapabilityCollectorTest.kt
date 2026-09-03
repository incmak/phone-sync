package co.twinotify.core.call

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CallCapabilityCollectorTest {
    @Test
    @Suppress("DEPRECATION")
    fun captureReturnsOnlyTheThreeTypedCallIntentsAndBoundedMetadata() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val answer = pendingIntent(context, 1, "test.misleading.HANG_UP")
        val decline = pendingIntent(context, 2, "test.misleading.ANSWER")
        val hangUp = pendingIntent(context, 3, "test.misleading.DECLINE")
        val caller = Person.Builder()
            .setName(PRIVATE_CALLER)
            .setKey("private-person-key")
            .build()
        val notification = Notification.Builder(context, "test")
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setCategory(Notification.CATEGORY_CALL)
            .setContentTitle(PRIVATE_CALLER)
            .setContentText(PRIVATE_NUMBER)
            .setStyle(Notification.CallStyle.forIncomingCall(caller, decline, answer))
            .addExtras(android.os.Bundle().apply {
                putParcelable(Notification.EXTRA_HANG_UP_INTENT, hangUp)
                putString("phone_number", PRIVATE_NUMBER)
            })
            .build()
        val sbn = StatusBarNotification(
            DIALER_PACKAGE,
            DIALER_PACKAGE,
            7,
            "incoming-call",
            Process.myUid(),
            Process.myPid(),
            0,
            notification,
            Process.myUserHandle(),
            2_000L,
        )

        val captured = CallCapabilityCollector.capture(sbn)

        assertEquals(sbn.key, captured.sourceKey)
        assertEquals(DIALER_PACKAGE, captured.packageName)
        assertEquals(Notification.CATEGORY_CALL, captured.category)
        assertEquals(answer, captured.answer)
        assertEquals(decline, captured.decline)
        assertEquals(hangUp, captured.hangUp)
        val capturedFields = captured.javaClass.declaredFields
            .filterNot { it.isSynthetic }
            .map { it.name }
            .toSet()
        assertEquals(
            setOf("sourceKey", "packageName", "category", "answer", "decline", "hangUp"),
            capturedFields,
        )
        assertFalse(captured.toString().contains(PRIVATE_CALLER))
        assertFalse(captured.toString().contains(PRIVATE_NUMBER))
    }

    private fun pendingIntent(context: Context, requestCode: Int, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private companion object {
        const val DIALER_PACKAGE = "com.example.defaultdialer"
        const val PRIVATE_CALLER = "Private Caller"
        const val PRIVATE_NUMBER = "+15551234567"
    }
}
