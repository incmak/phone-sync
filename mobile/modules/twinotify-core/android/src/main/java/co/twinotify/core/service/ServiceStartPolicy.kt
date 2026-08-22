package co.twinotify.core.service

/** Pure lifecycle decision boundary used by service, boot, and JVM policy tests. */
sealed interface ServiceStartDecision {
    /**
     * [relayUrl] is null for a peer that has only a direct LAN binding. Such a peer is
     * a first-class configuration, not a degraded one: it never had a relay and must
     * still run.
     */
    data class Start(val relayUrl: String?, val lanBound: Boolean) : ServiceStartDecision
    data class Stop(val reason: String) : ServiceStartDecision
}

object ServiceStartPolicy {
    const val BOOT_ACTION = "android.intent.action.BOOT_COMPLETED"

    fun decide(
        intentAction: String?,
        persisted: ServiceConfig,
        paired: Boolean,
        lanBound: Boolean = false,
    ): ServiceStartDecision {
        if (intentAction == SyncService.ACTION_STOP) {
            return ServiceStartDecision.Stop("user_disabled")
        }
        // A user stop outranks any available route, including a direct LAN binding.
        if (!persisted.enabled) return ServiceStartDecision.Stop("disabled")
        if (!paired) return ServiceStartDecision.Stop("not_paired")
        val relayUrl = persisted.relayUrl?.takeIf { it.isNotBlank() }
        if (relayUrl == null && !lanBound) return ServiceStartDecision.Stop("no_route_available")
        return ServiceStartDecision.Start(relayUrl, lanBound)
    }

    fun applyUserStop(persisted: ServiceConfig): ServiceConfig = persisted.copy(enabled = false)

    fun applyUserStart(persisted: ServiceConfig, relayUrl: String): ServiceConfig {
        val canonical = relayUrl.trim().trimEnd('/')
        require(canonical.isNotEmpty()) { "relay URL must not be empty" }
        return persisted.copy(enabled = true, relayUrl = canonical)
    }

    /** Enable a peer that pairs and delivers over the LAN and has no relay at all. */
    fun applyLanOnlyStart(persisted: ServiceConfig): ServiceConfig =
        persisted.copy(enabled = true)
}
