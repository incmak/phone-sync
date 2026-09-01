package co.twinotify.core.history

import android.content.Context
import co.twinotify.core.crypto.Sealed
import co.twinotify.core.crypto.WrappedKeys
import co.twinotify.core.listener.NotifPostJson
import co.twinotify.core.storage.NotificationDb
import co.twinotify.core.storage.UiActivityContent
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class HistoryContent(
    val title: String?,
    val preview: String?,
)

data class HistoryItem(
    val packageName: String?,
    val appName: String?,
    val appGroupId: String?,
    val direction: String,
    val kind: String,
    val status: String,
    val route: String?,
    val occurredAt: Long,
    val title: String?,
    val preview: String?,
)

data class HistorySettings(
    val contentEnabled: Boolean,
    val retentionDays: Int,
    val maxRows: Int = 500,
    val maxContentBytes: Long = 2L * 1024L * 1024L,
)

object HistoryContentCodec {
    private const val MAX_TITLE_CODE_POINTS = 120
    private const val MAX_PREVIEW_CODE_POINTS = 240

    fun fromNotificationPayload(payloadJson: String): HistoryContent? {
        val post = runCatching { NotifPostJson.fromPayloadJson(payloadJson) }.getOrNull() ?: return null
        val title = bounded(post.title ?: post.conversation?.title, MAX_TITLE_CODE_POINTS)
        val preview = bounded(
            post.conversation?.messages?.lastOrNull()?.text
                ?: post.big_text
                ?: post.text
                ?: post.sub_text,
            MAX_PREVIEW_CODE_POINTS,
        )
        return if (title == null && preview == null) null else HistoryContent(title, preview)
    }

    fun encode(content: HistoryContent): ByteArray = JSONObject().apply {
        put("v", 1)
        put("title", content.title ?: JSONObject.NULL)
        put("preview", content.preview ?: JSONObject.NULL)
    }.toString().toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): HistoryContent? = runCatching {
        val value = JSONObject(bytes.toString(Charsets.UTF_8))
        require(value.getInt("v") == 1)
        HistoryContent(
            title = value.optNullableString("title"),
            preview = value.optNullableString("preview"),
        )
    }.getOrNull()

    private fun bounded(value: String?, maxCodePoints: Int): String? {
        val trimmed = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val count = trimmed.codePointCount(0, trimmed.length)
        if (count <= maxCodePoints) return trimmed
        return trimmed.substring(0, trimmed.offsetByCodePoints(0, maxCodePoints))
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key).trim().takeIf(String::isNotEmpty)
}

fun historyAppGroupId(packageName: String): String = MessageDigest.getInstance("SHA-256")
    .digest(packageName.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

fun interface HistoryContentSealer {
    fun seal(plaintext: ByteArray): Sealed
}

fun interface HistoryContentUnsealer {
    fun unseal(sealed: Sealed): ByteArray
}

fun interface HistoryContentRecorder {
    suspend fun record(eventId: String, payloadJson: String, now: Long): Boolean
}

class HistoryRepository(
    context: Context,
    private val sealer: HistoryContentSealer = HistoryContentSealer(WrappedKeys::seal),
    private val unsealer: HistoryContentUnsealer = HistoryContentUnsealer(WrappedKeys::unseal),
) : HistoryContentRecorder {
    private val dao = NotificationDb.get(context.applicationContext).reliableDeliveryDao()

    override suspend fun record(eventId: String, payloadJson: String, now: Long) = withContext(Dispatchers.IO) {
        val content = HistoryContentCodec.fromNotificationPayload(payloadJson) ?: return@withContext false
        val sealed = sealer.seal(HistoryContentCodec.encode(content))
        dao.retainUiHistoryContent(
            UiActivityContent(
                eventId = eventId,
                ciphertext = sealed.ciphertext,
                iv = sealed.iv,
                byteSize = (sealed.ciphertext.size + sealed.iv.size).toLong(),
                createdAt = now,
            ),
        )
    }

    suspend fun recent(limit: Int, now: Long = System.currentTimeMillis()): List<HistoryItem> =
        withContext(Dispatchers.IO) {
            dao.maintainUiHistory(now)
            dao.uiHistoryRows(limit.coerceIn(1, 500)).map { row ->
                val content = if (row.contentCiphertext != null && row.contentIv != null) {
                    runCatching {
                        HistoryContentCodec.decode(
                            unsealer.unseal(Sealed(row.contentCiphertext, row.contentIv)),
                        )
                    }.getOrNull()
                } else {
                    null
                }
                HistoryItem(
                    packageName = row.packageName,
                    appName = row.appName,
                    appGroupId = row.packageName?.let(::historyAppGroupId),
                    direction = row.direction,
                    kind = row.kind,
                    status = row.status,
                    route = row.route,
                    occurredAt = row.occurredAt,
                    title = content?.title,
                    preview = content?.preview,
                )
            }
        }

    suspend fun settings(): HistorySettings = withContext(Dispatchers.IO) {
        val policy = dao.uiHistoryPolicy()
        HistorySettings(
            contentEnabled = policy?.contentEnabled ?: true,
            retentionDays = policy?.retentionDays ?: 30,
        )
    }

    suspend fun setContentEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        dao.setUiHistoryContentEnabled(enabled)
    }

    suspend fun setRetentionDays(days: Int, now: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        dao.setUiHistoryRetentionDays(days, now)
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) { dao.clearUiHistory() }

    suspend fun clearApp(appGroupId: String): Boolean = withContext(Dispatchers.IO) {
        val packageName = dao.uiHistoryPackages().singleOrNull { historyAppGroupId(it) == appGroupId }
            ?: return@withContext false
        dao.clearUiHistoryForPackage(packageName)
        true
    }
}
