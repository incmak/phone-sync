package co.twinotify.core.storage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyConversionTransactionTest {
    private lateinit var db: NotificationDbImpl

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NotificationDbImpl::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun missingLegacySourceDoesNotCreateReliableOrphan() = runBlocking {
        val result = db.reliableDeliveryDao().convertLegacy(99, reliableRow("m1", "sha"))

        assertEquals(LegacyConversionResult.AlreadyConverted, result)
        db.openHelper.readableDatabase.query(
            "SELECT msgId FROM outbound_message WHERE msgId='m1'",
        ).use { assertNull(if (it.moveToFirst()) it.getString(0) else null) }
    }

    private fun reliableRow(msgId: String, digest: String) = OutboundMessage(
        msgId = msgId,
        canonId = null,
        sequence = null,
        eventType = "enc",
        protocolVersion = 1,
        envelopeJson = "{}",
        envelopeSha256 = digest,
        byteSize = 2,
        createdAt = 1,
        expiresAt = 2,
        relayAcceptedAt = null,
        attempts = 0,
        nextAttemptAt = 1,
        state = "NEW",
        lastError = null,
        requiresPeerReceipt = true,
    )
}
