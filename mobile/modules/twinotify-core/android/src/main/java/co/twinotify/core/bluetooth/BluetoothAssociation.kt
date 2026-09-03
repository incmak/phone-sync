package co.twinotify.core.bluetooth

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
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
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
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

    /** The selected BLE identity must also serve the Classic RFCOMM socket this route uses. */
    fun acceptsSelectedDeviceType(type: Int?): Boolean = type == BluetoothDevice.DEVICE_TYPE_DUAL

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
    DEVICE_NOT_DUAL("bluetooth_device_not_dual"),
}

class BluetoothAssociationException(val failure: BluetoothAssociationFailure) : RuntimeException(failure.code)

/**
 * A user-approved association that has not yet proven the Twinotify peer identity. It lives in
 * memory only: the address never reaches storage, and no [BluetoothBinding] exists until the
 * authenticated handshake saves one.
 */
class ProvisionalBluetoothAssociation internal constructor(
    val associationId: Int,
    internal val device: BluetoothDevice,
)

object ProvisionalBluetoothAssociations {
    private val current = AtomicReference<ProvisionalBluetoothAssociation?>(null)

    fun current(): ProvisionalBluetoothAssociation? = current.get()

    internal fun replace(value: ProvisionalBluetoothAssociation?) {
        current.set(value)
    }

    fun clear() {
        current.set(null)
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
        val advertiser = adapter.bluetoothLeAdvertiser
            ?: throw BluetoothAssociationException(BluetoothAssociationFailure.ADVERTISING_FAILED)
        val advertisement = DiscoveryAdvertisement(context, advertiser) {
            outcome.completeExceptionally(BluetoothAssociationException(BluetoothAssociationFailure.ADVERTISING_FAILED))
        }
        val info = try {
            advertisement.start()
            companionDeviceManager.associate(request(), ContextCompat.getMainExecutor(context), callback)
            withTimeoutOrNull(BluetoothAssociationPolicy.ASSOCIATION_TIMEOUT_MILLIS) { outcome.await() }
        } finally {
            advertisement.stop()
        }
        if (info == null) {
            outcome.complete(null)
            return null
        }
        val device = info.associatedDevice?.bleDevice?.device
        if (!BluetoothAssociationPolicy.acceptsSelectedDeviceType(deviceType(device))) {
            runCatching { companionDeviceManager.disassociate(info.id) }
            throw BluetoothAssociationException(BluetoothAssociationFailure.DEVICE_NOT_DUAL)
        }
        return ProvisionalBluetoothAssociation(info.id, requireNotNull(device))
            .also(ProvisionalBluetoothAssociations::replace)
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

/** Advertises the discovery service for the life of one association flow and no longer. */
private class DiscoveryAdvertisement(
    private val context: Context,
    private val advertiser: BluetoothLeAdvertiser,
    private val onFailure: () -> Unit,
) : AdvertiseCallback() {
    fun start() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw BluetoothAssociationException(BluetoothAssociationFailure.PERMISSION_DENIED)
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(BluetoothAssociationPolicy.ASSOCIATION_TIMEOUT_MILLIS.toInt())
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(BluetoothConstants.DISCOVERY_SERVICE_UUID))
            .setIncludeDeviceName(false)
            .build()
        try {
            advertiser.startAdvertising(settings, data, this)
        } catch (_: IllegalStateException) {
            throw BluetoothAssociationException(BluetoothAssociationFailure.ADVERTISING_FAILED)
        }
    }

    fun stop() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching { advertiser.stopAdvertising(this) }
    }

    override fun onStartFailure(errorCode: Int) {
        onFailure()
    }
}
