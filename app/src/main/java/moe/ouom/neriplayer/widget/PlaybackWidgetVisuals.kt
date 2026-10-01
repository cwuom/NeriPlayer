package moe.ouom.neriplayer.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import kotlin.math.roundToInt

internal data class PlaybackWidgetVisuals(
    val artwork: Bitmap?,
    val compactArtwork: Bitmap?,
    val primaryControl: Bitmap?,
    val backgroundColor: Int = 0xFFF4F7F4.toInt(),
    val textPrimary: Int = 0xFF1C2521.toInt(),
    val textSecondary: Int = 0xFF48534D.toInt(),
    val controlTint: Int = textPrimary,
    val primaryControlTint: Int = 0xFF142B20.toInt(),
)

internal fun buildPlaybackWidgetVisuals(
    artwork: Bitmap?,
    isDarkTheme: Boolean = false,
): PlaybackWidgetVisuals {
    val squareArtwork = artwork?.takeIf { it.width > 0 && it.height > 0 }?.toSquareBitmap()
    val palette = playbackWidgetPalette(
        seedColor = squareArtwork?.sampleThemeSeed() ?: Color.rgb(106, 163, 134),
        isDarkTheme = isDarkTheme,
    )
    if (squareArtwork == null) {
        return PlaybackWidgetVisuals(
            artwork = null,
            compactArtwork = null,
            primaryControl = null,
            backgroundColor = palette.backgroundColor,
            textPrimary = palette.textPrimary,
            textSecondary = palette.textSecondary,
            controlTint = palette.textPrimary,
            primaryControlTint = palette.primaryControlTint,
        )
    }
    val roundedArtwork = squareArtwork.toRoundedArtworkBitmap()
    return PlaybackWidgetVisuals(
        artwork = roundedArtwork,
        compactArtwork = squareArtwork,
        primaryControl = createPrimaryControl(palette.primaryControlColor),
        backgroundColor = palette.backgroundColor,
        textPrimary = palette.textPrimary,
        textSecondary = palette.textSecondary,
        controlTint = palette.textPrimary,
        primaryControlTint = palette.primaryControlTint,
    )
}

private data class PlaybackWidgetPalette(
    val backgroundColor: Int,
    val primaryControlColor: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val primaryControlTint: Int,
)

private fun playbackWidgetPalette(
    seedColor: Int,
    isDarkTheme: Boolean,
): PlaybackWidgetPalette {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(seedColor, hsl)
    hsl[1] = (hsl[1] * 1.35f).coerceAtMost(1f)
    val saturatedSeed = ColorUtils.HSLToColor(hsl)
    val surface = if (isDarkTheme) Color.rgb(25, 30, 27) else Color.rgb(250, 252, 249)
    val textBase = if (isDarkTheme) Color.rgb(244, 249, 245) else Color.rgb(24, 32, 27)
    val secondaryBase = if (isDarkTheme) Color.rgb(214, 226, 218) else Color.rgb(59, 72, 63)
    return PlaybackWidgetPalette(
        backgroundColor = ColorUtils.blendARGB(saturatedSeed, surface, if (isDarkTheme) 0.70f else 0.74f),
        primaryControlColor = ColorUtils.blendARGB(saturatedSeed, Color.WHITE, 0.50f),
        textPrimary = ColorUtils.blendARGB(seedColor, textBase, 0.94f),
        textSecondary = ColorUtils.blendARGB(seedColor, secondaryBase, 0.92f),
        primaryControlTint = Color.rgb(12, 24, 18),
    )
}

private const val ARTWORK_MAX_DIMENSION_PX = 192
private const val SURFACE_MAX_DIMENSION_PX = 1024
private const val PRIMARY_CONTROL_SIZE_PX = 96
private const val MINI_SCRIM_START_COLOR = 0x99000000.toInt()
private const val MINI_SCRIM_END_COLOR = 0xB3000000.toInt()

internal fun playbackWidgetSurface(
    size: PlaybackWidgetSize,
    cornerRadiusDp: Float,
    color: Int,
    renderScale: Float = 1f,
): Bitmap {
    val scale = minOf(renderScale, SURFACE_MAX_DIMENSION_PX.toFloat() / maxOf(size.widthDp, size.heightDp))
    val width = (size.widthDp * scale).roundToInt().coerceAtLeast(1)
    val height = (size.heightDp * scale).roundToInt().coerceAtLeast(1)
    val path = playbackWidgetCornerPath(
        width = width.toFloat(),
        height = height.toFloat(),
        cornerRadius = cornerRadiusDp * scale,
    )
    return createBitmap(width, height).also { output ->
        Canvas(output).drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    }
}

