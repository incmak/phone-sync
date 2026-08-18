package co.twinotify.core.pairing.lan

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import co.twinotify.core.storage.PeerStore
import java.net.InetAddress
import java.net.Socket
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertFailsWith

@RunWith(AndroidJUnit4::class)
class OfflinePairingLoopbackTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun cleanIdentity() {
        LanIdentityStore.delete()
    }

    @Test
    fun androidNsdAdapterRegistersAndUnregistersOnSuppliedWifiNetwork() = runBlocking {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val wifiNetwork = connectivity.allNetworks.first { network ->
            connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        val adapter = AndroidPairingNsdAdapter(context, wifiNetwork)
        val sessionId = UUID.randomUUID().toString()
        val advertisement = adapter.register(sessionId, 4455)

        try {
            assertTrue(advertisement.listener is android.net.nsd.NsdManager.RegistrationListener)
        } finally {
            withTimeout(5_000) { adapter.unregister(advertisement) }
            adapter.stopDiscovery()
        }
    }

    @Test
    fun realJssePinnedLoopbackExchangesBoundedFrameWithoutPersistingTrust() {
        runBlocking {
            val identity = LanIdentityStore.loadOrCreate()
            val before = PeerStore.load(context)?.snapshot()
            val server = JssePairingTlsServer.open(LanTlsContextFactory.serverContext())
            val sessionId = UUID.randomUUID().toString()
            val signature = ByteArray(64) { it.toByte() }

            try {
                withTimeout(10_000) {
                    val serverJob = async(Dispatchers.IO) {
                        server.accept().use { connection ->
                            val received = connection.read() as OfflinePairingFrame.Signature
                            connection.write(received)
                        }
                    }
                    val raw = Socket(InetAddress.getLoopbackAddress(), server.localPort)
                    JssePairingTlsClient().handshake(
                        raw,
                        InetAddress.getLoopbackAddress().hostAddress!!,
                        server.localPort,
                        identity.spkiSha256,
                    ).use { connection ->
                        connection.write(OfflinePairingFrame.Signature(sessionId, signature))
                        val echoed = connection.read() as OfflinePairingFrame.Signature
                        assertEquals(sessionId, echoed.sessionId)
                        assertArrayEquals(signature, echoed.signature)
                        assertArrayEquals(identity.spkiSha256, requireNotNull(connection.peerSpkiSha256))
                    }
                    serverJob.await()
                }
            } finally {
                server.close()
            }

            val after = PeerStore.load(context)?.snapshot()
            if (before == null) assertNull(after) else assertEquals(before, after)
        }
    }

    @Test
    fun realJsseWrongPinRejectsHandshake() {
        runBlocking {
            val identity = LanIdentityStore.loadOrCreate()
            val wrongPin = identity.spkiSha256.also { it[0] = (it[0].toInt() xor 1).toByte() }
            val server = JssePairingTlsServer.open(LanTlsContextFactory.serverContext())

            try {
                withTimeout(10_000) {
                    val serverJob = async(Dispatchers.IO) { runCatching { server.accept().close() } }
                    val raw = Socket(InetAddress.getLoopbackAddress(), server.localPort)
                    val failure = assertFailsWith<PairingTransportException> {
                        JssePairingTlsClient().handshake(
                            raw,
                            InetAddress.getLoopbackAddress().hostAddress!!,
                            server.localPort,
                            wrongPin,
                        )
                    }
                    assertEquals(PairingTransportFailure.TLS_PIN_MISMATCH, failure.failure)
                    serverJob.await()
                }
            } finally {
                server.close()
            }
        }
    }

    private fun co.twinotify.core.storage.PeerRecord.snapshot(): PeerSnapshot = PeerSnapshot(
        deviceId = deviceId,
        encryption = encPubkey.toList(),
        signing = signPubkey.toList(),
        displayName = displayName,
        lanBindingId = lanBindingId,
    )

    private data class PeerSnapshot(
        val deviceId: String,
        val encryption: List<Byte>,
        val signing: List<Byte>,
        val displayName: String?,
        val lanBindingId: String?,
    )
}
