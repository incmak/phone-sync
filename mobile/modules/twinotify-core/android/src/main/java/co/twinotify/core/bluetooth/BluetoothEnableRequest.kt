package co.twinotify.core.bluetooth

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Asks the user to turn Bluetooth on without leaving Twinotify. Android owns the dialog and the
 * decision: this never toggles the radio itself, and a refusal simply returns false.
 */
object BluetoothEnableRequest {
    internal const val REQUEST_CODE = 0x5458
    private const val ENABLE_POLL_ATTEMPTS = 40
    private const val ENABLE_POLL_INTERVAL_MILLIS = 125L

    private val pending = AtomicReference<CompletableDeferred<Boolean>?>(null)

    /** True only when the radio is usable right now, which also needs the connect permission. */
    fun isEnabled(context: Context): Boolean {
        val appContext = context.applicationContext
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        return adapter.isEnabled
    }

    /**
     * Resolves true only once the radio is actually on. An already-enabled adapter answers
     * immediately without showing the user anything.
     */
    suspend fun request(context: Context, activity: Activity): Boolean {
        val appContext = context.applicationContext
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw BluetoothAssociationException(BluetoothAssociationFailure.PERMISSION_DENIED)
        }
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw BluetoothAssociationException(BluetoothAssociationFailure.BLUETOOTH_UNAVAILABLE)
        if (adapter.isEnabled) return true

        val outcome = CompletableDeferred<Boolean>()
        if (!pending.compareAndSet(null, outcome)) {
            throw BluetoothAssociationException(BluetoothAssociationFailure.ASSOCIATION_IN_PROGRESS)
        }
        return try {
            activity.startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_CODE)
            // A dismissed dialog can leave no result at all; treat the bounded wait as a refusal.
            val accepted = withTimeoutOrNull(BluetoothAssociationPolicy.ASSOCIATION_TIMEOUT_MILLIS) {
                outcome.await()
            } ?: false
            // The result arrives while the adapter is still turning on, so wait for the radio
            // itself rather than reporting success the caller cannot use yet.
            accepted && awaitEnabled(adapter)
        } finally {
            pending.compareAndSet(outcome, null)
        }
    }

    private suspend fun awaitEnabled(adapter: BluetoothAdapter): Boolean {
        repeat(ENABLE_POLL_ATTEMPTS) {
            if (adapter.isEnabled) return true
            delay(ENABLE_POLL_INTERVAL_MILLIS)
        }
        return adapter.isEnabled
    }

    /** Returns true when this request owned the result. */
    fun onActivityResult(requestCode: Int, resultCode: Int): Boolean {
        if (requestCode != REQUEST_CODE) return false
        pending.get()?.complete(resultCode == Activity.RESULT_OK)
        return true
    }

    /** Ends an open request, for example when the hosting activity goes away. */
    fun cancelActive() {
        pending.get()?.complete(false)
    }
}
