package co.twinotify.core.pairing.lan

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import co.twinotify.core.storage.PeerStore
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
        wifiNetworkLease().use { wifi ->
            val adapter = AndroidPairingNsdAdapter(context, wifi.network)
            val sessionId = UUID.randomUUID().toString()
            val advertisement = adapter.register(sessionId, 4455)

            try {
                assertTrue(advertisement.listener is android.net.nsd.NsdManager.RegistrationListener)
            } finally {
                withTimeout(5_000) { adapter.unregister(advertisement) }
                adapter.stopDiscovery()
            }
        }
    }

    @Test
    fun crossDeviceNsdRole() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val role = arguments.getString(ARG_ROLE)
        assumeTrue("cross-device role requires -e $ARG_ROLE advertiser|resolver", role != null)
        require(role == ROLE_ADVERTISER || role == ROLE_RESOLVER) { "invalid_lan_role" }
        val sessionId = requireNotNull(arguments.getString(ARG_SESSION)) { "missing_lan_session" }
        require(runCatching { UUID.fromString(sessionId) }.isSuccess) { "invalid_lan_session" }
        val version = requireNotNull(arguments.getString(ARG_VERSION)) { "missing_lan_version" }
        require(version == PairingNsdContract.VERSION) { "unsupported_lan_version" }
        val durationMillis = requireNotNull(arguments.getString(ARG_DURATION_MS)) { "missing_lan_duration_ms" }
            .toLong()
            .also { require(it in MIN_ROLE_DURATION_MS..MAX_ROLE_DURATION_MS) { "invalid_lan_duration_ms" } }
        val sessionHash = MessageDigest.getInstance("SHA-256")
            .digest(sessionId.encodeToByteArray())
            .take(6)
            .joinToString("") { "%02x".format(it) }

        withTimeout(durationMillis + ROLE_CLEANUP_TIMEOUT_MS) {
            wifiNetworkLease().use { wifi ->
                val adapter = AndroidPairingNsdAdapter(context, wifi.network)
                try {
                    when (role) {
                        ROLE_ADVERTISER -> runAdvertiserRole(adapter, sessionId, sessionHash, version, durationMillis)
                        ROLE_RESOLVER -> runResolverRole(adapter, sessionId, sessionHash, version, durationMillis)
                    }
                } finally {
                    withContext(NonCancellable) {
                        withTimeoutOrNull(ROLE_CLEANUP_TIMEOUT_MS) { adapter.stopDiscovery() }
                    }
                }
            }
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

    private suspend fun runAdvertiserRole(
        adapter: AndroidPairingNsdAdapter,
        sessionId: String,
        sessionHash: String,
        version: String,
        durationMillis: Long,
    ) {
        ServerSocket(0).use { listener ->
            val advertisement = adapter.register(sessionId, listener.localPort)
            try {
                reportRole(ROLE_ADVERTISER, "ready", sessionHash, version)
                delay(durationMillis)
                reportRole(ROLE_ADVERTISER, "complete", sessionHash, version)
            } finally {
                withContext(NonCancellable) {
                    withTimeoutOrNull(ROLE_CLEANUP_TIMEOUT_MS) { adapter.unregister(advertisement) }
                }
            }
        }
    }

    private suspend fun runResolverRole(
        adapter: AndroidPairingNsdAdapter,
        sessionId: String,
        sessionHash: String,
        version: String,
        durationMillis: Long,
    ) {
        withTimeout(durationMillis) { adapter.resolve(sessionId) }
        reportRole(ROLE_RESOLVER, "resolved", sessionHash, version)
    }

    private fun reportRole(role: String, status: String, sessionHash: String, version: String) {
        val result = "TWINOTIFY_LAN_SMOKE role=$role status=$status session_sha256=$sessionHash version=$version transport=wifi"
        Log.i(ROLE_LOG_TAG, result)
        println(result)
    }

    private suspend fun wifiNetworkLease(): WifiNetworkLease {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        return withTimeout(WIFI_NETWORK_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        continuation.resumeLease(connectivity, this, network)
                    }
                }
                continuation.invokeOnCancellation { runCatching { connectivity.unregisterNetworkCallback(callback) } }
                connectivity.registerNetworkCallback(request, callback)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun CancellableContinuation<WifiNetworkLease>.resumeLease(
        connectivity: ConnectivityManager,
        callback: ConnectivityManager.NetworkCallback,
        network: Network,
    ) {
        if (isActive) {
            val lease = WifiNetworkLease(connectivity, callback, network)
            resume(lease) { lease.close() }
        }
    }

    private class WifiNetworkLease(
        private val connectivity: ConnectivityManager,
        private val callback: ConnectivityManager.NetworkCallback,
        val network: Network,
    ) : AutoCloseable {
        override fun close() {
            runCatching { connectivity.unregisterNetworkCallback(callback) }
        }
    }

    private companion object {
        const val ARG_ROLE = "lanRole"
        const val ARG_SESSION = "lanSession"
        const val ARG_VERSION = "lanVersion"
        const val ARG_DURATION_MS = "lanDurationMs"
        const val ROLE_ADVERTISER = "advertiser"
        const val ROLE_RESOLVER = "resolver"
        const val ROLE_LOG_TAG = "TwinotifyLanSmoke"
        const val MIN_ROLE_DURATION_MS = 5_000L
        const val MAX_ROLE_DURATION_MS = 60_000L
        const val ROLE_CLEANUP_TIMEOUT_MS = 5_000L
        const val WIFI_NETWORK_TIMEOUT_MS = 10_000L
    }
}
