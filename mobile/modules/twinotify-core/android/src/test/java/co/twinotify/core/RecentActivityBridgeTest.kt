package co.twinotify.core

import co.twinotify.core.storage.UiActivityEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RecentActivityBridgeTest {
    @Test
    fun bridgeClampsReadsAndOmitsInternalIdentifiers() {
        assertEquals(20, recentActivityLimit(200))
        assertEquals(1, recentActivityLimit(0))

        val public = UiActivityEvent(
            eventId = "internal-event",
            msgId = "internal-message",
            packageName = "example.messages",
            appName = "Messages",
            direction = "RECEIVED",
            kind = "NOTIFICATION",
            status = "APPLIED",
            route = "LAN",
            occurredAt = 2_000,
        ).toRecentActivityMap()

        assertEquals("Messages", public["appName"])
        assertFalse(public.containsKey("eventId"))
        assertFalse(public.containsKey("msgId"))
        assertFalse(public.containsKey("packageName"))
    }
}
