package co.twinotify.core.call

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import co.twinotify.core.service.NotifChannelSetup
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CallNotificationTest {
    @Test
    fun callMirrorUsesStableIdentityAndCancelsWithoutActions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val canonId = "call:$SESSION"
        val ringing = assertIs<CallReduction.Apply>(
            CallStateReducer.reduceInbound(
                current = null,
                originDevice = "dev-peer",
                event = event("ringing", 1),
                localDeviceId = "dev-local",
                allocator = co.twinotify.core.service.LocalIdAllocator { 7341 },
            ),
        )
        val state = ringing.state
        assertEquals(canonId, state.canonId)
        assertEquals(CallStateMaterializer.stableTag(canonId), state.mirrorLocalTag)
        assertEquals(7341, state.mirrorLocalId)

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.POST_NOTIFICATIONS)
        try {
            NotifChannelSetup.ensureChannels(context)
            val notification = CallStateMaterializer.build(context, state, state.mirrorLocalId!!)

            assertEquals(Notification.CATEGORY_CALL, notification.category)
            assertTrue(notification.actions?.isEmpty() != false, "call mirror must not expose controls")
            assertEquals(null, notification.contentIntent)
            assertEquals(null, notification.deleteIntent)
            assertEquals(null, notification.fullScreenIntent)

            manager.cancel(state.mirrorLocalTag, state.mirrorLocalId!!)
            manager.notify(state.mirrorLocalTag, state.mirrorLocalId!!, notification)
            assertNotNull(
                waitForActive(manager, state.mirrorLocalTag!!, state.mirrorLocalId!!, expected = true),
                "call mirror should be posted under its stable tag/id",
            )

            val idle = assertIs<CallReduction.Apply>(
                CallStateReducer.reduceInbound(
                    current = state,
                    originDevice = "dev-peer",
                    event = event("idle", 2),
                    localDeviceId = "dev-local",
                    allocator = co.twinotify.core.service.LocalIdAllocator { 9999 },
                ),
            )
            assertEquals("CANCELLED", idle.state.state)
            assertEquals(state.mirrorLocalTag, idle.state.mirrorLocalTag)
            assertEquals(state.mirrorLocalId, idle.state.mirrorLocalId)
            manager.cancel(idle.state.mirrorLocalTag, idle.state.mirrorLocalId!!)
            assertFalse(
                waitForActive(manager, idle.state.mirrorLocalTag!!, idle.state.mirrorLocalId!!, expected = false) != null,
                "idle cancellation must remove the stable call mirror",
            )
        } finally {
            manager.cancel(state.mirrorLocalTag, state.mirrorLocalId!!)
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    private fun event(state: String, sequence: Long) = CallStateEvent(
        callSessionId = SESSION,
        state = state,
        direction = CallDirection.INCOMING,
        sequence = sequence,
    )

    private fun waitForActive(
        manager: NotificationManager,
        tag: String,
        id: Int,
        expected: Boolean,
    ): StatusBarNotification? {
        repeat(40) {
            val active = manager.activeNotifications.firstOrNull { it.tag == tag && it.id == id }
            if ((active != null) == expected) return active
            SystemClock.sleep(50)
        }
        return manager.activeNotifications.firstOrNull { it.tag == tag && it.id == id }
    }

    private companion object {
        const val SESSION = "11111111-1111-4111-8111-111111111111"
    }
}
