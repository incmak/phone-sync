package co.twinotify.core.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.io.Closeable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One phone as seen over the air: the LE device to dial and the L2CAP PSM it is listening on.
 * The address is kept for this attempt only and is never stored or printed.
 */
internal class DiscoveredBluetoothPeer(
    val device: BluetoothDevice,
    val psm: Int,
)

/**
 * Advertises the discovery service, and the local L2CAP PSM under it, for exactly as long as one
 * association flow or one route attempt is open.
 *
 * The service UUID rides in the advertisement and the PSM in the scan response because the two
 * cannot share a 31-byte legacy packet; see [BluetoothAdvertisementLayout].
 */
internal class DiscoveryAdvertisement(
    context: Context,
    private val advertiser: BluetoothLeAdvertiser,
    private val psm: Int,
    private val timeoutMillis: Long,
    private val onFailure: () -> Unit = {},
) : AdvertiseCallback(), Closeable {
    private val context = context.applicationContext

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
            .setTimeout(timeoutMillis.coerceAtMost(MAX_ADVERTISE_TIMEOUT_MILLIS).toInt())
            .build()
        val advertisement = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(BluetoothConstants.DISCOVERY_SERVICE_UUID))
            .setIncludeDeviceName(false)
            .build()
        val scanResponse = AdvertiseData.Builder()
            .addServiceData(ParcelUuid(BluetoothConstants.DISCOVERY_SERVICE_UUID), BluetoothPsm.encode(psm))
            .setIncludeDeviceName(false)
            .build()
        try {
            advertiser.startAdvertising(settings, advertisement, scanResponse, this)
        } catch (_: IllegalStateException) {
            throw BluetoothAssociationException(BluetoothAssociationFailure.ADVERTISING_FAILED)
        }
    }

    override fun close() {
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

    private companion object {
        /** AdvertiseSettings rejects anything above this; the caller's own deadline still bounds us. */
        const val MAX_ADVERTISE_TIMEOUT_MILLIS = 180_000L
    }
}

/** Radio-side discovery helpers. Every entry point checks its own permission so lint can see it. */
internal object BluetoothDiscovery {
    /**
     * Opens the LE L2CAP server socket the peer will dial.
     *
     * Insecure is deliberate. Link-layer encryption here would require bonding, and the plan
     * treats Bluetooth link encryption as defence in depth rather than the security boundary:
     * the Ed25519 mutual challenge and the E2EE v2 envelopes are what actually protect the data.
     */
    fun openListener(context: Context, adapter: BluetoothAdapter): BluetoothL2capListener {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw BluetoothAssociationException(BluetoothAssociationFailure.PERMISSION_DENIED)
        }
        val serverSocket = try {
            adapter.listenUsingInsecureL2capChannel()
        } catch (_: Throwable) {
            throw BluetoothAssociationException(BluetoothAssociationFailure.LISTEN_FAILED)
        }
        return AndroidL2capListener(serverSocket)
    }

    fun advertiser(adapter: BluetoothAdapter): BluetoothLeAdvertiser =
        adapter.bluetoothLeAdvertiser
            ?: throw BluetoothAssociationException(BluetoothAssociationFailure.ADVERTISING_FAILED)

    /**
     * Scans for the peer's discovery advertisement and reads its PSM. The scan is stopped on every
     * exit, including cancellation, so nothing keeps scanning once the attempt ends.
     */
    suspend fun awaitPeer(context: Context, adapter: BluetoothAdapter, timeoutMillis: Long): DiscoveredBluetoothPeer {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw BluetoothAssociationException(BluetoothAssociationFailure.PERMISSION_DENIED)
        }
        val scanner = adapter.bluetoothLeScanner
            ?: throw BluetoothAssociationException(BluetoothAssociationFailure.SCAN_FAILED)
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BluetoothConstants.DISCOVERY_SERVICE_UUID))
                .build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        val outcome = CompletableDeferred<DiscoveredBluetoothPeer>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.let(::readPeer)?.let(outcome::complete)
            }

            override fun onScanFailed(errorCode: Int) {
                outcome.completeExceptionally(
                    BluetoothAssociationException(BluetoothAssociationFailure.SCAN_FAILED),
                )
            }
        }
        try {
            scanner.startScan(filters, settings, callback)
        } catch (_: Throwable) {
            throw BluetoothAssociationException(BluetoothAssociationFailure.SCAN_FAILED)
        }
        try {
            return withTimeoutOrNull(timeoutMillis) { outcome.await() }
                ?: throw BluetoothAssociationException(BluetoothAssociationFailure.PEER_NOT_DISCOVERED)
        } finally {
            // Every exit stops the scan, cancellation included: nothing scans past the attempt.
            runCatching { scanner.stopScan(callback) }
        }
    }

    /** The service data is the peer's PSM; an advertisement without a usable one is ignored. */
    fun readPeer(result: ScanResult): DiscoveredBluetoothPeer? {
        val serviceData = result.scanRecord
            ?.getServiceData(ParcelUuid(BluetoothConstants.DISCOVERY_SERVICE_UUID))
            ?: return null
        val psm = runCatching { BluetoothPsm.decode(serviceData) }.getOrNull() ?: return null
        return DiscoveredBluetoothPeer(result.device, psm)
    }
}
