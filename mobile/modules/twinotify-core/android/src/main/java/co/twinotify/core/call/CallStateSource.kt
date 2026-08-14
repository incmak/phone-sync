package co.twinotify.core.call

/** Small source seam so coordinator sequencing can be tested without telephony hardware. */
interface CallStateSource {
    fun capabilities(): CallSourceCapabilities

    /** Registers callbacks and returns a handle whose close is safe to call repeatedly. */
    fun register(listener: (CallFrameworkState) -> Unit): AutoCloseable
}
