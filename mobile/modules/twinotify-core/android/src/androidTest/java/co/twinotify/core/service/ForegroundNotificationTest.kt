package co.twinotify.core.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ForegroundNotificationTest {
    @Test
    fun launcherIntentIsExplicitSanitizedImmutableAndReusesTheExistingTask() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val component = ComponentName(context, ForegroundNotificationTestActivity::class.java)
        val unsafe = Intent("co.twinotify.SECRET", Uri.parse("wss://relay.example/ws?token=secret"))
            .setComponent(component)
            .putExtra("peer_id", "peer-secret")

        val prepared = ForegroundNotificationFactory.prepareLauncherIntent(unsafe, context.packageName)
        assertEquals(component, prepared.component)
        assertEquals(Intent.ACTION_MAIN, prepared.action)
        assertNull(prepared.data)
        assertNull(prepared.clipData)
        assertTrue(prepared.extras == null || prepared.extras!!.isEmpty)
        assertEquals(setOf(Intent.CATEGORY_LAUNCHER), prepared.categories)
        assertTrue(prepared.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(prepared.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(ForegroundNotificationFactory.pendingIntentFlags and PendingIntent.FLAG_IMMUTABLE != 0)
    }

    @Test
    fun foregroundNotificationIsPrivateOngoingAndOpensOneExistingTask() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ForegroundNotificationTestActivity.lastInstance?.finishAndRemoveTask()
        instrumentation.waitForIdleSync()
        val launchIntent = Intent(context, ForegroundNotificationTestActivity::class.java)
        val presentation = DeliveryStatusPresenter.present(
            SyncRouteStatus(RouteKind.NONE, RoutePhase.RECONNECTING),
            paired = true,
            enabled = true,
        )
        val notification = ForegroundNotificationFactory.build(
            context = context,
            presentation = presentation,
            launchIntentProvider = { launchIntent },
        )

        val manager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as NotificationManager
        instrumentation.uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.POST_NOTIFICATIONS)
        try {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                android.Manifest.permission.POST_NOTIFICATIONS,
            )
            NotifChannelSetup.ensureChannels(context)
            manager.notify(TEST_NOTIFICATION_ID, notification)
            val posted = awaitNotification(manager, TEST_NOTIFICATION_ID)
            assertEquals("Reconnecting", posted.extras.getCharSequence(Notification.EXTRA_TITLE))
            assertEquals(Notification.VISIBILITY_PRIVATE, posted.visibility)
            assertTrue(posted.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertTrue(posted.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
            val contentIntent = assertNotNull(posted.contentIntent)
            assertTrue(contentIntent.isImmutable)

            ForegroundNotificationTestActivity.created = 0
            contentIntent.send()
            instrumentation.waitForIdleSync()
            awaitActivityCreated()
            contentIntent.send()
            instrumentation.waitForIdleSync()
            SystemClock.sleep(100)
            assertEquals(1, ForegroundNotificationTestActivity.created)
            assertFalse(ForegroundNotificationTestActivity.lastIntent?.hasExtra("peer_id") == true)
        } finally {
            manager.cancel(TEST_NOTIFICATION_ID)
            ForegroundNotificationTestActivity.lastInstance?.finishAndRemoveTask()
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    private companion object {
        const val TEST_NOTIFICATION_ID = 0x544E

        fun awaitNotification(manager: NotificationManager, id: Int): Notification {
            repeat(40) {
                manager.activeNotifications.firstOrNull { it.id == id }?.let { return it.notification }
                SystemClock.sleep(50)
            }
            error("foreground notification was not posted")
        }

        fun awaitActivityCreated() {
            repeat(40) {
                if (ForegroundNotificationTestActivity.created > 0) return
                SystemClock.sleep(50)
            }
            error("foreground content intent did not open its activity")
        }
    }
}

class ForegroundNotificationTestActivity : Activity() {
    private val fixtureScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        created += 1
        lastInstance = this
        lastIntent = intent
        // Test-only analogue of TwinotifyCoreModule.OnActivityEntersForeground. It lets the
        // host exercise force-stop -> explicit launch without placing test seams in production.
        if (intent.getBooleanExtra(EXTRA_RECOVER_ON_OPEN, false)) {
            fixtureScope.launch {
                TransportRecoveryAuthority.recover(
                    applicationContext,
                    RecoveryTrigger.APP_FOREGROUND,
                )
            }
        }
    }

    override fun onDestroy() {
        fixtureScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RECOVER_ON_OPEN = "recover_on_open"
        @Volatile var created: Int = 0
        @Volatile var lastInstance: ForegroundNotificationTestActivity? = null
        @Volatile var lastIntent: Intent? = null
    }
}
