package co.twinotify.core.call

/** Framework call states exposed by TelephonyCallback without carrying caller identity. */
enum class CallFrameworkState { RINGING, OFFHOOK, IDLE }

enum class CallDirection { INCOMING, OUTGOING, UNKNOWN }

enum class CallCaptureDisabledReason {
    PERMISSION_DENIED,
    UNSUPPORTED_TELEPHONY,
    DISABLED,
    CALLBACK_REGISTRATION_FAILED,
}

val CallCaptureDisabledReason.code: String
    get() = when (this) {
        CallCaptureDisabledReason.PERMISSION_DENIED -> "call_permission_denied"
        CallCaptureDisabledReason.UNSUPPORTED_TELEPHONY -> "call_telephony_unsupported"
        CallCaptureDisabledReason.DISABLED -> "call_capture_disabled"
        CallCaptureDisabledReason.CALLBACK_REGISTRATION_FAILED -> "call_callback_registration_failed"
    }

/** Privacy-bounded state emitted by the local coordinator. */
data class CallStateEvent(
    val callSessionId: String,
    val state: String,
    val direction: CallDirection,
    val sequence: Long,
    val controls: List<CallControlDescriptor> = emptyList(),
    internal val pendingGeneration: CallCapabilityGeneration<*>? = null,
)

internal data class CallControlSessionSnapshot(
    val state: CallFrameworkState,
    val direction: CallDirection,
)

data class CallSourceCapabilities(
    val supported: Boolean,
    val permissionGranted: Boolean,
)

data class CallCaptureStatus(
    val enabled: Boolean,
    val reason: CallCaptureDisabledReason? = null,
    val lastErrorCode: String? = null,
)

sealed interface CallCaptureDecision {
    data object Start : CallCaptureDecision
    data class Disabled(val code: String) : CallCaptureDecision
}

object CallCapturePolicy {
    fun decide(enabled: Boolean, capabilities: CallSourceCapabilities): CallCaptureDecision {
        if (!enabled) return CallCaptureDecision.Disabled(CallCaptureDisabledReason.DISABLED.code)
        if (!capabilities.supported) return CallCaptureDecision.Disabled(CallCaptureDisabledReason.UNSUPPORTED_TELEPHONY.code)
        if (!capabilities.permissionGranted) return CallCaptureDecision.Disabled(CallCaptureDisabledReason.PERMISSION_DENIED.code)
        return CallCaptureDecision.Start
    }
}
