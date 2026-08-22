package co.twinotify.core.lan

import android.os.Bundle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import co.twinotify.core.crypto.WrappedKeys
import co.twinotify.core.pairing.lan.LanIdentityStore
import co.twinotify.core.pairing.lan.LanTlsContextFactory
import co.twinotify.core.service.DaoOutboxStore
import co.twinotify.core.service.InboundDispatchResult
import co.twinotify.core.service.OutboxRepository
import co.twinotify.core.storage.NotificationDbImpl
import co.twinotify.core.storage.OutboundMessage
import co.twinotify.core.storage.ReliableDeliveryDao
import java.net.InetAddress
import java.security.MessageDigest
import javax.net.ssl.SSLServerSocket
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Direct delivery over two real pinned TLS sockets on this device.
 *
 * Both ends run the production listener, dialer, connector and transport. It is
 * a loopback rather than two handsets, but nothing here is faked below the
 * transport: a real envelope crosses a real authenticated socket and its
 * acceptance is written to a real Room database.
 */
@RunWith(AndroidJUnit4::class)
class DirectLanDeliveryTest {
    private var db: NotificationDbImpl? = null

    @After
    fun tearDown() {
        db?.close()
        LanIdentityStore.delete()
    }

    @Test
    fun anEnvelopeCrossesTwoAuthenticatedSocketsAndIsAcceptedDurably(): Unit = runBlocking {
        val identity = LanIdentityStore.loadOrCreate()
        val initiatorKeys = WrappedKeys.generateSign()
        val acceptorKeys = WrappedKeys.generateSign()
        val dao = openDao()
        dao.insertOutbound(row())

        val server = withContext(Dispatchers.IO) {
            (
                LanTlsContextFactory.serverContext(identity.spkiSha256)
                    .serverSocketFactory
                    .createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket
                ).apply { needClientAuth = true }
        }

        try {
            // The acceptor takes custody of whatever the initiator delivers.
            val delivered = CompletableDeferred<String>()
            val acceptorConnection = async(Dispatchers.IO) {
                connector(
                    server = server,
                    pin = identity.spkiSha256,
                    local = DEVICE_B,
                    peer = DEVICE_A,
                    secret = acceptorKeys.secretKey,
                    peerPublic = initiatorKeys.publicKey,
                    role = LanConnectionRole.ACCEPTOR,
                ).connect()
            }
            val initiatorConnection = async(Dispatchers.IO) {
                connector(
                    server = null,
                    pin = identity.spkiSha256,
                    local = DEVICE_A,
                    peer = DEVICE_B,
                    secret = initiatorKeys.secretKey,
                    peerPublic = acceptorKeys.publicKey,
                    role = LanConnectionRole.INITIATOR,
                    port = server.localPort,
                ).connect()
            }

            val acceptor = LanTransport(
                connection = acceptorConnection.await(),
                outbox = OutboxRepository(DaoOutboxStore(dao)),
                dispatch = { envelope ->
                    delivered.complete(envelope)
                    InboundDispatchResult.Accepted(MSG_ID, DIGEST)
                },
            )
            val initiator = LanTransport(
                connection = initiatorConnection.await(),
                outbox = OutboxRepository(DaoOutboxStore(dao)),
                dispatch = { InboundDispatchResult.Rejected("unexpected_inbound") },
            )

            val acceptorEvents = launch(Dispatchers.IO) { acceptor.run().collect { } }
            val initiatorAccepted = CompletableDeferred<String>()
            val initiatorEvents = launch(Dispatchers.IO) {
                initiator.run().collect { event ->
                    if (event is LanTransportEvent.PeerAccepted) initiatorAccepted.complete(event.msgId)
                }
            }

            initiator.send(dao.outboundMessage(MSG_ID)!!)

            val envelope = withTimeout(15_000) { delivered.await() }
            val acceptedMsgId = withTimeout(15_000) { initiatorAccepted.await() }

            assertEquals(ENVELOPE, envelope)
            assertEquals(MSG_ID, acceptedMsgId)
            val stored = assertNotNull(dao.outboundMessage(MSG_ID), "the row must survive acceptance")
            assertEquals("LAN", stored.custodyRoute)
            assertNotNull(stored.custodyAcceptedAt)

            InstrumentationRegistry.getInstrumentation().sendStatus(
                0,
                Bundle().apply {
                    putString("state_code", "LAN_DIRECT_DELIVERY_ACCEPTED")
                    putString("route", "lan")
                    putString(
                        "envelope_sha256",
                        MessageDigest.getInstance("SHA-256")
                            .digest(envelope.encodeToByteArray())
                            .joinToString("") { "%02x".format(it) },
                    )
                },
            )
            assertTrue(acceptorEvents.isActive || acceptorEvents.isCompleted)

            acceptorEvents.cancel()
            initiatorEvents.cancel()
        } finally {
            withContext(Dispatchers.IO) { server.close() }
        }
    }

