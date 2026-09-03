package co.twinotify.core.call

import java.util.concurrent.ConcurrentHashMap

class CallCapabilityRegistry<T> {
    private val generations = ConcurrentHashMap<String, CallCapabilityGeneration<T>>()

    fun install(canonId: String, generation: CallCapabilityGeneration<T>) {
        require(canonId.isNotEmpty())
        require(generation.sequence >= 1)
        generations[canonId] = generation.copy(controls = generation.controls.toMap())
    }

    fun lookup(
        canonId: String,
        sequence: Long,
        controlId: String,
        kind: CallControlKind,
    ): CallCapabilityLookup<T> {
        val generation = generations[canonId]
            ?: return CallCapabilityLookup.MissingGeneration
        if (generation.sequence != sequence) return CallCapabilityLookup.StaleGeneration
        val control = generation.controls[controlId]
            ?: return CallCapabilityLookup.MissingControl
        if (control.kind != kind) return CallCapabilityLookup.MissingControl
        return CallCapabilityLookup.Found(control.handle)
    }

    fun purge(canonId: String) {
        generations.remove(canonId)
    }

    fun clear() {
        generations.clear()
    }
}
