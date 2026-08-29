package co.twinotify.core.listener

import co.twinotify.core.storage.UiActivityKind
import co.twinotify.core.storage.UiActivityStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class UiActivityCaptureTest {
    @Test
    fun postCaptureKeepsOnlyAppIdentityAndQueuedState() {
        val command = PostCommand(
            canonId = "canonical",
            sourceKey = "source",
            snapshot = SourceNotificationSnapshot(
                sourceKey = "source",
                packageName = "example.messages",
                id = 1,
                tag = null,
                postTime = 100,
                flags = 0,
                category = null,
                visibility = 0,
                isGroupSummary = false,
                isOngoing = false,
                isClearable = true,
                appName = "Messages",
                title = "private title",
                text = "private body",
                subText = null,
                bigText = null,
                smallIcon = null,
                largeIcon = null,
            ),
        )

        val event = outboundUiActivity(command, null, "message", 200)

        assertEquals("Messages", event.appName)
        assertEquals("example.messages", event.packageName)
        assertEquals(UiActivityKind.NOTIFICATION.name, event.kind)
        assertEquals(UiActivityStatus.QUEUED.name, event.status)
    }
}
