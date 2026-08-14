package co.twinotify.core.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** API 31+ telephony adapter. It reads only framework state, never call identity or contacts. */
class TelephonyCallStateSource(
    context: Context,
    private val executor: Executor = ContextCompat.getMainExecutor(context),
) : CallStateSource {
    private val appContext = context.applicationContext

    override fun capabilities(): CallSourceCapabilities {
        val manager = appContext.getSystemService(TelephonyManager::class.java)
        return CallSourceCapabilities(
            supported = manager != null,
            permissionGranted = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.READ_PHONE_STATE,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }

    override fun register(listener: (CallFrameworkState) -> Unit): AutoCloseable {
        val capabilities = capabilities()
        require(capabilities.supported) { "telephony call-state callbacks are unsupported" }
        require(capabilities.permissionGranted) { "READ_PHONE_STATE permission is denied" }
        val manager = requireNotNull(appContext.getSystemService(TelephonyManager::class.java))
        val closed = AtomicBoolean(false)
        val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                if (closed.get()) return
                val mapped = when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> CallFrameworkState.RINGING
                    TelephonyManager.CALL_STATE_OFFHOOK -> CallFrameworkState.OFFHOOK
                    TelephonyManager.CALL_STATE_IDLE -> CallFrameworkState.IDLE
                    else -> return
                }
                runCatching { listener(mapped) }
            }
        }
        manager.registerTelephonyCallback(executor, callback)
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                manager.unregisterTelephonyCallback(callback)
            }
        }
    }
}
