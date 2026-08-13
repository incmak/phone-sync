package co.twinotify.core.pairing

enum class RevokeOutcome { Revoked, AlreadyRevoked }

/** Pure response policy; no crypto or Android dependencies, so it is JVM-testable. */
object RevocationPolicy {
    fun classify(httpCode: Int, revocationMarkerPresent: Boolean): RevokeOutcome {
        return when (httpCode) {
            204 -> RevokeOutcome.Revoked
            401 -> {
                check(revocationMarkerPresent) {
                    "unauthorized revocation response is terminal only after persisted revoke intent"
                }
                RevokeOutcome.AlreadyRevoked
            }
            else -> error("pair/revoke HTTP $httpCode")
        }
    }
}
