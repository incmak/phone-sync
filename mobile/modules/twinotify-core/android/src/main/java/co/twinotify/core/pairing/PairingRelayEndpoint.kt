package co.twinotify.core.pairing

import co.twinotify.core.service.RelayUrlPolicy
import okhttp3.HttpUrl

/** Builds every relay pairing URL through the same release TLS policy as the live transport. */
internal object PairingRelayEndpoint {
    fun http(relayUrl: String, vararg pathSegments: String, debug: Boolean): HttpUrl {
        val builder = RelayUrlPolicy.parse(relayUrl, debug = debug).pairing.newBuilder()
        pathSegments.forEach(builder::addPathSegment)
        return builder.build()
    }

    fun notify(relayUrl: String, pairToken: String, role: String, debug: Boolean): HttpUrl {
        require(role == "A" || role == "B") { "role must be A or B, got $role" }
        return http(relayUrl, "pair", "notify", debug = debug).newBuilder()
            .addQueryParameter("token", pairToken)
            .addQueryParameter("role", role)
            .build()
    }
}
