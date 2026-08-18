package co.twinotify.core.pairing.lan

import android.content.Context
import co.twinotify.core.OfflinePairingPublicStatus
import co.twinotify.core.OfflinePairingRuntime
import co.twinotify.core.OfflinePairingRuntimeFactory
import co.twinotify.core.crypto.CryptoStore
import co.twinotify.core.storage.DeviceIdentity
import co.twinotify.core.storage.LanBinding
import co.twinotify.core.storage.LanPairStore
import co.twinotify.core.storage.PeerRecord
import co.twinotify.core.storage.PeerStore
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

internal class AndroidOfflinePairingRuntimeFactory(
    private val contextProvider: () -> Context,
    private val secureRandom: SecureRandom = SecureRandom(),
) : OfflinePairingRuntimeFactory {
    override fun start(
        scope: CoroutineScope,
        displayName: String,
        statusSink: (OfflinePairingPublicStatus) -> Unit,
    ): OfflinePairingRuntime {
        val initialized = initialize(displayName)
        val qr = LanPairingQr(
            version = 1,
            sessionId = UUID.randomUUID().toString(),
            createdAtHintMillis = System.currentTimeMillis(),
            lifetimeMillis = PAIRING_LIFETIME_MILLIS,
            deviceId = initialized.identity.deviceId,
            displayName = initialized.identity.displayName,
            encryptionPublicKey = LanPairingBytes(initialized.identity.encryptionPublicKey),
            signingPublicKey = LanPairingBytes(initialized.identity.signingPublicKey),
            tlsSpkiSha256 = LanPairingBytes(initialized.identity.tlsSpkiSha256),
            sessionToken = LanPairingBytes(randomBytes(32)),
        )
        return runtime(scope, OfflinePairingRole.INITIATOR, qr, initialized, LanPairingCodec.encodeQr(qr), statusSink)
    }

    override fun join(
        scope: CoroutineScope,
        qr: LanPairingQr,
        displayName: String,
        statusSink: (OfflinePairingPublicStatus) -> Unit,
    ): OfflinePairingRuntime {
        val initialized = initialize(displayName)
        return runtime(scope, OfflinePairingRole.JOINER, qr, initialized, null, statusSink)
    }

    private fun runtime(
        scope: CoroutineScope,
        role: OfflinePairingRole,
        qr: LanPairingQr,
        initialized: Initialization,
        qrJson: String?,
        statusSink: (OfflinePairingPublicStatus) -> Unit,
    ) = OfflinePairingRuntimeAdapter(
        scope = scope,
        pairingRole = role,
        qr = qr,
        localIdentity = initialized.identity,
        committer = AndroidOfflinePairingCommitter(initialized.context, initialized.existingPeer),
        transport = AndroidOfflinePairingSessionTransport(initialized.context),
        nonce = randomBytes(32),
        qrJson = qrJson,
        statusSink = statusSink,
    )

    /** Recovery is deliberately before the peer snapshot used by the ceremony. */
    private fun initialize(displayName: String): Initialization = runBlocking(Dispatchers.IO) {
        withTimeout(INITIALIZATION_TIMEOUT_MILLIS) {
            val context = contextProvider().applicationContext
            LanPairStore.recover(context, PeerStore.load(context))
            val peer = PeerStore.load(context)
            val (box, sign) = CryptoStore.loadOrGenerate(context)
            val tls = LanIdentityStore.loadOrCreate()
            Initialization(
                context,
                OfflinePairingIdentity(
                    deviceId = DeviceIdentity.getOrCreate(context),
                    displayName = normalizeLanDisplayName(displayName),
                    encryptionPublicKey = box.publicKey,
                    signingPublicKey = sign.publicKey,
                    signingSecretKey = sign.secretKey,
                    tlsSpkiSha256 = tls.spkiSha256,
                ),
                peer,
            )
        }
    }

    private fun randomBytes(size: Int) = ByteArray(size).also(secureRandom::nextBytes)

    private data class Initialization(
        val context: Context,
        val identity: OfflinePairingIdentity,
        val existingPeer: PeerRecord?,
    )

    private companion object {
        const val PAIRING_LIFETIME_MILLIS = 5 * 60 * 1_000L
        const val INITIALIZATION_TIMEOUT_MILLIS = 15_000L
    }
}

private class AndroidOfflinePairingCommitter(
    private val context: Context,
    existingPeer: PeerRecord?,
) : OfflinePairingCommitter {
    private val initialPeer = existingPeer?.copyRecord()

    override fun existingPeer(): OfflinePairingExistingPeer? = initialPeer?.let {
        OfflinePairingExistingPeer(it.deviceId, it.encPubkey, it.signPubkey)
    }

    override fun commit(value: OfflinePairingCommit): Boolean = runBlocking(Dispatchers.IO) {
        withTimeout(COMMIT_TIMEOUT_MILLIS) {
            val peer = initialPeer?.copyRecord() ?: PeerRecord(
                value.peerDeviceId,
                value.peerEncryptionPublicKey,
                value.peerSigningPublicKey,
                value.peerDisplayName,
            )
            val binding = LanBinding(
                value.peerTlsSpkiSha256,
                value.lanSecret,
                value.protocolVersion,
                System.currentTimeMillis(),
            )
            LanPairStore.commit(context, LanPairStore.prepare(context, peer, binding))
            true
        }
    }

    private companion object { const val COMMIT_TIMEOUT_MILLIS = 10_000L }
}

private class AndroidOfflinePairingSessionTransport(
    private val context: Context,
) : OfflinePairingSessionTransport {
    private val closed = AtomicBoolean(false)
    private var lease: PairingWifiNetworkLease? = null
    private var connection: RuntimePairingConnection? = null

    override suspend fun open(
        role: OfflinePairingRole,
        qr: LanPairingQr,
        onNetworkLost: () -> Unit,
    ): RuntimePairingConnection {
        check(!closed.get()) { "pairing_transport_closed" }
        val acquired = PairingWifiNetworkSelector(context).acquire {
            runCatching(onNetworkLost)
            runCatching { connection?.close() }
        }
        lease = acquired
        val nsd = AndroidPairingNsdAdapter(context, acquired.network)
        val transport = if (role == OfflinePairingRole.INITIATOR) {
            OfflinePairingTransport(
                nsd,
                tlsServer = JssePairingTlsServer.open(LanTlsContextFactory.serverContext()),
            )
        } else {
            OfflinePairingTransport(nsd, tlsClient = JssePairingTlsClient())
        }
        val opened = if (role == OfflinePairingRole.INITIATOR) {
            transport.accept(qr.sessionId)
        } else {
            transport.connect(qr.sessionId, qr.tlsSpkiSha256.copy())
        }
        connection = opened
        return opened
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { connection?.close() }
        lease?.close()
    }
}

private fun PeerRecord.copyRecord() = PeerRecord(deviceId, encPubkey, signPubkey, displayName, lanBindingId)
