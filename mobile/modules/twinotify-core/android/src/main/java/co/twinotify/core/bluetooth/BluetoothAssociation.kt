package co.twinotify.core.bluetooth

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import co.twinotify.core.crypto.CryptoStore
import co.twinotify.core.storage.DeviceIdentity
import co.twinotify.core.storage.PeerStore
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Pure decisions for the Bluetooth route. Nothing here touches the Android runtime. */
object BluetoothAssociationPolicy {
    /** Nearby-device permissions only. No location, audio, call, or privileged Bluetooth access. */
    val RUNTIME_PERMISSIONS: Set<String> = setOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE,
    )

    /** The whole association flow, advertising included, ends after this. */
    const val ASSOCIATION_TIMEOUT_MILLIS = 120_000L

    /**
     * The one advertisement covers the picker and the connect that follows it.
     *
     * It cannot be stopped and restarted in between: the stack gives every new advertising set a
     * fresh resolvable private address, which would invalidate the address the peer's picker just
     * recorded and leave its dial timing out against nobody.
     */
    const val ADVERTISEMENT_TIMEOUT_MILLIS = ASSOCIATION_TIMEOUT_MILLIS + 27_000L

    /**
     * The picker discovers over an LE scan, so the chosen device reports LE or unknown.
     * Requiring DUAL here rejected every real peer, and the route no longer needs a Classic
     * radio at all: it runs over an LE L2CAP channel. Accept any device the picker actually
     * returned and let the signed handshake prove it is the confirmed Twinotify peer.
     */
    fun acceptsSelectedDeviceType(type: Int?): Boolean = type != null

    fun canEnableRoute(permissionsGranted: Boolean, peerConfirmed: Boolean, bindingValidated: Boolean): Boolean =
        permissionsGranted && peerConfirmed && bindingValidated

    /** `connectedDevice` joins the foreground-service type only under both conditions. */
    fun foregroundRouteActive(routeEnabled: Boolean, permissionsGranted: Boolean): Boolean =
        routeEnabled && permissionsGranted

    fun permissionsGranted(context: Context): Boolean = RUNTIME_PERMISSIONS.all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}

enum class BluetoothAssociationFailure(val code: String) {
    PERMISSION_DENIED("bluetooth_permission_denied"),
    ACTIVITY_UNAVAILABLE("bluetooth_activity_unavailable"),
    BLUETOOTH_UNAVAILABLE("bluetooth_unavailable"),
    ADVERTISING_FAILED("bluetooth_advertising_failed"),
    ASSOCIATION_IN_PROGRESS("bluetooth_association_in_progress"),
    ASSOCIATION_FAILED("bluetooth_association_failed"),
    LISTEN_FAILED("bluetooth_listen_failed"),
    SCAN_FAILED("bluetooth_scan_failed"),
    PEER_NOT_DISCOVERED("bluetooth_peer_not_discovered"),
    PEER_PSM_UNAVAILABLE("bluetooth_peer_psm_unavailable"),
    DEVICE_UNUSABLE("bluetooth_device_unusable"),
    PEER_NOT_CONFIRMED("bluetooth_peer_not_confirmed"),
}

class BluetoothAssociationException(val failure: BluetoothAssociationFailure) : RuntimeException(failure.code)

/**
 * A user-approved association that has not yet proven the Twinotify peer identity. It lives in
 * memory only: the address never reaches storage, and no [BluetoothBinding] exists until the
 * authenticated handshake saves one.
 *
 * It also carries the L2CAP server socket opened before the advertisement started, so the PSM the
 * peer read off the air is the one this phone is still accepting on, plus the peer's own PSM read
 * from its advertisement. Closing it releases that socket.
 */
class ProvisionalBluetoothAssociation internal constructor(
    val associationId: Int,
    internal val device: BluetoothDevice,
    internal val peerPsm: Int,
    internal val listener: BluetoothL2capListener,
    private val advertisement: DiscoveryAdvertisement,
) : Closeable {
    override fun close() {
        listener.close()
        advertisement.close()
    }
}

object ProvisionalBluetoothAssociations {
    private val current = AtomicReference<ProvisionalBluetoothAssociation?>(null)

    fun current(): ProvisionalBluetoothAssociation? = current.get()

    internal fun replace(value: ProvisionalBluetoothAssociation?) {
        current.getAndSet(value)?.takeIf { it !== value }?.close()
    }

    fun clear() {
        current.getAndSet(null)?.close()
    }
}

/** Companion Device Manager reads used to validate and remove stored associations. */
object BluetoothAssociations {
    fun currentIds(context: Context): Set<Int> =
        companionDeviceManager(context)?.myAssociations?.map { it.id }?.toSet() ?: emptySet()

    fun disassociate(context: Context, associationId: Int) {
        runCatching { companionDeviceManager(context)?.disassociate(associationId) }
    }

