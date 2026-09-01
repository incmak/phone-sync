package co.twinotify.core.history

import androidx.test.core.app.ApplicationProvider
import co.twinotify.core.storage.NotificationDb
import co.twinotify.core.storage.UiActivityEvent
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test

class HistoryRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db by lazy { NotificationDb.get(context) }
    private val dao by lazy { db.reliableDeliveryDao() }

    @Before
    fun setUp() = runBlocking {
        dao.setUiHistoryContentEnabled(true)
        dao.setUiHistoryRetentionDays(30, NOW)
        dao.clearUiHistory()
    }

    @After
    fun tearDown() = runBlocking {
        dao.clearUiHistory()
    }

    @Test
    fun contentIsCiphertextAtRestAndDecryptsForPresentation() = runBlocking {
        dao.upsertUiActivity(
            UiActivityEvent(
                eventId = EVENT_ID,
                msgId = "message",
                packageName = "example.messages",
                appName = "Messages",
                direction = "RECEIVED",
                kind = "NOTIFICATION",
                status = "APPLIED",
                route = "LAN",
                occurredAt = NOW,
            ),
        )
        val payload = JSONObject().apply {
            put("type", "notif.post")
            put("canon_id", "canon")
            put("package_name", "example.messages")
            put("id", 7)
            put("title", SECRET_TITLE)
            put("text", SECRET_PREVIEW)
            put("visibility", "private")
            put("is_group_summary", false)
            put("is_ongoing", false)
            put("is_clearable", true)
            put("ts", NOW)
        }.toString()

        assertTrue(HistoryRepository(context).record(EVENT_ID, payload, NOW))

        db.openHelper.readableDatabase.query(
            "SELECT ciphertext, iv FROM ui_activity_content WHERE eventId=?",
            arrayOf(EVENT_ID),
        ).use { row ->
            assertTrue(row.moveToFirst())
            val ciphertext = row.getBlob(0).toString(Charsets.UTF_8)
            assertFalse(ciphertext.contains(SECRET_TITLE))
            assertFalse(ciphertext.contains(SECRET_PREVIEW))
            assertTrue(row.getBlob(1).isNotEmpty())
        }
        val item = HistoryRepository(context).recent(10, NOW).single()
        assertEquals(SECRET_TITLE, item.title)
        assertEquals(SECRET_PREVIEW, item.preview)
    }

    private companion object {
        const val EVENT_ID = "history-event"
        const val NOW = 1_000_000L
        const val SECRET_TITLE = "Alice"
        const val SECRET_PREVIEW = "Dinner at seven"
    }
}
