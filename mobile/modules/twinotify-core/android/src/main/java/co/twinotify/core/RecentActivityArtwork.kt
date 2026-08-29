package co.twinotify.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.util.Base64
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream

internal fun sourceAppArtworkDataUri(context: Context, packageName: String?): String? {
    if (packageName.isNullOrBlank()) return null
    return runCatching {
        val drawable = context.packageManager.getApplicationIcon(packageName)
        val size = (40 * context.resources.displayMetrics.density).toInt().coerceAtLeast(40)
        val bitmap: Bitmap = when (drawable) {
            is BitmapDrawable -> drawable.bitmap.scale(size, size)
            else -> createBitmap(size, size).also { target ->
                drawable.setBounds(0, 0, size, size)
                drawable.draw(Canvas(target))
            }
        }
        val bytes = ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.toByteArray()
        }
        "data:image/png;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
    }.getOrNull()
}
