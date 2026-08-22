package co.twinotify.core.lan

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import co.twinotify.core.service.DaoOutboxStore
import co.twinotify.core.service.InboundDispatchResult
import co.twinotify.core.service.OutboxRepository
import co.twinotify.core.storage.NotificationDbImpl
import co.twinotify.core.storage.OutboundMessage
import co.twinotify.core.storage.ReliableDeliveryDao
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Direct-route custody against a real Room database. This proves the durable
 * transitions a fake store cannot: that acceptance is committed and route-tagged,
 * that an ordinary row survives acceptance until its peer receipt, and that a
 * receipt row is removed so receipts cannot recurse.
 */
@RunWith(AndroidJUnit4::class)
class LanCustodyTransactionTest {
    private lateinit var db: NotificationDbImpl
    private lateinit var dao: ReliableDeliveryDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NotificationDbImpl::class.java,
        ).allowMainThreadQueries().build()
        dao = db.reliableDeliveryDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun directAcceptanceCommitsCustodyAndTagsTheLanRoute(): Unit = runBlocking {
        dao.insertOutbound(row(MSG_A, requiresPeerReceipt = true))
        val connection = FakeConnection()
        val transport = transport(connection)
        val events = async { transport.run().toList() }

        transport.send(dao.outboundMessage(MSG_A)!!)
        connection.deliver(LanFrame.Accepted(MSG_A, DIGEST_A))
        connection.closeSession()
        events.await()

        val stored = assertNotNull(dao.outboundMessage(MSG_A), "an ordinary row must survive acceptance")
        assertEquals("LAN", stored.custodyRoute)
        assertNotNull(stored.custodyAcceptedAt)
    }

    @Test
    fun ordinaryRowIsReleasedOnlyByItsAuthenticatedPeerReceipt(): Unit = runBlocking {
        dao.insertOutbound(row(MSG_A, requiresPeerReceipt = true))
        val outbox = OutboxRepository(DaoOutboxStore(dao))

        outbox.onLanAccepted(MSG_A)
        assertNotNull(dao.outboundMessage(MSG_A), "acceptance alone must not release the row")

        outbox.onPeerReceipt(MSG_A, DIGEST_A, status = "applied")
        assertNull(dao.outboundMessage(MSG_A), "the peer receipt must release the row")
    }

    @Test
    fun receiptRowIsDeletedByDirectAcceptanceSoReceiptsCannotRecurse(): Unit = runBlocking {
        dao.insertOutbound(row(MSG_B, requiresPeerReceipt = false, eventType = "peer.receipt"))

        OutboxRepository(DaoOutboxStore(dao)).onLanAccepted(MSG_B)

        assertNull(dao.outboundMessage(MSG_B), "a receipt row must not survive its own acceptance")
    }

    @Test
    fun acknowledgementForAnUnknownRowChangesNoDurableState(): Unit = runBlocking {
        dao.insertOutbound(row(MSG_A, requiresPeerReceipt = true))
        val connection = FakeConnection()
        val transport = transport(connection)
        val events = async { transport.run().toList() }

        connection.deliver(LanFrame.Accepted(MSG_B, DIGEST_A))
        connection.closeSession()
        events.await()

        val stored = assertNotNull(dao.outboundMessage(MSG_A))
        assertNull(stored.custodyAcceptedAt, "an unrelated acknowledgement took custody")
    }

    @Test
    fun resendAfterCommitTakesCustodyExactlyOnce(): Unit = runBlocking {
        dao.insertOutbound(row(MSG_A, requiresPeerReceipt = true))
        val outbox = OutboxRepository(DaoOutboxStore(dao))

        outbox.onLanAccepted(MSG_A, acceptedAt = 5_000L)
        val firstAcceptedAt = dao.outboundMessage(MSG_A)!!.custodyAcceptedAt
        outbox.onLanAccepted(MSG_A, acceptedAt = 9_000L)

        // Asserting the value is present matters: two nulls would compare equal and
        // let this pass without custody ever being taken.
        assertEquals(5_000L, firstAcceptedAt)
        assertEquals(firstAcceptedAt, dao.outboundMessage(MSG_A)!!.custodyAcceptedAt)
    }

    @Test
    fun inboundIsAcknowledgedOnlyAfterItsCommitReturns(): Unit = runBlocking {
        val committed = CompletableDeferred<Unit>()
        val connection = FakeConnection()
        val transport = LanTransport(
            connection = connection,
            outbox = OutboxRepository(DaoOutboxStore(dao)),
            dispatch = {
                committed.await()
                InboundDispatchResult.Accepted(MSG_A, DIGEST_A)
            },
        )
        val events = async { transport.run().toList() }

        connection.deliver(LanFrame.Put("{\"v\":2}".encodeToByteArray()))
        assertTrue(connection.written.isEmpty(), "acknowledged before the commit returned")

        committed.complete(Unit)
        connection.closeSession()
        events.await()

        assertEquals(LanFrame.Accepted(MSG_A, DIGEST_A), connection.written.single())
    }

    private fun transport(connection: FakeConnection) = LanTransport(
        connection = connection,
        outbox = OutboxRepository(DaoOutboxStore(dao)),
        dispatch = { InboundDispatchResult.Accepted(MSG_A, DIGEST_A) },
    )

    private fun row(
        msgId: String,
        requiresPeerReceipt: Boolean,
        eventType: String = "notif.post",
    ) = OutboundMessage(
        msgId = msgId,
        canonId = null,
        sequence = null,
        eventType = eventType,
        protocolVersion = 2,
        envelopeJson = "{\"v\":2,\"msg\":\"$msgId\"}",
        envelopeSha256 = DIGEST_A,
        byteSize = 32,
        createdAt = 0,
        expiresAt = Long.MAX_VALUE,
        custodyAcceptedAt = null,
        custodyRoute = null,
        attempts = 0,
        nextAttemptAt = 0,
        state = "NEW",
        lastError = null,
        requiresPeerReceipt = requiresPeerReceipt,
    )

    private class FakeConnection : AuthenticatedLanConnection {
        override val session = LanAuthenticatedSession(
            peerDeviceId = "dev-00000000-0000-0000-0000-000000000002",
            initiatorDeviceId = "dev-00000000-0000-0000-0000-000000000001",
            sessionId = ByteArray(32) { it.toByte() },
        )
        private val frames = Channel<LanFrame>(Channel.UNLIMITED)
        val written = mutableListOf<LanFrame>()

        override val incoming: Flow<LanFrame> = frames.consumeAsFlow()
        override suspend fun send(frame: LanFrame) { written += frame }
        suspend fun deliver(frame: LanFrame) = frames.send(frame)
        fun closeSession() = frames.close()
        override fun close() { frames.close() }
    }

    private companion object {
        const val MSG_A = "44444444-4444-4444-8444-444444444444"
        const val MSG_B = "55555555-5555-4555-8555-555555555555"
        const val DIGEST_A = "cc11bb22cc33dd44ee55ff6600778899aabbccddeeff00112233445566778899"
    }
}
