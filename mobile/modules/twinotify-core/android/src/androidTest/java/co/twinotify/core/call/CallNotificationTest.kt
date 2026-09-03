package co.twinotify.core.call

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import co.twinotify.core.service.NotifChannelSetup
import co.twinotify.core.storage.ActionInvocation
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
    fun nativeCallStyleUsesExactImmutableControlsFallsBackWithoutActionsAndKeepsStableIdentity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val canonId = "call:$SESSION"
        val ringing = reduce(null, event(
            "ringing",
            1,
            controls = listOf(
                CallControlDescriptor(ANSWER, CallControlKind.ANSWER),
                CallControlDescriptor(DECLINE, CallControlKind.DECLINE),
            ),
        ))
        val state = ringing.state
        assertEquals(canonId, state.canonId)
        assertEquals(CallStateMaterializer.stableTag(canonId), state.mirrorLocalTag)
        assertEquals(7341, state.mirrorLocalId)

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // The foreground service posts as the test package itself, so the runtime permission
        // must be granted to the package, not only adopted for the instrumentation thread.
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.POST_NOTIFICATIONS)
        try {
            NotifChannelSetup.ensureChannels(context)
            val notification = CallStateMaterializer.build(
                context,
                state,
                state.mirrorLocalId!!,
                invocations = emptyList(),
                peerName = "Pixel 9",
            )

            assertEquals(NotifChannelSetup.CHANNEL_CALLS, notification.channelId)
            assertEquals(
                NotificationManager.IMPORTANCE_HIGH,
                manager.getNotificationChannel(NotifChannelSetup.CHANNEL_CALLS)?.importance,
            )
            assertEquals(
                "Incoming call state and controls from your paired phone. Caller identity and audio are not shared.",
                manager.getNotificationChannel(NotifChannelSetup.CHANNEL_CALLS)?.description,
            )
            assertEquals(Notification.CATEGORY_CALL, notification.category)
            val answer = notification.callIntent(Notification.EXTRA_ANSWER_INTENT)
            val decline = notification.callIntent(Notification.EXTRA_DECLINE_INTENT)
            assertNotNull(answer, "incoming call must expose the exact answer intent")
            assertNotNull(decline, "incoming call must expose the exact decline intent")
            assertTrue(answer.isImmutable, "answer intent must be immutable")
            assertTrue(decline.isImmutable, "decline intent must be immutable")
            assertTrue(answer != decline, "full control data URIs must keep PendingIntent identities distinct")
            assertEquals(null, notification.callIntent(Notification.EXTRA_HANG_UP_INTENT))
            assertEquals(null, notification.contentIntent)
            assertEquals(null, notification.deleteIntent)
            assertEquals(null, notification.fullScreenIntent)
            @Suppress("DEPRECATION")
            val balOptIn = CallControlInvocationProcessor.callControlSendOptions()
                .get("android.pendingIntent.backgroundActivityAllowed")
            assertTrue(
                balOptIn == true || balOptIn == android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                "control dispatch must opt in to the sender's background-launch privilege (was $balOptIn)",
            )
            val person = notification.extras.getParcelable(Notification.EXTRA_CALL_PERSON, android.app.Person::class.java)
            assertEquals("Call on Pixel 9", person?.name?.toString())
            assertNotNull(person?.icon, "call person avatar must be the app mark, not a letter")

            // The platform refuses CallStyle outside a foreground service or full-screen intent,
            // and Twinotify never requests a full-screen intent.
            manager.cancel(state.mirrorLocalTag, state.mirrorLocalId!!)
            val refused = runCatching { manager.notify(state.mirrorLocalTag, state.mirrorLocalId!!, notification) }
            assertIs<IllegalArgumentException>(refused.exceptionOrNull(), "plain notify must not admit CallStyle")
            assertEquals(null, waitForActive(manager, state.mirrorLocalId!!, expected = false))

            // Production renders every call mirror through the service's foreground slot.
            // A previous run's host may still be winding down; start from a clean slot.
            context.stopService(android.content.Intent(context, CallMirrorTestForegroundService::class.java))
            assertEquals(null, waitForActive(manager, CallMirrorTestForegroundService.STATUS_ID, expected = false))
            CallMirrorTestForegroundService.reset()
            val status = CallMirrorTestForegroundService.statusNotification(context)
            context.startForegroundService(
                android.content.Intent(context, CallMirrorTestForegroundService::class.java),
            )
            val service = assertNotNull(CallMirrorTestForegroundService.await(10_000), "foreground host must start")
            assertEquals(null, CallMirrorTestForegroundService.lastError?.toString())
            assertNotNull(
                waitForActive(manager, CallMirrorTestForegroundService.STATUS_ID, expected = true),
                "status notification must be visible as the foreground notification",
            )
            val slot = service.slot

            assertTrue(slot.hold(state.mirrorLocalId!!, notification), "call mirror must take the foreground slot")
            val posted = assertNotNull(
                waitForActive(manager, state.mirrorLocalId!!, expected = true),
                "call mirror should be posted under its stable id through the foreground slot",
            )
            assertEquals(null, posted.tag)
            assertTrue(posted.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
            assertNotNull(posted.notification.callIntent(Notification.EXTRA_ANSWER_INTENT))
            assertEquals(
                null,
                waitForActive(manager, CallMirrorTestForegroundService.STATUS_ID, expected = false),
                "status notification yields the slot while a call mirror holds it",
            )

            val active = reduce(
                state,
                event(
                    "active",
                    2,
                    controls = listOf(CallControlDescriptor(HANG_UP, CallControlKind.HANG_UP)),
                ),
            )
            val ongoing = CallStateMaterializer.build(
                context,
                active.state,
                active.state.mirrorLocalId!!,
                invocations = emptyList(),
                peerName = "Pixel 9",
            )
            val hangUp = ongoing.callIntent(Notification.EXTRA_HANG_UP_INTENT)
            assertNotNull(hangUp, "ongoing incoming call must expose hang-up")
            assertTrue(hangUp.isImmutable, "hang-up intent must be immutable")
            assertEquals(null, ongoing.callIntent(Notification.EXTRA_ANSWER_INTENT))
            assertEquals(null, ongoing.callIntent(Notification.EXTRA_DECLINE_INTENT))

            val attempted = CallStateMaterializer.build(
                context,
                state,
                state.mirrorLocalId!!,
                invocations = listOf(invocation(ANSWER, "PENDING")),
                peerName = "Pixel 9",
            )
            assertNoCallControls(attempted)

            val partial = reduce(
                null,
                event(
                    "ringing",
                    1,
                    controls = listOf(CallControlDescriptor(ANSWER, CallControlKind.ANSWER)),
                ),
            )
            assertNoCallControls(
                CallStateMaterializer.build(
                    context,
                    partial.state,
                    partial.state.mirrorLocalId!!,
                    invocations = emptyList(),
                    peerName = "Pixel 9",
                ),
            )

            // An attempt updates the same foreground identity without Android actions.
            assertTrue(slot.hold(state.mirrorLocalId!!, attempted))
            val attemptedPosted = assertNotNull(
                waitForActive(manager, state.mirrorLocalId!!, expected = true) { sbn ->
                    sbn.notification.callIntent(Notification.EXTRA_ANSWER_INTENT) == null
                },
                "attempted update must replace the controllable mirror in place",
            )
            assertNoCallControls(attemptedPosted.notification)
            assertEquals(state.mirrorLocalId, slot.heldBy())

            val idle = reduce(active.state, event("idle", 3))
            assertEquals("CANCELLED", idle.state.state)
            assertEquals(state.mirrorLocalTag, idle.state.mirrorLocalTag)
            assertEquals(state.mirrorLocalId, idle.state.mirrorLocalId)
            // A stale app-side cancel cannot remove a foreground notification; only the slot can.
            manager.cancel(idle.state.mirrorLocalId!!)
            assertNotNull(waitForActive(manager, idle.state.mirrorLocalId!!, expected = true))
            assertTrue(slot.release(idle.state.mirrorLocalId!!) { status })
            assertFalse(
                waitForActive(manager, idle.state.mirrorLocalId!!, expected = false) != null,
                "idle release must remove the stable call mirror",
            )
            assertNotNull(
                waitForActive(manager, CallMirrorTestForegroundService.STATUS_ID, expected = true),
                "status notification returns to the slot after the call",
            )
        } finally {
            context.stopService(android.content.Intent(context, CallMirrorTestForegroundService::class.java))
            manager.cancel(state.mirrorLocalTag, state.mirrorLocalId!!)
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    private fun reduce(
        current: co.twinotify.core.storage.CanonicalNotificationState?,
        next: CallStateEvent,
    ) = assertIs<CallReduction.Apply>(
        CallStateReducer.reduceInbound(
            current = current,
            originDevice = "dev-peer",
            event = next,
            localDeviceId = "dev-local",
            allocator = co.twinotify.core.service.LocalIdAllocator { 7341 },
        ),
    )

    private fun event(
        state: String,
        sequence: Long,
        controls: List<CallControlDescriptor> = emptyList(),
    ) = CallStateEvent(
        callSessionId = SESSION,
        state = state,
        direction = CallDirection.INCOMING,
        sequence = sequence,
        controls = controls,
    )

    private fun invocation(controlId: String, state: String) = ActionInvocation(
        invocationId = controlId,
        canonId = "call:$SESSION",
        actionId = CallControlKind.ANSWER.wire,
        notificationSequence = 1L,
        replyText = null,
        state = state,
        createdAt = 1L,
        expiresAt = 16L,
        updatedAt = 2L,
    )

    private fun Notification.callIntent(key: String): PendingIntent? =
        extras.getParcelable(key, PendingIntent::class.java)

    private fun assertNoCallControls(notification: Notification) {
        assertEquals(null, notification.callIntent(Notification.EXTRA_ANSWER_INTENT))
        assertEquals(null, notification.callIntent(Notification.EXTRA_DECLINE_INTENT))
        assertEquals(null, notification.callIntent(Notification.EXTRA_HANG_UP_INTENT))
        assertTrue(notification.actions?.isEmpty() != false, "fallback must contain no fake Android actions")
    }

    private fun waitForActive(
        manager: NotificationManager,
        id: Int,
        expected: Boolean,
        matches: (StatusBarNotification) -> Boolean = { true },
    ): StatusBarNotification? {
        fun current() = manager.activeNotifications.firstOrNull { it.tag == null && it.id == id && matches(it) }
        repeat(200) {
            val active = current()
            if ((active != null) == expected) return active
            SystemClock.sleep(50)
        }
        return current()
    }

    private companion object {
        const val SESSION = "11111111-1111-4111-8111-111111111111"
        const val ANSWER = "22222222-2222-4222-8222-222222222222"
        const val DECLINE = "33333333-3333-4333-8333-333333333333"
        const val HANG_UP = "44444444-4444-4444-8444-444444444444"
    }
}