internal fun playbackWidgetBackdrop(
    artwork: Bitmap,
    size: PlaybackWidgetSize,
    cornerRadiusDp: Float,
    applyScrim: Boolean = false,
    renderScale: Float = 1f,
): Bitmap {
    // bitmap clipping also works on launchers where XML cannot clip the root view
    val output = playbackWidgetSurface(size, cornerRadiusDp, Color.WHITE, renderScale)
    val width = output.width
    val height = output.height
    val cropWidth = minOf(artwork.width, (artwork.height * width.toFloat() / height).roundToInt()).coerceAtLeast(1)
    val cropHeight = minOf(artwork.height, (artwork.width * height.toFloat() / width).roundToInt()).coerceAtLeast(1)
    val left = (artwork.width - cropWidth) / 2
    val top = (artwork.height - cropHeight) / 2
    val source = Rect(left, top, left + cropWidth, top + cropHeight)
    val target = RectF(0f, 0f, width.toFloat(), height.toFloat())
    val canvas = Canvas(output)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        // an opaque base keeps the scrim readable even when cover pixels are transparent
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
    }
    canvas.drawBitmap(artwork, source, target, paint)
    if (applyScrim) {
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        paint.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            MINI_SCRIM_START_COLOR, MINI_SCRIM_END_COLOR,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(target, paint)
    }
    return output
}

private fun Bitmap.toSquareBitmap(): Bitmap {
    val sourceSize = minOf(width, height)
    val left = (width - sourceSize) / 2
    val top = (height - sourceSize) / 2
    val targetSize = sourceSize.coerceAtMost(ARTWORK_MAX_DIMENSION_PX)
    return createBitmap(targetSize, targetSize).also { output ->
        Canvas(output).drawBitmap(
            this,
            Rect(left, top, left + sourceSize, top + sourceSize),
            Rect(0, 0, targetSize, targetSize),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
    }
}

private fun Bitmap.toRoundedArtworkBitmap(): Bitmap {
    val output = createBitmap(width, height)
    val canvas = Canvas(output)
    val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
    val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    canvas.drawPath(
        playbackWidgetCornerPath(width.toFloat(), height.toFloat(), width * 0.15f),
        maskPaint,
    )
    maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    canvas.drawBitmap(this, null, rect, maskPaint)
    maskPaint.xfermode = null
    return output
}

private fun playbackWidgetCornerPath(
    width: Float,
    height: Float,
    cornerRadius: Float,
): Path {
    val radius = cornerRadius.coerceIn(0f, minOf(width, height) / 2f)
    return Path().apply {
        addRoundRect(0f, 0f, width, height, radius, radius, Path.Direction.CW)
    }
}

private fun Bitmap.sampleThemeSeed(): Int {
    val stepX = (width / 48).coerceAtLeast(1)
    val stepY = (height / 48).coerceAtLeast(1)
    var red = 0f
    var green = 0f
    var blue = 0f
    var weightTotal = 0f
    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            val color = this[x, y]
            val alpha = color ushr 24 and 0xFF
            if (alpha > 24) {
                val sampleRed = color ushr 16 and 0xFF
                val sampleGreen = color ushr 8 and 0xFF
                val sampleBlue = color and 0xFF
                val maximum = maxOf(sampleRed, sampleGreen, sampleBlue)
                val minimum = minOf(sampleRed, sampleGreen, sampleBlue)
                val saturation = if (maximum == 0) 0f else {
                    (maximum - minimum).toFloat() / maximum
                }
                val brightness = maximum / 255f
                val weight = 0.25f + saturation * 1.4f +
                    if (brightness in 0.12f..0.95f) 0.25f else 0f
                red += sampleRed * weight
                green += sampleGreen * weight
                blue += sampleBlue * weight
                weightTotal += weight
            }
            x += stepX
        }
        y += stepY
    }
    if (weightTotal <= 0f) {
        return Color.rgb(112, 112, 112)
    }
    return Color.rgb(
        (red / weightTotal).toInt().coerceIn(0, 255),
        (green / weightTotal).toInt().coerceIn(0, 255),
        (blue / weightTotal).toInt().coerceIn(0, 255),
    )
}

private fun createPrimaryControl(color: Int): Bitmap {
    val output = createBitmap(PRIMARY_CONTROL_SIZE_PX, PRIMARY_CONTROL_SIZE_PX)
    val center = PRIMARY_CONTROL_SIZE_PX / 2f
    val radius = center - 4f
    Canvas(output).drawCircle(
        center,
        center,
        radius,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color },
    )
    return output
}
