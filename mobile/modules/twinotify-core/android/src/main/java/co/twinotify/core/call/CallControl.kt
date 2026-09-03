package co.twinotify.core.call

enum class CallControlKind(val wire: String) {
    ANSWER("answer"),
    DECLINE("decline"),
    HANG_UP("hang_up"),
    ;

    companion object {
        fun fromWire(value: String): CallControlKind = entries.first { it.wire == value }
    }
}

data class CallControlDescriptor(
    val controlId: String,
    val kind: CallControlKind,
)

data class RegisteredCallControl<T>(
    val kind: CallControlKind,
    val handle: T,
)

data class CallCapabilityGeneration<T>(
    val sequence: Long,
    val sourceKey: String,
    val controls: Map<String, RegisteredCallControl<T>>,
)

data class CallCapabilityCandidate<T>(
    val sourceKey: String,
    val packageName: String,
    val category: String?,
    val answer: T?,
    val decline: T?,
    val hangUp: T?,
)

sealed interface CallCapabilitySelection<out T> {
    data class Ready<T>(
        val sourceKey: String,
        val handles: Map<CallControlKind, T>,
    ) : CallCapabilitySelection<T>

    data class None(val code: String) : CallCapabilitySelection<Nothing>
}

sealed interface CallCapabilityLookup<out T> {
    data class Found<T>(val handle: T) : CallCapabilityLookup<T>
    data object MissingGeneration : CallCapabilityLookup<Nothing>
    data object StaleGeneration : CallCapabilityLookup<Nothing>
    data object MissingControl : CallCapabilityLookup<Nothing>
}
