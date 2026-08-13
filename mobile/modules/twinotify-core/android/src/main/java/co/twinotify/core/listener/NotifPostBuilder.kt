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
import java.io.ByteArrayOutputStream

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
            return NotifPostJson(
                v = o.optInt("v", 1),
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
            )
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
        )
    }

    /** Builds one canonical payload from an immutable callback snapshot. */
    fun build(
        snapshot: SourceNotificationSnapshot,
        ctx: Context,
        originDevice: String,
        eventType: String,
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
    }.toString()

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
}
