package co.twinotify.core.filter

import android.content.Context
import androidx.annotation.VisibleForTesting
import org.json.JSONObject
import java.security.MessageDigest

object DenylistLoader {
    // SHA-256 of default-denylist.json bytes — recompute after any edit.
    // File ends with a single LF newline (Git convention). Tampered APK = integrity
    // check fails = module init aborts with SecurityException.
    private const val EXPECTED_SHA256_HEX =
        "811df74b9fc5f64090339f0dc90435cab2eb203f3457229e9174ab38eb18945b"

    // Derived at class init; EXPECTED_SHA256_HEX stays the authoritative source.
    private val EXPECTED_SHA256_BYTES: ByteArray = run {
        EXPECTED_SHA256_HEX.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    @Volatile private var cached: Set<String>? = null

    fun load(ctx: Context): Set<String> = cached ?: synchronized(this) {
        cached ?: run {
            val bytes = ctx.assets.open("default-denylist.json").use { it.readBytes() }
            parseAndVerify(bytes).also { cached = it }
        }
    }

    fun contains(pkg: String, ctx: Context): Boolean = load(ctx).contains(pkg)

    // Exposed for testing: verifies the SHA-256 and parses the denylist.
    // Throws SecurityException on mismatch.
    @VisibleForTesting
    internal fun parseAndVerify(bytes: ByteArray): Set<String> {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        if (!MessageDigest.isEqual(digest, EXPECTED_SHA256_BYTES)) {
            throw SecurityException(
                "denylist integrity check failed (expected $EXPECTED_SHA256_HEX)"
            )
        }
        val obj = JSONObject(bytes.toString(Charsets.UTF_8))
        val arr = obj.getJSONArray("packages")
        return buildSet {
            for (i in 0 until arr.length()) add(arr.getString(i))
        }
    }
}
