package co.twinotify.core.listener

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.service.notification.StatusBarNotification
import android.util.Base64
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
)

object NotifPostBuilder {
    private const val SMALL_ICON_PX = 96
    private const val LARGE_ICON_PX = 256
    private const val ANDROID_AUTO_PKG = "com.google.android.projection.gearhead"
    private val CAR_CATEGORIES = setOf(
        Notification.CATEGORY_CAR_EMERGENCY,
        Notification.CATEGORY_CAR_INFORMATION,
        Notification.CATEGORY_CAR_WARNING,
    )

    /**
     * @return NotifPostJson, or null if privacy filter drops it.
     * @param originDevice this device's id (DeviceIdentity.getOrCreate(ctx)).
     * @param isUpdate if true, emits "notif.update"; else "notif.post".
     */
    fun build(
        sbn: StatusBarNotification,
        ctx: Context,
        originDevice: String,
        denylist: Set<String>,
        isUpdate: Boolean = false,
    ): NotifPostJson? {
        val notif = sbn.notification
        val pkg = sbn.packageName

        // Spec §4.7.3 privacy filters — hard drops
        if (notif.visibility == Notification.VISIBILITY_SECRET) return null
        if (pkg == ANDROID_AUTO_PKG) return null
        if (notif.category in CAR_CATEGORIES) return null
        if (pkg in denylist) return null

        val extras = notif.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val appName = pkg // Phase 3: use package name as display name; Phase 7+ loads real app label.

        val smallIconB64 = drawableToPngB64(notif.smallIcon?.loadDrawable(ctx), SMALL_ICON_PX)
        val largeIconB64 = drawableToPngB64(notif.getLargeIcon()?.loadDrawable(ctx), LARGE_ICON_PX)

        return NotifPostJson(
            type = if (isUpdate) "notif.update" else "notif.post",
            canon_id = CanonIdBuilder.build(originDevice, pkg, sbn.id, sbn.tag),
            app_name = appName,
            package_name = pkg,
            id = sbn.id,
            tag = sbn.tag,
            title = title,
            text = text,
            sub_text = subText,
            big_text = bigText,
            visibility = visibilityString(notif.visibility),
            is_group_summary = (notif.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
            is_ongoing = (notif.flags and Notification.FLAG_ONGOING_EVENT) != 0,
            is_clearable = sbn.isClearable,
            small_icon_png_b64 = smallIconB64,
            large_icon_png_b64 = largeIconB64,
            ts = sbn.postTime,
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
                val scaled = Bitmap.createScaledBitmap(d.bitmap, px, px, true)
                if (scaled === d.bitmap) d.bitmap.copy(d.bitmap.config ?: Bitmap.Config.ARGB_8888, false) else scaled
            }
            else -> {
                val tmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
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
