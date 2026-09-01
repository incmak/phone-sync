package co.twinotify.core.listener

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.service.notification.StatusBarNotification
import android.util.Base64
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import co.twinotify.core.actions.ActionCandidate
import java.io.ByteArrayOutputStream
import java.util.UUID

private const val MAX_CONVERSATION_MESSAGES = 25
private const val MAX_MESSAGE_TEXT_CODE_POINTS = 4_096
private const val MAX_SENDER_NAME_CODE_POINTS = 256
private const val MAX_SENDER_KEY_CODE_POINTS = 256
private const val MAX_CONVERSATION_KEY_CODE_POINTS = 512
private const val MAX_CONVERSATION_TITLE_CODE_POINTS = 256

data class NotifActionJson(
    val action_id: String,
    val title: String,
    val semantic: Int,
    val reply: Boolean,
    val reply_label: String?,
)

data class NotifMessageJson(
    val text: String,
    val timestamp: Long,
    val sender_name: String?,
    val sender_key: String?,
)

data class NotifConversationJson(
    val key: String?,
    val title: String?,
    val is_group: Boolean,
    val messages: List<NotifMessageJson>,
)

data class NotifPostJson(
    val v: Int = 1,
    val type: String,                      // "notif.post" | "notif.update"
    val canon_id: String,
    val app_name: String?,
    val package_name: String,
    val id: Int,
    val tag: String?,
    val title: String?,
    val text: String?,
    val sub_text: String?,
    val big_text: String?,
    val visibility: String,                // "public" | "private" | "secret"
    val is_group_summary: Boolean,
    val is_ongoing: Boolean,
    val is_clearable: Boolean,
    val small_icon_png_b64: String?,       // Phase 3: always inline; icon-hash-elide is Phase 7
    val large_icon_png_b64: String?,
    val ts: Long,
    val is_auto_cancel: Boolean = true,
    val actions: List<NotifActionJson> = emptyList(),
    val conversation: NotifConversationJson? = null,
) {
    companion object {
        fun fromPayloadJson(raw: String): NotifPostJson {
            val o = org.json.JSONObject(raw)
            fun nullable(key: String): String? =
                if (!o.has(key) || o.isNull(key)) null else o.getString(key)
            val type = o.getString("type")
            require(type == "notif.post" || type == "notif.update") {
                "notification payload type must be notif.post or notif.update"
            }
            val version = o.optInt("v", 1)
            require(version == 1) { "notification payload must use version 1" }
            val actions = if (!o.has("actions")) {
                emptyList()
            } else {
                val array = o.get("actions") as? org.json.JSONArray
                    ?: throw IllegalArgumentException("notification payload actions must be an array")
                require(array.length() <= 3) { "notification payload actions must contain at most 3 items" }
                List(array.length()) { index -> parseAction(array.get(index), index) }
            }
            val conversation = when {
                !o.has("conversation") || o.isNull("conversation") -> null
                else -> parseConversation(o.get("conversation"))
            }
            return NotifPostJson(
                v = version,
                type = type,
                canon_id = o.getString("canon_id"),
                app_name = nullable("app_name"),
                package_name = o.getString("package_name"),
                id = o.getInt("id"),
                tag = nullable("tag"),
                title = nullable("title"),
                text = nullable("text"),
                sub_text = nullable("sub_text"),
                big_text = nullable("big_text"),
                visibility = o.optString("visibility", "private"),
                is_group_summary = o.optBoolean("is_group_summary", false),
                is_ongoing = o.optBoolean("is_ongoing", false),
                is_clearable = o.optBoolean("is_clearable", true),
                small_icon_png_b64 = nullable("small_icon_png_b64"),
                large_icon_png_b64 = nullable("large_icon_png_b64"),
                ts = o.optLong("ts", 0L),
                is_auto_cancel = o.optBoolean("is_auto_cancel", true),
                actions = actions,
                conversation = conversation,
            )
        }

        private fun parseConversation(value: Any): NotifConversationJson {
            val conversation = value as? org.json.JSONObject
                ?: throw IllegalArgumentException("notification payload conversation must be an object")
            val allowed = setOf("key", "title", "is_group", "messages")
            val unknown = conversation.keys().asSequence().filterNot(allowed::contains).toList()
            require(unknown.isEmpty()) {
                "notification payload conversation contains unknown fields: ${unknown.joinToString()}"
            }
            val messages = conversation.opt("messages") as? org.json.JSONArray
                ?: throw IllegalArgumentException("notification payload conversation messages must be an array")
            require(messages.length() in 1..MAX_CONVERSATION_MESSAGES) {
                "notification payload conversation must contain 1..$MAX_CONVERSATION_MESSAGES messages"
            }
            val isGroup = conversation.opt("is_group") as? Boolean
                ?: throw IllegalArgumentException("notification payload conversation is_group must be a boolean")
            return NotifConversationJson(
                key = optionalBoundedString(conversation, "key", MAX_CONVERSATION_KEY_CODE_POINTS),
                title = optionalBoundedString(conversation, "title", MAX_CONVERSATION_TITLE_CODE_POINTS),
                is_group = isGroup,
                messages = List(messages.length()) { index -> parseMessage(messages.get(index), index) },
            )
        }

        private fun parseMessage(value: Any, index: Int): NotifMessageJson {
            val message = value as? org.json.JSONObject
                ?: throw IllegalArgumentException("notification payload conversation message $index must be an object")
            val allowed = setOf("text", "timestamp", "sender_name", "sender_key")
            val unknown = message.keys().asSequence().filterNot(allowed::contains).toList()
            require(unknown.isEmpty()) {
                "notification payload conversation message $index contains unknown fields: ${unknown.joinToString()}"
            }
            val text = message.opt("text") as? String
                ?: throw IllegalArgumentException("notification payload conversation message $index text must be a string")
            require(text.isNotEmpty() && text.codePointCount(0, text.length) <= MAX_MESSAGE_TEXT_CODE_POINTS) {
                "notification payload conversation message $index text exceeds bounds"
            }
            val timestampNumber = message.opt("timestamp") as? Number
                ?: throw IllegalArgumentException("notification payload conversation message $index timestamp must be an integer")
            val timestamp = timestampNumber.toLong()
            require(timestamp >= 0 && timestampNumber.toDouble() == timestamp.toDouble()) {
                "notification payload conversation message $index timestamp must be a non-negative integer"
            }
            return NotifMessageJson(
                text = text,
                timestamp = timestamp,
                sender_name = optionalBoundedString(message, "sender_name", MAX_SENDER_NAME_CODE_POINTS),
                sender_key = optionalBoundedString(message, "sender_key", MAX_SENDER_KEY_CODE_POINTS),
            )
        }

        private fun optionalBoundedString(
            objectValue: org.json.JSONObject,
            key: String,
            maxCodePoints: Int,
        ): String? {
            if (!objectValue.has(key) || objectValue.isNull(key)) return null
            val value = objectValue.get(key) as? String
                ?: throw IllegalArgumentException("notification payload $key must be a string or null")
            require(value.codePointCount(0, value.length) <= maxCodePoints) {
                "notification payload $key exceeds bounds"
            }
            return value.takeIf(String::isNotBlank)
        }

        private fun parseAction(value: Any, index: Int): NotifActionJson {
            val action = value as? org.json.JSONObject
                ?: throw IllegalArgumentException("notification payload action $index must be an object")
            val allowed = setOf("action_id", "title", "semantic", "reply", "reply_label")
            val unknown = action.keys().asSequence().filterNot(allowed::contains).toList()
            require(unknown.isEmpty()) {
                "notification payload action $index contains unknown fields: ${unknown.joinToString()}"
            }
            val actionId = action.get("action_id") as? String
                ?: throw IllegalArgumentException("notification payload action $index action_id must be a string")
            val parsedUuid = runCatching { UUID.fromString(actionId) }.getOrElse {
                throw IllegalArgumentException("notification payload action $index action_id must be a UUID", it)
            }
            require(parsedUuid.toString().equals(actionId, ignoreCase = true)) {
                "notification payload action $index action_id must be a canonical UUID"
            }
            val title = action.get("title") as? String
                ?: throw IllegalArgumentException("notification payload action $index title must be a string")
            require(title.isNotEmpty() && title.codePointCount(0, title.length) <= 64) {
                "notification payload action $index title must be 1..64 characters"
            }
            val semanticNumber = action.get("semantic") as? Number
                ?: throw IllegalArgumentException("notification payload action $index semantic must be an integer")
            val semantic = semanticNumber.toInt()
            require(semanticNumber.toDouble() == semantic.toDouble() && semantic in 0..12) {
                "notification payload action $index semantic must be an integer from 0 to 12"
            }
            val reply = action.get("reply") as? Boolean
                ?: throw IllegalArgumentException("notification payload action $index reply must be a boolean")
            val replyLabel = when {
                !action.has("reply_label") || action.isNull("reply_label") -> null
                else -> action.get("reply_label") as? String
                    ?: throw IllegalArgumentException(
                        "notification payload action $index reply_label must be a string or null",
                    )
            }
            replyLabel?.let {
                require(it.codePointCount(0, it.length) <= 64) {
                    "notification payload action $index reply_label must be at most 64 characters"
                }
            }
            return NotifActionJson(actionId, title, semantic, reply, replyLabel)
        }
    }
}