    internal fun companionDeviceManager(context: Context): CompanionDeviceManager? =
        context.applicationContext.getSystemService(CompanionDeviceManager::class.java)
}

/**
 * Second half of the public association operation. A provisional association proves only
 * that the user picked a device; this proves the device is the confirmed Twinotify peer by
 * running the signed L2CAP handshake, and only then writes the durable binding. Any
 * failure, including cancellation, closes the socket, discards the provisional state, and
 * disassociates that exact provisional ID so an unverified association is never left enabled.
 */
object BluetoothAssociationCompletion {
    suspend fun complete(context: Context, provisional: ProvisionalBluetoothAssociation): BluetoothBinding {
        val appContext = context.applicationContext
        try {
            val peer = PeerStore.load(appContext)
                ?: throw BluetoothAssociationException(BluetoothAssociationFailure.PEER_NOT_CONFIRMED)
            if (!BluetoothAssociationPolicy.permissionsGranted(appContext)) {
                throw BluetoothAssociationException(BluetoothAssociationFailure.PERMISSION_DENIED)
            }
            appContext.getSystemService(BluetoothManager::class.java)?.adapter
                ?.takeIf { it.isEnabled }
                ?: throw BluetoothAssociationException(BluetoothAssociationFailure.BLUETOOTH_UNAVAILABLE)
            val localDeviceId = DeviceIdentity.getOrCreate(appContext)
            val signingKeys = CryptoStore.loadOrGenerate(appContext).second
            // The provisional is still advertising the service and PSM it published before the
            // picker opened. An L2CAP channel rides an LE ACL link and only a connectable
            // advertiser can be dialled, so that advertisement has to outlive the picker: it
            // stops when the provisional is closed, win or lose.
            val links = AndroidBluetoothLinkProvider(
                context = appContext,
                device = provisional.device,
                peerPsm = provisional.peerPsm,
                listener = provisional.listener,
            )
            val connector = BluetoothConnector(
                localDeviceId = localDeviceId,
                peerDeviceId = peer.deviceId,
                links = links,
                authenticator = SignedBluetoothWireAuthenticator { role ->
                    BluetoothHandshake(
                        localDeviceId = localDeviceId,
                        peerDeviceId = peer.deviceId,
                        localSigningKey = signingKeys.secretKey,
                        peerSigningKey = peer.signPubkey,
                        role = role,
                    )
                },
            )
            // Association proves identity only; route sessions are the coordinator's to open.
            try {
                connector.connect().close()
            } finally {
                links.close()
            }
            val binding = BluetoothBinding(
                associationId = provisional.associationId,
                peerDeviceId = peer.deviceId,
                peerSigningKeySha256 = BluetoothBinding.signingKeyDigest(peer.signPubkey),
            )
            BluetoothBindingStore.forContext(appContext).save(binding)
            // Clearing closes the provisional, which stops the advertisement and the listener.
            ProvisionalBluetoothAssociations.clear()
            return binding
        } catch (error: Throwable) {
            withContext(NonCancellable) { discard(appContext, provisional) }
            throw error
        }
    }

    private fun discard(context: Context, provisional: ProvisionalBluetoothAssociation) {
        ProvisionalBluetoothAssociations.clear()
        provisional.close()
        BluetoothAssociations.disassociate(context, provisional.associationId)
    }
}

/** Durable enablement plus every runtime permission: the input to the foreground-service type. */
object BluetoothRouteGate {
    suspend fun foregroundActive(context: Context): Boolean = BluetoothAssociationPolicy.foregroundRouteActive(
        routeEnabled = BluetoothBindingStore.forContext(context).routeEnabled(),
        permissionsGranted = BluetoothAssociationPolicy.permissionsGranted(context),
    )
}

/**
 * One generic CDM association flow. It advertises the discovery service only while the flow is
 * open, filters the picker to that same service, sets no device profile, and hands back a
 * provisional in-memory association or null when the user declined or the flow timed out.
 */
