package co.twinotify.core.protocol

import org.json.JSONObject

/** Resolves committed golden fixtures through their manifest rather than hard-coded paths. */
object ProtocolFixtures {
    fun manifest(): String = readResource("manifest.json")

    fun readPath(path: String): String = readResource(path)

    fun read(type: String, valid: Boolean? = null, expectedCode: String? = null): String {
        val manifest = JSONObject(manifest())
        val entries = manifest.getJSONArray("fixtures")
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            if (entry.getString("type") != type) continue
            if (valid != null && entry.optBoolean("valid", false) != valid) continue
            if (expectedCode != null && entry.optString("expected_code") != expectedCode) continue
            return readPath(entry.getString("file"))
        }
        error("No protocol fixture for type=$type valid=$valid expectedCode=$expectedCode")
    }

    private fun readResource(path: String): String = checkNotNull(
        ProtocolFixtures::class.java.classLoader?.getResourceAsStream(path),
    ) { "Missing committed protocol fixture $path" }.bufferedReader().use { it.readText() }
}
