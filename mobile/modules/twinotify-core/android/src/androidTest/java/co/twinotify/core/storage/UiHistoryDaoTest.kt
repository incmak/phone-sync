package co.twinotify.core.storage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

class UiHistoryDaoTest {
    private lateinit var db: NotificationDbImpl
    private lateinit var dao: ReliableDeliveryDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, NotificationDbImpl::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.reliableDeliveryDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun disablingContentAtomicallyDeletesSidecarButPreservesMetadata() = runBlocking {
        dao.upsertUiActivity(event("one", "example.messages", 1_000))
        assertTrue(dao.retainUiHistoryContent(content("one", 1_000, 32)))

        dao.setUiHistoryContentEnabled(false)

        assertFalse(dao.uiHistoryPolicy()!!.contentEnabled)
        val row = dao.uiHistoryRows(10).single()
        assertNull(row.contentCiphertext)
        assertEquals("one", row.eventId)
        assertFalse(dao.retainUiHistoryContent(content("one", 2_000, 32)))
    }

    @Test
    fun clearAppAndClearAllDoNotTouchDesiredStateOrReceipts() = runBlocking {
        dao.upsertUiActivity(event("one", "example.messages", 1_000))
        dao.upsertUiActivity(event("two", "example.mail", 2_000))
        dao.retainUiHistoryContent(content("one", 1_000, 32))
        dao.retainUiHistoryContent(content("two", 2_000, 32))
        dao.putCanonical(canonical())
        dao.insertOutbound(receipt())

        dao.clearUiHistoryForPackage("example.messages")

        assertEquals(listOf("two"), dao.uiHistoryRows(10).map { it.eventId })
        assertNotNull(dao.canonical("canon"))
        assertNotNull(dao.outboundMessage("receipt"))

        dao.clearUiHistory()

        assertTrue(dao.uiHistoryRows(10).isEmpty())
        assertNotNull(dao.canonical("canon"))
        assertNotNull(dao.outboundMessage("receipt"))
        Unit
    }

    @Test
    fun configuredAgeAndEncryptedByteBoundsAreEnforced() = runBlocking {
        dao.upsertUiActivity(event("old", "example.messages", 1_000))
        dao.upsertUiActivity(event("new-one", "example.messages", EIGHT_DAYS))
        dao.upsertUiActivity(event("new-two", "example.messages", EIGHT_DAYS + 1))
        dao.retainUiHistoryContent(content("old", 1_000, 16))
        dao.retainUiHistoryContent(content("new-one", EIGHT_DAYS, ONE_MIB + 10))
        dao.retainUiHistoryContent(content("new-two", EIGHT_DAYS + 1, ONE_MIB + 10))

        dao.setUiHistoryRetentionDays(7, EIGHT_DAYS + 2)

        val rows = dao.uiHistoryRows(10)
        assertEquals(listOf("new-two", "new-one"), rows.map { it.eventId })
        assertNull(rows.single { it.eventId == "new-one" }.contentCiphertext)
        assertNotNull(rows.single { it.eventId == "new-two" }.contentCiphertext)
        Unit
    }

    private fun event(id: String, packageName: String, at: Long) = UiActivityEvent(
        eventId = id,
        msgId = "message-$id",
        packageName = packageName,
        appName = packageName.substringAfterLast('.'),
        direction = "RECEIVED",
        kind = "NOTIFICATION",
        status = "APPLIED",
        route = "LAN",
        occurredAt = at,
    )

    private fun content(id: String, at: Long, bytes: Int) = UiActivityContent(
        eventId = id,
        ciphertext = ByteArray(bytes),
        iv = ByteArray(12),
        byteSize = bytes.toLong() + 12,
        createdAt = at,
    )

    private fun canonical() = CanonicalNotificationState(
        canonId = "canon",
        originDevice = "peer",
        latestSequence = 1,
        state = "ACTIVE",
        desiredPayloadJson = "{}",
        materializedSequence = 1,
        sourceNotificationKey = null,
        mirrorLocalId = 7,
        mirrorLocalTag = "tag",
        peerCancelPending = false,
        updatedAt = 1_000,
    )

    private fun receipt() = OutboundMessage(
        msgId = "receipt",
        canonId = null,
        sequence = null,
        eventType = "peer.receipt",
        protocolVersion = 2,
        envelopeJson = "{}",
        envelopeSha256 = "digest",
        byteSize = 2,
        createdAt = 1_000,
        expiresAt = 2_000,
        custodyAcceptedAt = null,
        custodyRoute = null,
        attempts = 0,
        nextAttemptAt = 1_000,
        state = "NEW",
        lastError = null,
        requiresPeerReceipt = false,
    )

    private companion object {
        const val ONE_MIB = 1024 * 1024
        const val EIGHT_DAYS = 8L * 24L * 60L * 60L * 1_000L
    }
}
