package co.twinotify.core.lan

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import co.twinotify.core.crypto.WrappedKeys
import co.twinotify.core.pairing.lan.LanIdentityStore
import co.twinotify.core.pairing.lan.LanTlsContextFactory
import java.net.InetAddress
import java.security.MessageDigest
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanPinnedTlsTest {
    @After
    fun clearIdentity() {
        LanIdentityStore.delete()
    }

    @Test
    fun pinnedTlsAndSignedHelloAuthenticateOverLoopback() = runBlocking {
        val identity = LanIdentityStore.loadOrCreate()
        val initiatorKeys = WrappedKeys.generateSign()
        val acceptorKeys = WrappedKeys.generateSign()
        val server = (LanTlsContextFactory.serverContext(identity.spkiSha256)
            .serverSocketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket).apply {
            needClientAuth = true
        }
        try {
            val acceptor = async(Dispatchers.IO) {
                val socket = server.accept() as SSLSocket
                factory(
                    socket,
                    identity.spkiSha256,
                    device(2),
                    device(1),
                    acceptorKeys.secretKey,
                    initiatorKeys.publicKey,
                    LanConnectionRole.ACCEPTOR,
                ).connect()
            }
            val initiator = async(Dispatchers.IO) {
                val socket = LanTlsContextFactory.clientContext(identity.spkiSha256).socketFactory.createSocket(
                    InetAddress.getLoopbackAddress(),
                    server.localPort,
                ) as SSLSocket
                factory(
                    socket,
                    identity.spkiSha256,
                    device(1),
                    device(2),
                    initiatorKeys.secretKey,
                    acceptorKeys.publicKey,
                    LanConnectionRole.INITIATOR,
                ).connect()
            }

            val initiatorConnection = initiator.await()
            val acceptorConnection = acceptor.await()
            assertEquals(device(2), initiatorConnection.peerDeviceId)
            assertEquals(device(1), acceptorConnection.peerDeviceId)
            initiatorConnection.close()
            acceptorConnection.close()

            val resultHash = MessageDigest.getInstance("SHA-256").digest(
                "authenticated:${initiatorConnection.peerDeviceId.length}:${acceptorConnection.peerDeviceId.length}".encodeToByteArray(),
            ).toHex()
            InstrumentationRegistry.getInstrumentation().sendStatus(
                0,
                Bundle().apply {
                    putString("state_code", "LAN_HANDSHAKE_AUTHENTICATED")
                    putString("result_sha256", resultHash)
                },
            )
            assertTrue(resultHash.matches(Regex("^[0-9a-f]{64}$")))
        } finally {
            withContext(Dispatchers.IO) { server.close() }
        }
    }

    private fun factory(
        socket: SSLSocket,
        expectedPin: ByteArray,
        localDeviceId: String,
        peerDeviceId: String,
        localSecretKey: ByteArray,
        peerPublicKey: ByteArray,
        role: LanConnectionRole,
    ) = LanConnectionFactory(
        socketProvider = { JsseLanTlsSocket(socket) },
        expectedPeerTlsPin = expectedPin,
        handshake = SignedLanSocketHandshake(
            LanHandshake(
                localDeviceId = localDeviceId,
                peerDeviceId = peerDeviceId,
                localSigningKey = localSecretKey,
                peerSigningKey = peerPublicKey,
                localRole = role,
                protocolFloor = LanFrame.VERSION,
            ),
        ),
    )

    private fun device(value: Int) = "dev-00000000-0000-0000-0000-${value.toString().padStart(12, '0')}"
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