    private fun openDao(): ReliableDeliveryDao {
        val created = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NotificationDbImpl::class.java,
        ).allowMainThreadQueries().build()
        db = created
        return created.reliableDeliveryDao()
    }

    private fun connector(
        server: SSLServerSocket?,
        pin: ByteArray,
        local: String,
        peer: String,
        secret: ByteArray,
        peerPublic: ByteArray,
        role: LanConnectionRole,
        port: Int = 0,
    ): DirectLanConnector {
        val handshake = {
            SignedLanSocketHandshake(
                LanHandshake(
                    localDeviceId = local,
                    peerDeviceId = peer,
                    localSigningKey = secret,
                    peerSigningKey = peerPublic,
                    localRole = role,
                    protocolFloor = LanFrame.VERSION,
                ),
            )
        }
        val candidates: Flow<LanCandidate> =
            if (server == null) {
                flowOf(
                    LanCandidate(
                        address = InetAddress.getLoopbackAddress(),
                        port = port,
                        network = { java.net.Socket() },
                    ),
                )
            } else {
                kotlinx.coroutines.flow.emptyFlow()
            }
        return DirectLanConnector(
            discovery = object : LanDiscovery {
                override fun candidates(): Flow<LanCandidate> = candidates
                override suspend fun close() = Unit
            },
            listener = if (server != null) {
                JsseLanListener(server, pin, handshake)
            } else {
                object : LanListener {
                    override suspend fun accept(): AuthenticatedLanConnection =
                        kotlinx.coroutines.awaitCancellation()
                    override fun close() = Unit
                }
            },
            dialer = JsseLanDialer(LanTlsContextFactory.clientContext(pin), pin, handshake),
            arbitrationGraceMillis = 200,
            connectTimeoutMillis = 15_000,
        )
    }

    private fun row() = OutboundMessage(
        msgId = MSG_ID,
        canonId = null,
        sequence = null,
        eventType = "notif.post",
        protocolVersion = 2,
        envelopeJson = ENVELOPE,
        envelopeSha256 = DIGEST,
        byteSize = ENVELOPE.length.toLong(),
        createdAt = 0,
        expiresAt = Long.MAX_VALUE,
        custodyAcceptedAt = null,
        custodyRoute = null,
        attempts = 0,
        nextAttemptAt = 0,
        state = "NEW",
        lastError = null,
        requiresPeerReceipt = true,
    )

    private companion object {
        const val DEVICE_A = "dev-00000000-0000-0000-0000-000000000001"
        const val DEVICE_B = "dev-00000000-0000-0000-0000-000000000002"
        const val MSG_ID = "66666666-6666-4666-8666-666666666666"
        const val DIGEST = "dd11bb22cc33dd44ee55ff6600778899aabbccddeeff00112233445566778899"
        const val ENVELOPE = "{\"v\":2,\"type\":\"enc\",\"body\":\"direct-lan\"}"
    }
}
