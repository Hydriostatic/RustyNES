package com.doublegate.rustynes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Picture shape. `par` is the width of one NES pixel relative to its height.
 *  - [Pixel87]: the NES's own 8:7 pixel aspect (what the app always used).
 *  - [Tv43]: the full 256x240 frame shown at exactly 4:3, like a CRT TV.
 *  - [Square]: 1:1 square pixels (narrowest, sharpest with integer scaling).
 *  - [Stretch]: fill the whole screen, ignoring aspect.
 */
enum class AspectMode(val label: String, val par: Float) {
    Pixel87("8:7 (NES pixels)", 8f / 7f),
    Tv43("4:3 (TV)", (4f / 3f) / (256f / 240f)),
    Square("1:1 (square)", 1f),
    Stretch("Stretch", 0f),
}

/** How much of the frame edge to hide (most games draw junk in the outer 8 pixels). */
enum class OverscanCrop(val label: String, val x: Int, val y: Int) {
    None("None", 0, 0),
    Vertical("Top/bottom 8 px", 0, 8),
    All("All sides 8 px", 8, 8),
}

/** Where the cropped source goes on screen, in pixels. */
data class PictureGeometry(val srcX: Int, val srcY: Int, val srcW: Int, val srcH: Int, val dstW: Int, val dstH: Int)

/**
 * Fit a [frameW] x [frameH] frame into [maxW] x [maxH] pixels with the given
 * aspect, crop and integer-scaling choices. Integer scaling uses the largest
 * whole vertical factor that fits (each NES line is exactly k screen lines); the
 * width follows the aspect, so 8:7 at 4x on a 1080p screen is 1170 x 960.
 */
fun pictureGeometry(
    maxW: Int,
    maxH: Int,
    frameW: Int,
    frameH: Int,
    aspect: AspectMode,
    integer: Boolean,
    crop: OverscanCrop,
): PictureGeometry {
    // Crop scales with the frame (HD frames are larger than 256x240).
    val kx = frameW / 256f
    val ky = frameH / 240f
    val cx = (crop.x * kx).roundToInt().coerceIn(0, frameW / 4)
    val cy = (crop.y * ky).roundToInt().coerceIn(0, frameH / 4)
    val sw = max(1, frameW - 2 * cx)
    val sh = max(1, frameH - 2 * cy)
    if (maxW <= 0 || maxH <= 0) return PictureGeometry(cx, cy, sw, sh, 0, 0)
    if (aspect == AspectMode.Stretch) return PictureGeometry(cx, cy, sw, sh, maxW, maxH)
    // Pixel aspect is defined on the 256x240 grid; HD frames keep the same shape.
    val par = aspect.par * (ky / kx)
    val ratio = sw * par / sh
    var dw: Int
    var dh: Int
    if (integer) {
        // Lines per NES line, as large as fits both ways (at least 1).
        var k = max(1f, floor(maxH / (sh / ky)))
        while (k > 1f && (sw / kx) * k * aspect.par > maxW) k -= 1f
        dh = ((sh / ky) * k).roundToInt()
        dw = ((sw / kx) * k * aspect.par).roundToInt()
        if (dw > maxW || dh > maxH) {
            // Screen smaller than 1x: fall back to plain fit.
            dh = min(maxH, (maxW / ratio).roundToInt())
            dw = (dh * ratio).roundToInt()
        }
    } else {
        dh = maxH
        dw = (maxH * ratio).roundToInt()
        if (dw > maxW) {
            dw = maxW
            dh = (maxW / ratio).roundToInt()
        }
    }
    return PictureGeometry(cx, cy, sw, sh, dw, dh)
}

/** Highest picture zoom, as a factor (200%). */
const val MAX_ZOOM = 2f

/** The zoom to draw at: the "fill screen" factor, or the chosen percentage. */
fun zoomFactor(settings: AppSettings, maxW: Int, maxH: Int, g: PictureGeometry): Float =
    if (settings.zoomFill && g.dstW > 0 && g.dstH > 0) {
        max(maxW.toFloat() / g.dstW, maxH.toFloat() / g.dstH).coerceIn(1f, MAX_ZOOM)
    } else {
        (settings.zoomPercent / 100f).coerceIn(1f, MAX_ZOOM)
    }

/**
 * [g] enlarged by [zoom] and cut to the [maxW] x [maxH] area, centred: the
 * on-screen size is the part that fits, and the source rectangle shrinks by the
 * same proportion, so pixels keep the chosen aspect at the larger size.
 */
fun zoomedGeometry(g: PictureGeometry, zoom: Float, maxW: Int, maxH: Int): PictureGeometry {
    if (zoom <= 1f) return g
    val fullW = g.dstW * zoom
    val fullH = g.dstH * zoom
    val visW = min(fullW, maxW.toFloat())
    val visH = min(fullH, maxH.toFloat())
    val sw = max(1, (g.srcW * visW / fullW).roundToInt())
    val sh = max(1, (g.srcH * visH / fullH).roundToInt())
    return PictureGeometry(
        g.srcX + (g.srcW - sw) / 2,
        g.srcY + (g.srcH - sh) / 2,
        sw,
        sh,
        visW.roundToInt(),
        visH.roundToInt(),
    )
}

