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
        val d = LocalDensity.current
        val quality = if (settings.smoothScaling) FilterQuality.Low else FilterQuality.None
        Canvas(
            modifier = with(d) { Modifier.size(g.dstW.toDp(), g.dstH.toDp()) }.then(effect),
        ) {
            drawImage(
                image = image,
                srcOffset = IntOffset(g.srcX, g.srcY),
                srcSize = IntSize(g.srcW, g.srcH),
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                filterQuality = quality,
            )
        }
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
