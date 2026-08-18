package co.twinotify.core.pairing.lan

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflinePairingRuntimeLoopbackTest {
    @After
    fun clearIdentity() {
        LanIdentityStore.delete()
    }

    @Test
    fun realMutualTlsCapturesNonNullPeerPinsOnBothRolesBeforeFrames() = runBlocking {
        val identity = LanIdentityStore.loadOrCreate()
        val server = JssePairingTlsServer.open(LanTlsContextFactory.serverContext())
        try {
            withTimeout(10_000) {
                val accepted = async(Dispatchers.IO) { server.accept() }
                val client = JssePairingTlsClient().handshake(
                    Socket(InetAddress.getLoopbackAddress(), server.localPort),
                    InetAddress.getLoopbackAddress().hostAddress!!,
                    server.localPort,
                    identity.spkiSha256,
                )
                val serverConnection = accepted.await()
                try {
                    assertNotNull(client.peerSpkiSha256)
                    assertNotNull(serverConnection.peerSpkiSha256)
                    assertArrayEquals(identity.spkiSha256, client.peerSpkiSha256)
                    assertArrayEquals(identity.spkiSha256, serverConnection.peerSpkiSha256)
                } finally {
                    client.close()
                    serverConnection.close()
                }
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun missingClientCertificateIsRejectedDuringHandshake() = runBlocking {
        val identity = LanIdentityStore.loadOrCreate()
        val clientWithoutIdentity = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(LanTlsContextFactory.pinningTrustManager(identity.spkiSha256)), null)
        }
        val server = JssePairingTlsServer.open(LanTlsContextFactory.serverContext())
        try {
            withTimeout(10_000) {
                val accepted = async(Dispatchers.IO) { runCatching { server.accept() } }
                val client = JssePairingTlsClient(contextFactory = { clientWithoutIdentity }).handshake(
                        Socket(InetAddress.getLoopbackAddress(), server.localPort),
                        InetAddress.getLoopbackAddress().hostAddress!!,
                        server.localPort,
                        identity.spkiSha256,
                    )
                try {
                    check(accepted.await().exceptionOrNull() is PairingTransportException)
                } finally {
                    client.close()
                }
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun wrongClientCertificatePinIsRejectedDuringHandshake() = runBlocking {
        val identity = LanIdentityStore.loadOrCreate()
        val wrongClientPin = identity.spkiSha256.also { it[0] = (it[0].toInt() xor 1).toByte() }
        val server = JssePairingTlsServer.open(LanTlsContextFactory.serverContext(wrongClientPin))
        try {
            withTimeout(10_000) {
                val accepted = async(Dispatchers.IO) { runCatching { server.accept() } }
                val client = JssePairingTlsClient().handshake(
                        Socket(InetAddress.getLoopbackAddress(), server.localPort),
                        InetAddress.getLoopbackAddress().hostAddress!!,
                        server.localPort,
                        identity.spkiSha256,
                    )
                try {
                    check(accepted.await().exceptionOrNull() is PairingTransportException)
                } finally {
                    client.close()
                }
            }
        } finally {
            server.close()
        }
    }
}