/**
 * The NES picture on the default (Bitmap) renderer, drawn with the user's display
 * settings. [effect] carries the optional AGSL filter layer and size reporting.
 */
@Composable
fun NesPicture(
    image: ImageBitmap,
    settings: AppSettings,
    effect: Modifier,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val g = pictureGeometry(
            constraints.maxWidth,
            constraints.maxHeight,
            image.width,
            image.height,
            settings.aspectMode,
            settings.integerScale,
            settings.overscan,
        )
        if (g.dstW <= 0 || g.dstH <= 0) return@BoxWithConstraints
        // Zoom (100-200%): enlarge the fitted picture and keep the centre part that
        // fits the screen. "Fill screen" picks the zoom that covers the whole area.
        val z = zoomFactor(settings, constraints.maxWidth, constraints.maxHeight, g)
        val v = zoomedGeometry(g, z, constraints.maxWidth, constraints.maxHeight)
        val d = LocalDensity.current
        val mode = settings.scaleMode
        val tv = settings.tvBlend
        // Android 13+ draws through a GPU shader: sharp-bilinear (each NES pixel is
        // enlarged by the largest whole factor, and only the leftover fraction is
        // blended, so every pixel comes out the same size and nothing shimmers while
        // scrolling at 4.5x on a 1080p screen) and the optional TV blend. Older
        // Android, or plain "Pixel" without the blend, uses the bitmap draw.
        val shader = androidx.compose.runtime.remember {
            if (android.os.Build.VERSION.SDK_INT >= 33) PictureShader() else null
        }
        val useShader = shader != null && (mode != ScaleMode.Pixel || tv)
        Canvas(
            modifier = with(d) { Modifier.size(v.dstW.toDp(), v.dstH.toDp()) }.then(effect),
        ) {
            if (useShader && shader != null && android.os.Build.VERSION.SDK_INT >= 33) {
                val kx = when (mode) {
                    ScaleMode.Sharp -> max(1f, floor(size.width / v.srcW))
                    ScaleMode.Smooth -> 1f
                    ScaleMode.Pixel -> 1000f
                }
                val ky = when (mode) {
                    ScaleMode.Sharp -> max(1f, floor(size.height / v.srcH))
                    ScaleMode.Smooth -> 1f
                    ScaleMode.Pixel -> 1000f
                }
                drawIntoCanvas { c ->
                    shader.draw(
                        c.nativeCanvas, image.asAndroidBitmap(), v,
                        size.width, size.height, kx, ky, tv,
                    )
                }
            } else {
                drawImage(
                    image = image,
                    srcOffset = IntOffset(v.srcX, v.srcY),
                    srcSize = IntSize(v.srcW, v.srcH),
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    filterQuality = if (mode == ScaleMode.Smooth) FilterQuality.Low else FilterQuality.None,
                )
            }
        }
    }
}

/** How the picture is enlarged. */
enum class ScaleMode(val label: String, val hint: String) {
    Sharp("Sharp", "Even, crisp pixels at any size; no shimmer while scrolling. Recommended."),
    Pixel("Pixel", "Raw nearest-neighbour: crisp, but uneven pixel sizes can shimmer when the screen scrolls."),
    Smooth("Smooth", "Soft bilinear scaling."),
}

/**
 * Sharp-bilinear + TV-blend scaler as an AGSL shader (Android 13+). Coordinates
 * are in NES source pixels: each output pixel maps to a source position, the
 * fractional part is squeezed toward the pixel centre by the integer prescale
 * (so only the last fraction of a pixel blends with its neighbour), and the
 * linear-filtered sample is read there. The TV blend averages the samples half a
 * pixel left and right ([1, 2, 1] / 4 horizontally), roughly what an NTSC
 * composite signal does to the 1-pixel dithering NES games drew for TVs.
 */
@androidx.annotation.RequiresApi(33)
private class PictureShader {
    private val shader = android.graphics.RuntimeShader(
        """
        uniform shader image;
        uniform float2 srcOrigin;
        uniform float2 srcSize;
        uniform float2 dstSize;
        uniform float2 prescale;
        uniform float blend;
        half4 sharp(float2 t) {
            float2 tf = floor(t);
            float2 f = t - tf;
            float2 region = 0.5 - 0.5 / prescale;
            float2 d = f - 0.5;
            float2 ff = (d - clamp(d, -region, region)) * prescale + 0.5;
            return image.eval(tf + ff);
        }
        half4 main(float2 p) {
            float2 t = srcOrigin + p * srcSize / dstSize;
            half4 c = sharp(t);
            if (blend > 0.5) {
                half4 l = sharp(t - float2(1.0, 0.0));
                half4 r = sharp(t + float2(1.0, 0.0));
                c = (l + 2.0 * c + r) * 0.25;
            }
            return half4(c.rgb, 1.0);
        }
        """,
    )
    private val paint = android.graphics.Paint()

