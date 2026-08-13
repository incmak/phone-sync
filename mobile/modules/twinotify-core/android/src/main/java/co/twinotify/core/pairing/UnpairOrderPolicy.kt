package co.twinotify.core.pairing

/** Explicit order contract for local unpair; wiping keys while a service job runs is unsafe. */
enum class UnpairStep { StopService, AwaitServiceStopped, RevokePeer, WipeLocal }

object UnpairOrderPolicy {
    fun validate(steps: List<UnpairStep>) {
        val stop = steps.indexOf(UnpairStep.StopService)
        val await = steps.indexOf(UnpairStep.AwaitServiceStopped)
        val wipe = steps.indexOf(UnpairStep.WipeLocal)
        require(stop >= 0 && await > stop && wipe > await) {
            "unpair must stop and await the service before local wipe"
        }
    }
}
