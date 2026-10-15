package com.doublegate.rustynes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * AYN Thor build: home-screen shortcuts that start one library game directly.
 * The shortcut carries `play:<sha>` as the deep-link action, which MainActivity
 * resolves through the library (so it keeps working if the file is re-linked).
 * The icon is the game's box art when it has some, otherwise its initials.
 */
object GameShortcuts {
    fun pin(context: Context, entry: GameEntry) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            Toast.makeText(context, "This launcher can't add shortcuts", Toast.LENGTH_LONG).show()
            return
        }
        val title = gameTitle(entry.name)
        val intent = DeepLink.intent(context, DeepLink.ACTION_PLAY_PREFIX + entry.sha)
        val info = ShortcutInfoCompat.Builder(context, "game-${entry.sha.take(24)}")
            .setShortLabel(title.take(24).ifEmpty { "NES game" })
            .setLongLabel(title.take(60).ifEmpty { "NES game" })
            .setIcon(IconCompat.createWithAdaptiveBitmap(icon(context, entry, title)))
            .setIntent(intent)
            .build()
        val ok = runCatching { ShortcutManagerCompat.requestPinShortcut(context, info, null) }.getOrDefault(false)
        if (!ok) Toast.makeText(context, "Couldn't add the shortcut", Toast.LENGTH_LONG).show()
    }

    /** A 432x432 adaptive-icon bitmap (the centre 288 px is the visible safe zone). */
    private fun icon(context: Context, entry: GameEntry, title: String): Bitmap {
        val size = 432
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val art = entry.boxArtUri.takeIf { it.isNotEmpty() }?.let { u ->
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(u))?.use { BitmapFactory.decodeStream(it) }
            }.getOrNull() ?: runCatching { BitmapFactory.decodeFile(Uri.parse(u).path) }.getOrNull()
        }
        if (art != null) {
            // Cover the whole square with the art, centred (the launcher masks it).
            val s = maxOf(size.toFloat() / art.width, size.toFloat() / art.height)
            val w = art.width * s
            val h = art.height * s
            c.drawColor(Color.BLACK)
            c.drawBitmap(
                art, null,
                android.graphics.RectF((size - w) / 2, (size - h) / 2, (size + w) / 2, (size + h) / 2),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
        } else {
            val hue = (title.hashCode() and 0x7fffffff) % 360
            c.drawColor(Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.55f, 0.45f)))
            val initials = title.split(' ', ',', '-').filter { it.isNotBlank() && it[0].isLetterOrDigit() }
                .take(2).joinToString("") { it[0].uppercase() }.ifEmpty { "NES" }
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 120f
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            c.drawText(initials, size / 2f, size / 2f + 42f, p)
        }
        return out
    }
}
