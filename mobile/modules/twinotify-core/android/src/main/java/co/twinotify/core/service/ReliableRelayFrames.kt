package co.twinotify.core.service

import org.json.JSONObject

/** Typed relay frames shared by the sender and its unit tests. */
internal object ReliableRelayFrames {
    fun hello(appVersion: String): String = JSONObject()
        .put("v", 2)
        .put("type", "relay.hello")
        .put("protocols", org.json.JSONArray().put(2).put(1))
        .put("app_version", appVersion)
        .toString()

    fun put(envelopeJson: String): String = JSONObject()
        .put("v", 2)
        .put("type", "relay.put")
        .put("envelope", JSONObject(envelopeJson))
        .toString()
}