class BluetoothAssociationFlow private constructor(
    private val context: Context,
    private val activity: Activity,
    private val companionDeviceManager: CompanionDeviceManager,
) {
    private val outcome = CompletableDeferred<AssociationInfo?>()

    private val callback = object : CompanionDeviceManager.Callback() {
        override fun onAssociationPending(intentSender: IntentSender) {
            try {
                activity.startIntentSenderForResult(intentSender, REQUEST_CODE, null, 0, 0, 0)
            } catch (_: IntentSender.SendIntentException) {
                outcome.completeExceptionally(BluetoothAssociationException(BluetoothAssociationFailure.ASSOCIATION_FAILED))
            }
        }

        override fun onAssociationCreated(associationInfo: AssociationInfo) {
            // A selection that lands after cancel or timeout must not leave a stray association.
            if (!outcome.complete(associationInfo)) {
                runCatching { companionDeviceManager.disassociate(associationInfo.id) }
            }
        }

        override fun onFailure(error: CharSequence?) {
            outcome.completeExceptionally(BluetoothAssociationException(BluetoothAssociationFailure.ASSOCIATION_FAILED))
        }
    }

    private suspend fun execute(): ProvisionalBluetoothAssociation? {
        if (!BluetoothAssociationPolicy.permissionsGranted(context)) {
            throw BluetoothAssociationException(BluetoothAssociationFailure.PERMISSION_DENIED)
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?.takeIf { it.isEnabled }
            ?: throw BluetoothAssociationException(BluetoothAssociationFailure.BLUETOOTH_UNAVAILABLE)
        // Listen first, then advertise: the PSM published over the air has to be one this phone
        // is already accepting on. Both stay open past the picker and stop with the provisional.
        val listener = BluetoothDiscovery.openListener(context, adapter)
        val advertisement = DiscoveryAdvertisement(
            context = context,
            advertiser = BluetoothDiscovery.advertiser(adapter),
            psm = listener.psm,
            timeoutMillis = BluetoothAssociationPolicy.ADVERTISEMENT_TIMEOUT_MILLIS,
        ) {
            outcome.completeExceptionally(
                BluetoothAssociationException(BluetoothAssociationFailure.ADVERTISING_FAILED),
            )
        }
        var handedOver = false
        try {
            advertisement.start()
            companionDeviceManager.associate(request(), ContextCompat.getMainExecutor(context), callback)
            val info = withTimeoutOrNull(BluetoothAssociationPolicy.ASSOCIATION_TIMEOUT_MILLIS) { outcome.await() }
            if (info == null) {
                outcome.complete(null)
                return null
            }
            // The picker discovers over an LE scan, so this is a ScanResult whose record carries
            // the peer's advertised PSM. Its address is a resolvable private one, valid only
            // while the peer keeps that same advertising set running.
            val scanResult = info.associatedDevice?.bleDevice
            if (!BluetoothAssociationPolicy.acceptsSelectedDeviceType(deviceType(scanResult?.device))) {
                runCatching { companionDeviceManager.disassociate(info.id) }
                throw BluetoothAssociationException(BluetoothAssociationFailure.DEVICE_UNUSABLE)
            }
            val peer = BluetoothDiscovery.readPeer(requireNotNull(scanResult))
            if (peer == null) {
                runCatching { companionDeviceManager.disassociate(info.id) }
                throw BluetoothAssociationException(BluetoothAssociationFailure.PEER_PSM_UNAVAILABLE)
            }
            handedOver = true
            return ProvisionalBluetoothAssociation(info.id, peer.device, peer.psm, listener, advertisement)
                .also(ProvisionalBluetoothAssociations::replace)
        } finally {
            if (!handedOver) {
                advertisement.close()
                listener.close()
            }
        }
    }

    private fun deviceType(device: BluetoothDevice?): Int? {
        if (device == null) return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        return device.type
    }

    private fun request(): AssociationRequest {
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BluetoothConstants.DISCOVERY_SERVICE_UUID))
            .build()
        val deviceFilter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(scanFilter)
            .build()
        return AssociationRequest.Builder()
            .addDeviceFilter(deviceFilter)
            .build()
    }

    companion object {
        internal const val REQUEST_CODE = 0x5457
        private val active = AtomicReference<BluetoothAssociationFlow?>(null)

        suspend fun run(context: Context, activity: Activity): ProvisionalBluetoothAssociation? {
            val appContext = context.applicationContext
            if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                throw BluetoothAssociationException(BluetoothAssociationFailure.PERMISSION_DENIED)
            }
            // A disabled radio cannot advertise or open the picker. Report it as its own bounded
            // failure so the caller can say what is wrong instead of a generic retry.
            val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null || !adapter.isEnabled) {
                throw BluetoothAssociationException(BluetoothAssociationFailure.BLUETOOTH_UNAVAILABLE)
            }
            val manager = BluetoothAssociations.companionDeviceManager(appContext)
                ?: throw BluetoothAssociationException(BluetoothAssociationFailure.BLUETOOTH_UNAVAILABLE)
            val flow = BluetoothAssociationFlow(appContext, activity, manager)
            if (!active.compareAndSet(null, flow)) {
                throw BluetoothAssociationException(BluetoothAssociationFailure.ASSOCIATION_IN_PROGRESS)
            }
            try {
                return flow.execute()
            } finally {
                active.compareAndSet(flow, null)
            }
        }

        /** The picker's own result; anything but OK ends the flow without an association. */
        fun onActivityResult(requestCode: Int, resultCode: Int): Boolean {
            if (requestCode != REQUEST_CODE) return false
            val flow = active.get() ?: return true
            if (resultCode != Activity.RESULT_OK) flow.outcome.complete(null)
            return true
        }

        /** Ends an open flow, for example when the hosting activity stops. */
        fun cancelActive() {
            active.get()?.outcome?.complete(null)
        }
    }
}
