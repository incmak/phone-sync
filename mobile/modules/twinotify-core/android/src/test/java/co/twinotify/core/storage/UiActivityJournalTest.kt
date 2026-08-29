package co.twinotify.core.storage

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiActivityJournalTest {
    @Test
    fun eventSurfaceCannotCarryNotificationContent() {
        val fields = UiActivityEvent::class.java.declaredFields.map { it.name }.toSet()

        assertTrue(
            fields.containsAll(
                setOf("eventId", "msgId", "direction", "kind", "status", "occurredAt"),
            ),
        )
        assertTrue(
            fields.intersect(
                setOf("title", "text", "bigText", "canonId", "deviceId", "ciphertext", "nonce"),
            ).isEmpty(),
        )
    }

    @Test
    fun duplicateMessageUpdatesOneRowAndCapStaysAtFiveHundred() = runTest {
        val store = FakeUiActivityStore()
        val journal = UiActivityJournal(store)

        repeat(510) { index ->
            journal.recordApplied(
                msgId = "m-$index",
                packageName = "example.messages",
                appName = "Messages",
                kind = UiActivityKind.NOTIFICATION,
                now = index.toLong(),
            )
        }
        journal.markTerminal("m-509", UiActivityStatus.DELIVERED, "LAN", 999)

        assertEquals(500, store.rows.size)
        assertEquals(1, store.rows.count { it.msgId == "m-509" })
        assertEquals(UiActivityStatus.DELIVERED.name, store.rows.single { it.msgId == "m-509" }.status)
    }

    @Test
    fun recentLimitIsClampedBeforeTheStoreIsRead() = runTest {
        val store = FakeUiActivityStore()
        val journal = UiActivityJournal(store)

        journal.recent(200)

        assertEquals(20, store.lastRecentLimit)
    }
}

private class FakeUiActivityStore : UiActivityStore {
    val rows = mutableListOf<UiActivityEvent>()
    var lastRecentLimit: Int? = null

    override suspend fun upsertUiActivity(row: UiActivityEvent) {
        val matchingIndex = rows.indexOfFirst { existing ->
            existing.eventId == row.eventId || (row.msgId != null && existing.msgId == row.msgId)
        }
        if (matchingIndex >= 0) rows[matchingIndex] = row else rows += row
    }

    override suspend fun uiActivityForMessage(msgId: String): UiActivityEvent? =
        rows.firstOrNull { it.msgId == msgId }

    override suspend fun recentUiActivity(limit: Int): List<UiActivityEvent> {
        lastRecentLimit = limit
        return rows.sortedByDescending { it.occurredAt }.take(limit)
    }

    override suspend fun deleteUiActivityBefore(cutoff: Long): Int {
        val before = rows.size
        rows.removeAll { it.occurredAt < cutoff }
        return before - rows.size
    }

    override suspend fun trimUiActivityToLimit(limit: Int): Int {
        val keep = rows.sortedWith(compareByDescending<UiActivityEvent> { it.occurredAt }.thenByDescending { it.eventId })
            .take(limit)
            .mapTo(mutableSetOf()) { it.eventId }
        val before = rows.size
        rows.removeAll { it.eventId !in keep }
        return before - rows.size
    }
}