    fun draw(
        canvas: android.graphics.Canvas,
        bitmap: android.graphics.Bitmap,
        g: PictureGeometry,
        w: Float,
        h: Float,
        kx: Float,
        ky: Float,
        tv: Boolean,
    ) {
        val bs = android.graphics.BitmapShader(
            bitmap,
            android.graphics.Shader.TileMode.CLAMP,
            android.graphics.Shader.TileMode.CLAMP,
        )
        bs.filterMode = android.graphics.BitmapShader.FILTER_MODE_LINEAR
        shader.setInputShader("image", bs)
        shader.setFloatUniform("srcOrigin", g.srcX.toFloat(), g.srcY.toFloat())
        shader.setFloatUniform("srcSize", g.srcW.toFloat(), g.srcH.toFloat())
        shader.setFloatUniform("dstSize", w, h)
        shader.setFloatUniform("prescale", kx, ky)
        shader.setFloatUniform("blend", if (tv) 1f else 0f)
        paint.shader = shader
        canvas.drawRect(0f, 0f, w, h, paint)
    }
}

/** The core's APU output-filter models (codes match `setAudioFilter`). */
enum class AudioFilterMode(val label: String, val code: Int) {
    Nes("NES (authentic)", 0),
    Famicom("Famicom (more bass)", 1),
    Clean("Clean (full range)", 2),
}

/**
 * Audio buffer size in NES frames. Bigger buffers ride out hiccups (no crackle
 * or stutter) at the cost of a little sound delay. Applies when a game starts.
 */
enum class AudioLatency(val label: String, val frames: Int) {
    Low("Low", 4),
    Normal("Normal", 6),
    High("High", 10),
}

/** Display names for the six mixer channels, in the core's order. */
val AUDIO_CHANNEL_NAMES = listOf("Pulse 1", "Pulse 2", "Triangle", "Noise", "DMC (samples)", "Cartridge audio")

// ---------------------------------------------------------------- AYN Thor: menus on the bottom screen

/**
 * Shows [content] (dialogs and sheets) on the bottom screen whenever it is open,
 * and in place otherwise. The content keeps reading the top screen's state; only
 * where it is drawn changes. Dialog and sheet windows belong to the activity whose
 * composition draws them, so drawing them from the bottom activity puts them on
 * that display.
 */
@Composable
fun ThorPortal(content: @Composable () -> Unit) {
    if (!ThorDualScreen.bottomActive.value) {
        content()
        return
    }
    val latest = androidx.compose.runtime.rememberUpdatedState(content)
    val key = androidx.compose.runtime.remember { Any() }
    val entry: @Composable () -> Unit = { latest.value() }
    androidx.compose.runtime.DisposableEffect(key) {
        ThorDualScreen.portals.add(key to entry)
        onDispose { ThorDualScreen.portals.removeAll { it.first === key } }
    }
}

/** Draws everything sent through [ThorPortal]; lives in the bottom activity. */
@Composable
fun ThorPortalHost() {
    val scheme = ThorDualScreen.colorScheme.value ?: androidx.compose.material3.darkColorScheme()
    androidx.compose.material3.MaterialTheme(colorScheme = scheme) {
        for ((k, c) in ThorDualScreen.portals.toList()) {
            androidx.compose.runtime.key(k) { c() }
        }
    }
}

/**
 * A result launcher that opens its picker from the bottom screen when it is open
 * (so file pickers appear there too) and from the top activity otherwise. Same
 * use as `rememberLauncherForActivityResult`.
 */
@Composable
fun <I, O> rememberThorLauncher(
    contract: androidx.activity.result.contract.ActivityResultContract<I, O>,
    onResult: (O) -> Unit,
): androidx.activity.result.ActivityResultLauncher<I> {
    val current = androidx.compose.runtime.rememberUpdatedState(onResult)
    val fallback = androidx.activity.compose.rememberLauncherForActivityResult(contract) { current.value(it) }
    return androidx.compose.runtime.remember(contract) {
        ThorLauncher(fallback, contract) { current.value(it) }
    }
}

class ThorLauncher<I, O>(
    private val fallback: androidx.activity.result.ActivityResultLauncher<I>,
    private val resultContract: androidx.activity.result.contract.ActivityResultContract<I, O>,
    private val onResult: (O) -> Unit,
) : androidx.activity.result.ActivityResultLauncher<I>() {
    override fun launch(input: I, options: androidx.core.app.ActivityOptionsCompat?) {
        val bottom = ThorDualScreen.bottom.get()
        if (bottom != null && ThorDualScreen.bottomActive.value && bottom.launchFor(resultContract, input, onResult)) return
        ThorDualScreen.pickerOpen = true
        runCatching { fallback.launch(input, options) }.onFailure { ThorDualScreen.pickerOpen = false; throw it }
    }

    override fun unregister() = fallback.unregister()

    override val contract: androidx.activity.result.contract.ActivityResultContract<I, *>
        get() = resultContract
}