object NotifPostBuilder {
    private const val SMALL_ICON_PX = 96
    private const val LARGE_ICON_PX = 256
    private const val ANDROID_AUTO_PKG = "com.google.android.projection.gearhead"
    // String values match Notification.CATEGORY_CAR_EMERGENCY/_INFORMATION/_WARNING (API 21+).
    // Literals used so we don't depend on SDK-stub resolution in non-device build environments;
    // the Android framework treats these strings as the stable contract for notif.category.
    private val CAR_CATEGORIES = setOf("car_emergency", "car_information", "car_warning")

    /**
     * Copies all callback-owned fields before any asynchronous work. Icon handles are retained as
     * immutable framework handles; bitmap decoding/compression is deliberately deferred to [build].
     */
    fun captureSnapshot(
        sbn: StatusBarNotification,
        ctx: Context,
        denylist: Set<String>,
    ): SourceNotificationSnapshot? {
        val notif = sbn.notification
        val pkg = sbn.packageName

        if (notif.visibility == Notification.VISIBILITY_SECRET) return null
        if ((notif.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return null
        if (pkg == ANDROID_AUTO_PKG) return null
        if (notif.category in CAR_CATEGORIES) return null
        if (pkg in denylist) return null

        val extras = notif.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()?.takeIf { it.isNotBlank() }
            ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.takeIf { it.isNotBlank() }
            ?: pkg
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
            ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.lastOrNull()?.toString()?.takeIf { it.isNotBlank() }
            ?: extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.let { msgs ->
                (msgs.lastOrNull() as? android.os.Bundle)?.getCharSequence("text")?.toString()
                    ?.takeIf { it.isNotBlank() }
            }
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.takeIf { it.isNotBlank() }
            ?: extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()?.takeIf { it.isNotBlank() }
            ?: extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()?.takeIf { it.isNotBlank() }

        val actions = notif.actions.orEmpty().mapNotNull { action ->
            if (action.actionIntent == null) return@mapNotNull null
            val actionTitle = action.title?.toString()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val remoteInput = action.remoteInputs?.firstOrNull { it.allowFreeFormInput }
            ActionCandidate(
                title = actionTitle.truncateCodePoints(64),
                semantic = action.semanticAction.coerceIn(0, 12),
                reply = remoteInput != null,
                replyLabel = remoteInput?.label?.toString()?.truncateCodePoints(64),
                handle = action,
            )
        }.take(3)

        return SourceNotificationSnapshot(
            sourceKey = sbn.key,
            packageName = pkg,
            id = sbn.id,
            tag = sbn.tag,
            postTime = sbn.postTime,
            flags = notif.flags,
            category = notif.category,
            visibility = notif.visibility,
            isGroupSummary = (notif.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
            isOngoing = (notif.flags and Notification.FLAG_ONGOING_EVENT) != 0,
            isClearable = sbn.isClearable,
            appName = pkg,
            title = title,
            text = text,
            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            smallIcon = notif.smallIcon,
            largeIcon = notif.getLargeIcon(),
            isAutoCancel = (notif.flags and Notification.FLAG_AUTO_CANCEL) != 0,
            actions = actions,
            conversation = captureConversation(notif),
        )
    }

    /** Builds one canonical payload from an immutable callback snapshot. */
    fun build(
        snapshot: SourceNotificationSnapshot,
        ctx: Context,
        originDevice: String,
        eventType: String,
        actionDescriptors: List<NotifActionJson> = emptyList(),
    ): NotifPostJson {
        require(eventType == "notif.post" || eventType == "notif.update") {
            "notification event type must be notif.post or notif.update"
        }
        return NotifPostJson(
            type = eventType,
            canon_id = CanonIdBuilder.build(originDevice, snapshot.packageName, snapshot.id, snapshot.tag),
            app_name = snapshot.appName,
            package_name = snapshot.packageName,
            id = snapshot.id,
            tag = snapshot.tag,
            title = snapshot.title,
            text = snapshot.text,
            sub_text = snapshot.subText,
            big_text = snapshot.bigText,
            visibility = visibilityString(snapshot.visibility),
            is_group_summary = snapshot.isGroupSummary,
            is_ongoing = snapshot.isOngoing,
            is_clearable = snapshot.isClearable,
            small_icon_png_b64 = snapshot.smallIconPngB64
                ?: drawableToPngB64(snapshot.smallIcon?.loadDrawable(ctx), SMALL_ICON_PX),
            large_icon_png_b64 = snapshot.largeIconPngB64
                ?: drawableToPngB64(snapshot.largeIcon?.loadDrawable(ctx), LARGE_ICON_PX),
            ts = snapshot.postTime,
            is_auto_cancel = snapshot.isAutoCancel,
            actions = actionDescriptors,
            conversation = snapshot.conversation?.let { conversation ->
                NotifConversationJson(
                    key = conversation.key,
                    title = conversation.title,
                    is_group = conversation.isGroup,
                    messages = conversation.messages.map { message ->
                        NotifMessageJson(
                            text = message.text,
                            timestamp = message.timestamp,
                            sender_name = message.senderName,
                            sender_key = message.senderKey,
                        )
                    },
                )
            },
        )
    }

    /**
     * Compatibility adapter for callers that still hold a framework callback. New code must
     * capture first, then submit a [PostCommand] to [CaptureCoordinator].
     */
    fun build(
        sbn: StatusBarNotification,
        ctx: Context,
        originDevice: String,
        denylist: Set<String>,
        isUpdate: Boolean = false,
    ): NotifPostJson? = captureSnapshot(sbn, ctx, denylist)?.let {
        build(it, ctx, originDevice, if (isUpdate) "notif.update" else "notif.post")
    }

    fun toPayloadJson(post: NotifPostJson): String = org.json.JSONObject().apply {
        put("v", post.v)
        put("type", post.type)
        put("canon_id", post.canon_id)
        put("app_name", post.app_name ?: org.json.JSONObject.NULL)
        put("package_name", post.package_name)
        put("id", post.id)
        put("tag", post.tag ?: org.json.JSONObject.NULL)
        put("title", post.title ?: org.json.JSONObject.NULL)
        put("text", post.text ?: org.json.JSONObject.NULL)
        put("sub_text", post.sub_text ?: org.json.JSONObject.NULL)
        put("big_text", post.big_text ?: org.json.JSONObject.NULL)
        put("visibility", post.visibility)
        put("is_group_summary", post.is_group_summary)
        put("is_ongoing", post.is_ongoing)
        put("is_clearable", post.is_clearable)
        put("small_icon_png_b64", post.small_icon_png_b64 ?: org.json.JSONObject.NULL)
        put("large_icon_png_b64", post.large_icon_png_b64 ?: org.json.JSONObject.NULL)
        put("ts", post.ts)
        put("is_auto_cancel", post.is_auto_cancel)
        put("actions", org.json.JSONArray().apply {
            post.actions.forEach { action ->
                put(org.json.JSONObject().apply {
                    put("action_id", action.action_id)
                    put("title", action.title)
                    put("semantic", action.semantic)
                    put("reply", action.reply)
                    action.reply_label?.let { put("reply_label", it) }
                })
            }
        })
        post.conversation?.let { conversation ->
            put("conversation", org.json.JSONObject().apply {
                put("key", conversation.key ?: org.json.JSONObject.NULL)
                put("title", conversation.title ?: org.json.JSONObject.NULL)
                put("is_group", conversation.is_group)
                put("messages", org.json.JSONArray().apply {
                    conversation.messages.forEach { message ->
                        put(org.json.JSONObject().apply {
                            put("text", message.text)
                            put("timestamp", message.timestamp)
                            put("sender_name", message.sender_name ?: org.json.JSONObject.NULL)
                            put("sender_key", message.sender_key ?: org.json.JSONObject.NULL)
                        })
                    }
                })
            })
        }
    }.toString()

    private fun captureConversation(notification: Notification): SourceConversationSnapshot? {
        val extras = notification.extras
        val historic = parseFrameworkMessages(
            extras.getParcelableArray(Notification.EXTRA_HISTORIC_MESSAGES),
        )
        val current = parseFrameworkMessages(extras.getParcelableArray(Notification.EXTRA_MESSAGES))
        val messages = (historic + current)
            .distinctBy { listOf(it.text, it.timestamp.toString(), it.senderName.orEmpty(), it.senderKey.orEmpty()) }
            .takeLast(MAX_CONVERSATION_MESSAGES)
        if (messages.isEmpty()) return null
        return SourceConversationSnapshot(
            key = notification.shortcutId?.truncateCodePoints(MAX_CONVERSATION_KEY_CODE_POINTS),
            title = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                ?.toString()
                ?.takeIf(String::isNotBlank)
                ?.truncateCodePoints(MAX_CONVERSATION_TITLE_CODE_POINTS),
            isGroup = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false),
            messages = messages,
        )
    }

    private fun parseFrameworkMessages(array: Array<android.os.Parcelable>?): List<SourceMessageSnapshot> =
        Notification.MessagingStyle.Message.getMessagesFromBundleArray(array)
            .orEmpty()
            .mapNotNull { message ->
                val text = message.text?.toString()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val sender = message.senderPerson
                SourceMessageSnapshot(
                    text = text.truncateCodePoints(MAX_MESSAGE_TEXT_CODE_POINTS),
                    timestamp = message.timestamp.coerceAtLeast(0L),
                    senderName = sender?.name?.toString()
                        ?.takeIf(String::isNotBlank)
                        ?.truncateCodePoints(MAX_SENDER_NAME_CODE_POINTS),
                    senderKey = sender?.key
                        ?.takeIf(String::isNotBlank)
                        ?.truncateCodePoints(MAX_SENDER_KEY_CODE_POINTS),
                )
            }

    private fun visibilityString(v: Int): String = when (v) {
        Notification.VISIBILITY_PUBLIC -> "public"
        Notification.VISIBILITY_PRIVATE -> "private"
        Notification.VISIBILITY_SECRET -> "secret"
        else -> "private"
    }

    private fun drawableToPngB64(d: Drawable?, px: Int): String? {
        if (d == null) return null
        val bm: Bitmap = when (d) {
            is BitmapDrawable -> {
                // Guard: createScaledBitmap may return the same reference when dimensions already match.
                // Always own a distinct bitmap so bm.recycle() never corrupts a framework-held bitmap.
                val scaled = d.bitmap.scale(px, px)
                if (scaled === d.bitmap) d.bitmap.copy(d.bitmap.config ?: Bitmap.Config.ARGB_8888, false) else scaled
            }
            else -> {
                val tmp = createBitmap(px, px)
                val c = android.graphics.Canvas(tmp)
                d.setBounds(0, 0, px, px)
                d.draw(c)
                tmp
            }
        }
        val stream = ByteArrayOutputStream()
        bm.compress(Bitmap.CompressFormat.PNG, 100, stream)
        bm.recycle()
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    private fun String.truncateCodePoints(maxCodePoints: Int): String {
        val count = codePointCount(0, length)
        if (count <= maxCodePoints) return this
        return substring(0, offsetByCodePoints(0, maxCodePoints))
    }

}
