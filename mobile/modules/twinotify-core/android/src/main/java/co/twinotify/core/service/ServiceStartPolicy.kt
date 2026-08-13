package co.twinotify.core.service

/** Pure lifecycle decision boundary used by service, boot, and JVM policy tests. */
sealed interface ServiceStartDecision {
    data class Start(val relayUrl: String) : ServiceStartDecision
    data class Stop(val reason: String) : ServiceStartDecision
}

object ServiceStartPolicy {
    const val BOOT_ACTION = "android.intent.action.BOOT_COMPLETED"

    fun decide(
        intentAction: String?,
        persisted: ServiceConfig,
        paired: Boolean,
    ): ServiceStartDecision {
        if (intentAction == SyncService.ACTION_STOP) {
            return ServiceStartDecision.Stop("user_disabled")
        }
        if (!persisted.enabled) return ServiceStartDecision.Stop("disabled")
        if (!paired) return ServiceStartDecision.Stop("not_paired")
        val relayUrl = persisted.relayUrl?.takeIf { it.isNotBlank() }
            ?: return ServiceStartDecision.Stop("missing_relay_url")
        return ServiceStartDecision.Start(relayUrl)
    }

    fun applyUserStop(persisted: ServiceConfig): ServiceConfig = persisted.copy(enabled = false)

    fun applyUserStart(persisted: ServiceConfig, relayUrl: String): ServiceConfig {
        val canonical = relayUrl.trim().trimEnd('/')
        require(canonical.isNotEmpty()) { "relay URL must not be empty" }
        return persisted.copy(enabled = true, relayUrl = canonical)
    }
}
