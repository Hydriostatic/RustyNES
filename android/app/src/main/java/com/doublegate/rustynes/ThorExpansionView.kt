package com.doublegate.rustynes

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * Draws an [Expansion] page on the bottom screen.
 *
 * Two page kinds, as in M64-DS:
 *  - `screen`: title + grid of tiles + resource bar + buttons, laid out in a
 *    1240 x 1080 reference space (the Thor's bottom panel) and scaled to fit.
 *  - `pixel_screen`: sprite layers on a small canvas of the game's own pixel
 *    size, enlarged by the biggest whole number that fits, without smoothing.
 *
 * The view only draws; [values] is replaced by the host about 4 times a second.
 */
@SuppressLint("ViewConstructor")
class ExpansionView(
    context: Context,
    private val onAction: (String) -> Unit,
    private val onBackToMenu: () -> Unit,
) : View(context) {

    var expansion: Expansion? = null
        set(value) {
            field = value
            page = if (value?.pixelScreen != null) "pixel" else "main"
            lastTransitionKey = null
            transitionStart = 0L
            invalidate()
        }

    /** "main" (tiles) or "pixel". */
    var page: String = "main"
        set(value) {
            field = value
            invalidate()
        }

    var values: ValueEvaluator? = null
        set(value) {
            field = value
            checkTransition()
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pixelPaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val hitAreas = ArrayList<Pair<RectF, String>>()
    private var scale = 1f
    private var offX = 0f
    private var offY = 0f

    // pixel_screen offscreen canvases (current + the one being replaced in a transition)
    private var pixelBitmap: Bitmap? = null
    private var previousBitmap: Bitmap? = null
    private var lastTransitionKey: String? = null
    private var transitionStart = 0L
    private var transitionOrder: IntArray = IntArray(0)

    // Taps on a panel's buttons act once the tap is confirmed as single; a double tap
    // anywhere, or holding a finger down for 3 seconds, goes back to the menu.
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val x = (e.x - offX) / scale
            val y = (e.y - offY) / scale
            hitAreas.lastOrNull { it.first.contains(x, y) }?.let { onAction(it.second) }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            onBackToMenu()
            return true
        }
    }).apply { setIsLongpressEnabled(false) }

    private val holdSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop * 2f
    private var holdX = 0f
    private var holdY = 0f
    private var holdBroken = true
    private val hold = Runnable {
        if (!holdBroken) {
            holdBroken = true
            onBackToMenu()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                holdBroken = false
                holdX = event.x
                holdY = event.y
                removeCallbacks(hold)
                postDelayed(hold, HOLD_MS)
            }
            MotionEvent.ACTION_MOVE ->
                if (kotlin.math.abs(event.x - holdX) > holdSlop || kotlin.math.abs(event.y - holdY) > holdSlop) {
                    holdBroken = true
                    removeCallbacks(hold)
                } else if (!holdBroken && event.eventTime - event.downTime >= HOLD_MS) {
                    // Also judged from the touch's own clock, in case the timer was late.
                    removeCallbacks(hold)
                    holdBroken = true
                    onBackToMenu()
                }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(hold)
                if (!holdBroken && event.eventTime - event.downTime >= HOLD_MS) {
                    holdBroken = true
                    onBackToMenu()
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                holdBroken = true
                removeCallbacks(hold)
            }
        }
        if (BuildConfig.DEBUG && event.actionMasked != MotionEvent.ACTION_MOVE) {
            android.util.Log.d("RustyNES-thor", "panel touch ${MotionEvent.actionToString(event.actionMasked)} held=${event.eventTime - event.downTime}ms")
        }
        return gestures.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        val exp = expansion
        hitAreas.clear()
        if (exp == null) {
            canvas.drawColor(GRAY)
            return
        }
        val v = values
        val px = exp.pixelScreen
        if (page == "pixel" && px != null) drawPixel(canvas, exp, px, v) else drawTiles(canvas, exp, v)
    }

    // ---------------------------------------------------------------- tiles

    private fun color(key: String, def: Int, exp: Expansion): Int =
        parseColor(exp.theme.optString(key), def)

    private fun drawTiles(canvas: Canvas, exp: Expansion, v: ValueEvaluator?) {
        val w = REF_W
        val h = REF_H
        scale = min(width / w, height / h)
        offX = (width - w * scale) / 2f
        offY = (height - h * scale) / 2f
        val bg = color("background", GRAY, exp)
        canvas.drawColor(bg)
        canvas.save()
        canvas.translate(offX, offY)
        canvas.scale(scale, scale)

        exp.image(exp.theme.optString("background_image"))?.let {
            canvas.drawBitmap(it, null, RectF(0f, 0f, w, h), bmpPaint)
        }
        val panel = color("panel", 0xFF3A3A3A.toInt(), exp)
        val text = color("text", Color.WHITE, exp)
        val label = color("label", 0xFFB8B8B8.toInt(), exp)
        val accent = color("accent", 0xFFFFC107.toInt(), exp)
        paint.typeface = exp.typeface ?: Typeface.DEFAULT_BOLD

        val sc = exp.screen ?: JSONObject()
        // Title sign
        val titleRect = RectF(40f, 24f, w - 40f, 164f)
        if (!drawPiece(canvas, exp, exp.theme.optJSONObject("sign_image"), titleRect)) {
            paint.color = panel
            canvas.drawRoundRect(titleRect, 28f, 28f, paint)
        }
        val title = v?.format(sc.optString("title", exp.game.ifEmpty { exp.name })) ?: exp.name
        drawTitle(canvas, title, titleRect, exp, text, accent)

        // Tiles
        val tiles = sc.optJSONArray("tiles") ?: JSONArray()
        val cols = max(1, sc.optInt("columns", 3))
        val rows = max(1, (tiles.length() + cols - 1) / cols)
        val bar = sc.optJSONObject("bar")
        val gridTop = 190f
        val gridBottom = if (bar != null) h - 300f else h - 150f
        val gap = 24f
        val tileW = (w - 80f - gap * (cols - 1)) / cols
        val tileH = min(320f, (gridBottom - gridTop - gap * (rows - 1)) / rows)
        val tilePiece = exp.theme.optJSONObject("tile_image")
        for (i in 0 until tiles.length()) {
            val t = tiles.optJSONObject(i) ?: continue
            val c = i % cols
            val r = i / cols
            val rect = RectF(40f + c * (tileW + gap), gridTop + r * (tileH + gap), 0f, 0f)
            rect.right = rect.left + tileW
            rect.bottom = rect.top + tileH
            if (!drawPiece(canvas, exp, tilePiece, rect)) {
                paint.color = panel
                canvas.drawRoundRect(rect, 24f, 24f, paint)
            }
            val pieceScale = pieceScale(exp, tilePiece, rect)
            val labelY = tilePiece?.optDouble("label_y", Double.NaN)?.takeIf { !it.isNaN() }
                ?.let { rect.top + it.toFloat() * pieceScale } ?: (rect.top + tileH * 0.17f)
            val valueY = tilePiece?.optDouble("value_y", Double.NaN)?.takeIf { !it.isNaN() }
                ?.let { rect.bottom - it.toFloat() * pieceScale } ?: (rect.bottom - tileH * 0.14f)
            val lbl = v?.format(t.optString("label")) ?: t.optString("label")
            paint.textAlign = Paint.Align.CENTER
            paint.color = label
            paint.textSize = fitText(lbl, tileW - 24f, 40f)
            canvas.drawText(lbl, rect.centerX(), labelY + paint.textSize * 0.35f, paint)
            exp.image(t.optString("icon"))?.let { icon ->
                val box = min(tileW * 0.5f, tileH * 0.42f)
                val dst = fitRect(icon, RectF(rect.centerX() - box / 2, rect.centerY() - box / 2 - tileH * 0.02f,
                    rect.centerX() + box / 2, rect.centerY() + box / 2 - tileH * 0.02f))
                canvas.drawBitmap(icon, null, dst, bmpPaint)
            }
            val value = if (v?.gameRunning == true) v.format(t.optString("value")) else "–"
            paint.color = text
            paint.textSize = fitText(value, tileW - 24f, 64f)
            canvas.drawText(value, rect.centerX(), valueY + paint.textSize * 0.35f, paint)
        }

        // Resource bar
        if (bar != null) {
            val barRect = RectF(40f, h - 280f, w - 40f, h - 150f)
            val barPiece = exp.theme.optJSONObject("bar_image")
            if (!drawPiece(canvas, exp, barPiece, barRect)) {
                paint.color = panel
                canvas.drawRoundRect(barRect, 24f, 24f, paint)
            }
            var x = barRect.left + 32f +
                (barPiece?.optDouble("content_left", 0.0)?.toFloat() ?: 0f) * pieceScale(exp, barPiece, barRect)
            val barLabel = bar.optString("label")
            paint.textAlign = Paint.Align.LEFT
            if (barLabel.isNotEmpty()) {
                paint.color = label
                paint.textSize = 44f
                canvas.drawText(barLabel, x, barRect.centerY() + 16f, paint)
                x += paint.measureText(barLabel) + 32f
            }
            val items = bar.optJSONArray("items") ?: JSONArray()
            val n = max(1, items.length())
            val slot = (barRect.right - 24f - x) / n
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                val sx = x + i * slot
                var tx = sx
                exp.image(it.optString("icon"))?.let { icon ->
                    val box = barRect.height() * 0.62f
                    val dst = fitRect(icon, RectF(sx, barRect.centerY() - box / 2, sx + box, barRect.centerY() + box / 2))
                    canvas.drawBitmap(icon, null, dst, bmpPaint)
                    tx = sx + box + 14f
                }
                val value = if (v?.gameRunning == true) v.format(it.optString("value")) else "–"
                paint.color = text
                paint.textSize = fitText(value, sx + slot - tx - 8f, 50f)
                canvas.drawText(value, tx, barRect.centerY() + paint.textSize * 0.35f, paint)
            }
        }

        // Buttons: Menu always; the pixel page when the expansion has one.
        val buttons = ArrayList<Pair<String, String>>()
        buttons += "Menu" to "menu"
        if (exp.pixelScreen != null) buttons += "Pixel page" to "screen:pixel"
        val bw = (w - 80f - 24f * (buttons.size - 1)) / buttons.size
        buttons.forEachIndexed { i, (txt, action) ->
            val r = RectF(40f + i * (bw + 24f), h - 124f, 40f + i * (bw + 24f) + bw, h - 28f)
            if (!drawPiece(canvas, exp, exp.theme.optJSONObject("button_image"), r)) {
                paint.color = panel
                canvas.drawRoundRect(r, 22f, 22f, paint)
            }
            paint.color = text
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 46f
            canvas.drawText(txt, r.centerX(), r.centerY() + 16f, paint)
            hitAreas += r to action
        }
        canvas.restore()
    }

    private fun drawTitle(canvas: Canvas, title: String, rect: RectF, exp: Expansion, text: Int, accent: Int) {
        val colors = (exp.theme.optJSONArray("title_colors") ?: JSONArray()).let { arr ->
            (0 until arr.length()).map { parseColor(arr.optString(it), text) }
        }
        val words = title.split(' ').filter { it.isNotEmpty() }
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = fitText(title, rect.width() - 60f, 76f)
        val total = paint.measureText(title)
        var x = rect.centerX() - total / 2
        val y = rect.centerY() + paint.textSize * 0.35f
        val space = paint.measureText(" ")
        words.forEachIndexed { i, word ->
            paint.color = when {
                colors.isEmpty() -> if (i == 0) accent else text
                i < colors.size -> colors[i]
                else -> colors.last()
            }
            canvas.drawText(word, x, y, paint)
            x += paint.measureText(word) + space
        }
    }

    // ---------------------------------------------------------------- pixel page

    private fun drawPixel(canvas: Canvas, exp: Expansion, px: JSONObject, v: ValueEvaluator?) {
        val size = px.optJSONArray("size")
        val pw = max(1, size?.optInt(0, 256) ?: 256)
        val ph = max(1, size?.optInt(1, 224) ?: 224)
        val fill = parseColor(px.optString("fill"), Color.BLACK)
        canvas.drawColor(fill)

        val bmp = pixelBitmap?.takeIf { it.width == pw && it.height == ph }
            ?: Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888).also { pixelBitmap = it }
        bmp.eraseColor(Color.TRANSPARENT)
        val c = Canvas(bmp)
        var animating = false
        val layers = px.optJSONArray("layers") ?: JSONArray()
        for (i in 0 until layers.length()) {
            val l = layers.optJSONObject(i) ?: continue
            if (drawLayer(c, exp, l, v)) animating = true
        }

        val k = max(1, min(width / pw, height / ph))
        scale = k.toFloat()
        offX = ((width - pw * k) / 2).toFloat()
        offY = ((height - ph * k) / 2).toFloat()
        val dst = RectF(offX, offY, offX + pw * k, offY + ph * k)

        // transition: the new page covers the previous one (fade or jigsaw)
        val tr = px.optJSONObject("transition")
        val prev = previousBitmap
        val ms = tr?.optLong("ms", 800L) ?: 0L
        val elapsed = SystemClock.uptimeMillis() - transitionStart
        if (tr != null && prev != null && elapsed < ms) {
            canvas.drawBitmap(prev, null, dst, pixelPaint)
            val p = elapsed.toFloat() / ms
            if (tr.optString("style") == "jigsaw") {
                val cols = max(1, tr.optInt("cols", 8))
                val rows = max(1, tr.optInt("rows", 7))
                val shown = (p * cols * rows).toInt()
                if (transitionOrder.size != cols * rows) transitionOrder = IntArray(cols * rows) { it }.also { it.shuffle() }
                for (j in 0 until min(shown, transitionOrder.size)) {
                    val cell = transitionOrder[j]
                    val cx = cell % cols
                    val cy = cell / cols
                    val src = Rect(cx * pw / cols, cy * ph / rows, (cx + 1) * pw / cols, (cy + 1) * ph / rows)
                    val d = RectF(offX + src.left * k, offY + src.top * k, offX + src.right * k, offY + src.bottom * k)
                    canvas.drawBitmap(bmp, src, d, pixelPaint)
                }
            } else {
                pixelPaint.alpha = (p * 255).toInt().coerceIn(0, 255)
                canvas.drawBitmap(bmp, null, dst, pixelPaint)
                pixelPaint.alpha = 255
            }
            animating = true
        } else {
            canvas.drawBitmap(bmp, null, dst, pixelPaint)
        }

        val buttons = px.optJSONArray("buttons") ?: JSONArray()
        for (i in 0 until buttons.length()) {
            val b = buttons.optJSONObject(i) ?: continue
            val x = b.optDouble("x", 0.0).toFloat()
            val y = b.optDouble("y", 0.0).toFloat()
            hitAreas += RectF(x, y, x + b.optDouble("w", 0.0).toFloat(), y + b.optDouble("h", 0.0).toFloat()) to
                b.optString("action", "menu")
        }
        if (animating) postInvalidateDelayed(33)
    }

    /** Draws one layer; returns true while it is animated. */
    private fun drawLayer(c: Canvas, exp: Expansion, l: JSONObject, v: ValueEvaluator?): Boolean {
        val running = v?.gameRunning == true
        val dynamic = l.has("repeat") || l.has("pick") || l.has("number") || l.has("show") || l.has("hide")
        if (dynamic && !running) return false
        if (l.has("show") && (v?.number(l.optString("show")) ?: 0L) == 0L) return false
        if (l.has("hide") && (v?.number(l.optString("hide")) ?: 0L) != 0L) return false
        val x = l.optDouble("x", 0.0).toFloat()
        val y = l.optDouble("y", 0.0).toFloat()
        val center = l.optString("anchor") == "center"
        fun put(b: Bitmap, px: Float, py: Float) {
            if (center) c.drawBitmap(b, px - b.width / 2f, py - b.height / 2f, pixelPaint)
            else c.drawBitmap(b, px, py, pixelPaint)
        }
        when {
            l.has("frames") -> {
                val len = max(1, l.optInt("length", 1))
                val fps = l.optDouble("fps", 15.0).coerceAtLeast(0.1)
                val f = ((SystemClock.uptimeMillis() * fps / 1000.0).toLong() + l.optInt("phase", 0)) % len
                exp.image(l.optString("frames").replace("{f}", f.toString()))?.let { put(it, x, y) }
                return true
            }
            l.has("repeat") -> {
                val b = exp.image(l.optString("repeat")) ?: return false
                var n = v?.number(l.optString("count"))?.toInt() ?: 0
                if (l.has("max")) n = min(n, l.optInt("max"))
                val dx = l.optDouble("dx", 0.0).toFloat()
                val dy = l.optDouble("dy", 0.0).toFloat()
                for (i in 0 until max(0, n)) put(b, x + dx * i, y + dy * i)
            }
            l.has("pick") -> {
                val key = ValueEvaluator.display(v?.get(l.optString("pick")))
                val imgs = l.optJSONObject("images") ?: return false
                val path = imgs.optString(key).ifEmpty { imgs.optString("default") }
                exp.image(path)?.let { put(it, x, y) }
            }
            l.has("number") -> {
                val raw = v?.get(l.optString("number"))
                var s = ValueEvaluator.display(raw)
                val pad = l.optInt("pad", 0)
                if (raw !is String && s.length < pad) s = s.padStart(pad, '0')
                val pattern = l.optString("glyphs")
                val glyphs = s.map { ch -> exp.image(pattern.replace("{c}", glyphName(ch))) }
                val adv = if (l.has("advance")) l.optDouble("advance").toFloat() else Float.NaN
                val total = glyphs.sumOf { g -> (if (adv.isNaN()) (g?.width ?: 0).toFloat() else adv).toDouble() }.toFloat()
                var gx = when (l.optString("align", "left")) {
                    "right" -> x - total
                    "center" -> x - total / 2
                    else -> x
                }
                for (g in glyphs) {
                    if (g != null) c.drawBitmap(g, gx, if (center) y - g.height / 2f else y, pixelPaint)
                    gx += if (adv.isNaN()) (g?.width ?: 0).toFloat() else adv
                }
            }
            l.has("image") -> exp.image(l.optString("image"))?.let { put(it, x, y) }
        }
        return false
    }

    private fun glyphName(ch: Char): String = when (ch) {
        '/' -> "slash"
        '-' -> "minus"
        ':' -> "colon"
        '.' -> "dot"
        '%' -> "percent"
        else -> ch.toString()
    }

    private fun checkTransition() {
        val exp = expansion ?: return
        val tr = exp.pixelScreen?.optJSONObject("transition") ?: return
        val v = values ?: return
        if (!v.gameRunning) return
        val key = ValueEvaluator.display(v.get(tr.optString("value")))
        val last = lastTransitionKey
        lastTransitionKey = key
        if (last != null && last != key && page == "pixel") {
            previousBitmap = pixelBitmap?.copy(Bitmap.Config.ARGB_8888, false)
            transitionStart = SystemClock.uptimeMillis()
            transitionOrder = IntArray(0)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun pieceScale(exp: Expansion, piece: JSONObject?, dst: RectF): Float {
        val img = exp.image(piece?.optString("image")) ?: return 1f
        // Corners keep their pixel size relative to the reference layout, capped so they fit.
        val ins = piece?.optJSONArray("insets")
        val minSide = min(dst.width(), dst.height())
        val maxInset = if (ins == null) 0 else (0 until ins.length()).maxOf { ins.optInt(it) }
        return if (maxInset * 2 > minSide && maxInset > 0) minSide / (maxInset * 2f) else 1f.also { img.width }
    }

    /** Nine-slice draw of `{"image", "insets": [l, t, r, b]}`; false when absent. */
    private fun drawPiece(canvas: Canvas, exp: Expansion, piece: JSONObject?, dst: RectF): Boolean {
        if (piece == null) return false
        val img = exp.image(piece.optString("image")) ?: return false
        val ins = piece.optJSONArray("insets")
        if (ins == null || ins.length() < 4) {
            canvas.drawBitmap(img, null, dst, bmpPaint)
            return true
        }
        val l = ins.optInt(0); val t = ins.optInt(1); val r = ins.optInt(2); val b = ins.optInt(3)
        val s = pieceScale(exp, piece, dst)
        val sx = intArrayOf(0, l, img.width - r, img.width)
        val sy = intArrayOf(0, t, img.height - b, img.height)
        val dx = floatArrayOf(dst.left, dst.left + l * s, dst.right - r * s, dst.right)
        val dy = floatArrayOf(dst.top, dst.top + t * s, dst.bottom - b * s, dst.bottom)
        for (i in 0 until 3) for (j in 0 until 3) {
            if (sx[i + 1] <= sx[i] || sy[j + 1] <= sy[j]) continue
            canvas.drawBitmap(img, Rect(sx[i], sy[j], sx[i + 1], sy[j + 1]),
                RectF(dx[i], dy[j], dx[i + 1], dy[j + 1]), bmpPaint)
        }
        return true
    }

    private fun fitRect(b: Bitmap, box: RectF): RectF {
        val s = min(box.width() / b.width, box.height() / b.height)
        val w = b.width * s
        val h = b.height * s
        return RectF(box.centerX() - w / 2, box.centerY() - h / 2, box.centerX() + w / 2, box.centerY() + h / 2)
    }

    private fun fitText(s: String, maxW: Float, size: Float): Float {
        paint.textSize = size
        val w = paint.measureText(s)
        return if (w <= maxW || w <= 0f) size else max(16f, size * maxW / w)
    }

    companion object {
        const val REF_W = 1240f

        /** How long a finger must stay down on a panel to open the menu. */
        const val HOLD_MS = 3000L
        const val REF_H = 1080f
        val GRAY = 0xFF2B2B2B.toInt()

        fun parseColor(s: String?, def: Int): Int =
            if (s.isNullOrEmpty()) def else runCatching { Color.parseColor(s) }.getOrDefault(def)
    }
}
