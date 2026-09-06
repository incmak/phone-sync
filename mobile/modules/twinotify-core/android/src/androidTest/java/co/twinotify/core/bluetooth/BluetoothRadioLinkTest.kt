package co.twinotify.core.bluetooth

import android.Manifest
import android.bluetooth.BluetoothManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import co.twinotify.core.direct.DirectCommand
import co.twinotify.core.lan.LanFrameLimits
import com.goterl.lazysodium.SodiumAndroid
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/**
 * Opt-in two-device radio test. Run concurrently with -e bluetooth_role client/server.
 * Uses Android BLE discovery, LE L2CAP, and the production signed handshake and framing.
 * Fixed fixture identities exist only in this test APK; no app pairing or user keys are read.
 * This proves the radio adapter boundary, not call dispatch, CDM association, or OEM support.
 */
@RunWith(AndroidJUnit4::class)
class BluetoothRadioLinkTest {
    @Test
    fun discoveredL2capPeerAuthenticatesAndCarriesMaximumFrameBothWays() = runBlocking {
        val argument = InstrumentationRegistry.getArguments().getString("bluetooth_role")
        assumeTrue("requires concurrent client/server instrumentation on two devices", argument != null)
        require(argument == "client" || argument == "server")
        val client = argument == "client"
        val fixtureBytes = InstrumentationRegistry.getArguments().getString("bluetooth_bytes")?.toInt()
            ?: LanFrameLimits.MAX_ENVELOPE_BYTES
        require(fixtureBytes in 14..LanFrameLimits.MAX_ENVELOPE_BYTES)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.adoptShellPermissionIdentity(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        )
        try {
            val adapter = context.getSystemService(BluetoothManager::class.java).adapter
            assertTrue(adapter.isEnabled, "enable Bluetooth on both test devices first")
            val listener = BluetoothDiscovery.openListener(context, adapter)
            val advertisement = DiscoveryAdvertisement(
                context, BluetoothDiscovery.advertiser(adapter), listener.psm, 60_000,
            )
            val sentBytes = java.util.concurrent.atomic.AtomicLong()
            val receivedBytes = java.util.concurrent.atomic.AtomicLong()
            fun counted(socket: BluetoothStreamSocket): BluetoothStreamSocket = object : BluetoothStreamSocket by socket {
                override val inputStream = object : java.io.FilterInputStream(socket.inputStream) {
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                        socket.inputStream.read(bytes, offset, length).also { if (it > 0) receivedBytes.addAndGet(it.toLong()) }
                }
                override val outputStream = object : java.io.FilterOutputStream(socket.outputStream) {
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        socket.outputStream.write(bytes, offset, length)
                        sentBytes.addAndGet(length.toLong())
                    }
                }
            }
            val links = object : BluetoothLinkProvider {
                override val peerAddress: String? = null
                override suspend fun listen(): BluetoothLinkListener = object : BluetoothLinkListener {
                    override suspend fun accept() = counted(listener.accept())
                    override fun close() = listener.close()
                }
                override suspend fun connect(): BluetoothStreamSocket {
                    val peer = BluetoothDiscovery.awaitPeer(context, adapter, 12_000)
                    return counted(dialL2capChannel(context, peer.device, peer.psm))
                }
                override fun close() {
                    advertisement.close()
                    listener.close()
                }
            }
            try {
                advertisement.start()
                val sodium = SodiumAndroid()
                val localPublic = ByteArray(32)
                val localSecret = ByteArray(64)
                val peerPublic = ByteArray(32)
                val peerSecret = ByteArray(64)
                assertEquals(0, sodium.crypto_sign_seed_keypair(localPublic, localSecret, ByteArray(32) { if (client) 1 else 2 }))
                assertEquals(0, sodium.crypto_sign_seed_keypair(peerPublic, peerSecret, ByteArray(32) { if (client) 2 else 1 }))
                peerSecret.fill(0)
                val wire = try {
                    BluetoothConnector(
                        if (client) "radio-a" else "radio-b",
                        if (client) "radio-b" else "radio-a",
                        links,
                        SignedBluetoothWireAuthenticator { role ->
                            BluetoothHandshake(
                                if (client) "radio-a" else "radio-b",
                                if (client) "radio-b" else "radio-a",
                                localSecret, peerPublic, role,
                            )
                        },
                    ).connect()
                } finally {
                    localSecret.fill(0)
                }
                links.close()
                instrumentation.sendStatus(0, android.os.Bundle().apply { putString("radio_phase", "authenticated") })
                try {
                    // The frame contract carries UTF-8 envelope bytes, not arbitrary binary.
                    val payload = ("{\"fixture\":\"" + "a".repeat(fixtureBytes - 14) + "\"}").encodeToByteArray()
                    assertEquals(fixtureBytes, payload.size)
                    var puts = 0
                    var pong = false
                    withTimeout(150_000) {
                        coroutineScope {
                            // Production runs its reader and heartbeat while the outbox writes.
                            // Serial send-then-read creates an artificial L2CAP credit deadlock.
                            val heartbeat = launch {
                                while (true) {
                                    delay(3_000)
                                    try {
                                        wire.send(DirectCommand.Ping(7))
                                    } catch (error: BluetoothWireException) {
                                        if (error.failure != BluetoothWireFailure.CLOSED) throw error
                                        break
                                    }
                                }
                            }
                            val sender = if (client) launch {
                                wire.send(DirectCommand.Put(payload))
                                wire.send(DirectCommand.Ping(42))
                            } else null
                            try {
                                wire.incoming.transformWhile { command ->
                                    emit(command)
                                    !(client && command is DirectCommand.Pong && command.token == 42L)
                                }.collect { command ->
                                    when (command) {
                                        is DirectCommand.Put -> {
                                            assertContentEquals(payload, command.envelope)
                                            puts++
                                            instrumentation.sendStatus(0, android.os.Bundle().apply {
                                                putString("radio_phase", "received")
                                                putInt("envelope_bytes", payload.size)
                                            })
                                            if (!client) launch { wire.send(DirectCommand.Put(payload)) }
                                        }
                                        is DirectCommand.Ping -> launch {
                                            try {
                                                wire.send(DirectCommand.Pong(command.token))
                                            } catch (error: BluetoothWireException) {
                                                if (error.failure != BluetoothWireFailure.CLOSED) throw error
                                            }
                                        }
                                        is DirectCommand.Pong -> {
                                            if (client && command.token == 42L) {
                                                pong = true
                                                heartbeat.cancelAndJoin()
                                                wire.send(DirectCommand.Close("test_complete"))
                                            }
                                        }
                                        else -> Unit
                                    }
                                }
                            } finally {
                                heartbeat.cancelAndJoin()
                                sender?.cancelAndJoin()
                            }
                        }
                    }
                    assertEquals(1, puts)
                    if (client) assertTrue(pong)
                } finally {
                    wire.close()
                }
            } finally {
                links.close()
                instrumentation.sendStatus(0, android.os.Bundle().apply {
                    putLong("wire_sent_bytes", sentBytes.get())
                    putLong("wire_received_bytes", receivedBytes.get())
                })
            }
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }
}
