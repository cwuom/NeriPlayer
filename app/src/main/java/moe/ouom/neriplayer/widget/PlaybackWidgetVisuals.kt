package moe.ouom.neriplayer.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get

internal data class PlaybackWidgetVisuals(
    val artwork: Bitmap?,
    val compactArtwork: Bitmap?,
    val themeBackground: Bitmap?,
    val compactThemeBackground: Bitmap?,
    val legacyThemeBackground: Bitmap?,
    val legacyCompactThemeBackground: Bitmap?,
    val primaryControl: Bitmap?,
)

internal fun selectPlaybackWidgetThemeBackground(
    visuals: PlaybackWidgetVisuals,
    hasProgress: Boolean,
    sdkInt: Int,
): Bitmap? {
    val supportsRootClipping = sdkInt >= Build.VERSION_CODES.S
    return when {
        hasProgress && supportsRootClipping -> visuals.themeBackground
        hasProgress -> visuals.legacyThemeBackground
        supportsRootClipping -> visuals.compactThemeBackground
        else -> visuals.legacyCompactThemeBackground
    }
}

internal fun buildPlaybackWidgetVisuals(artwork: Bitmap?): PlaybackWidgetVisuals {
    if (artwork == null || artwork.width <= 0 || artwork.height <= 0) {
        return PlaybackWidgetVisuals(
            artwork = null,
            compactArtwork = null,
            themeBackground = null,
            compactThemeBackground = null,
            legacyThemeBackground = null,
            legacyCompactThemeBackground = null,
            primaryControl = null,
        )
    }
    val squareArtwork = artwork.toSquareBitmap()
    val colors = derivePlaybackWidgetThemeColors(squareArtwork.sampleThemeSeed())
    return PlaybackWidgetVisuals(
        artwork = squareArtwork.toRoundedArtworkBitmap(),
        compactArtwork = squareArtwork,
        themeBackground = createThemeBackground(
            colors = colors,
            widthPx = THEME_BACKGROUND_WIDTH_PX,
            heightPx = THEME_BACKGROUND_HEIGHT_PX,
        ),
        compactThemeBackground = createThemeBackground(
            colors = colors,
            widthPx = COMPACT_THEME_BACKGROUND_SIZE_PX,
            heightPx = COMPACT_THEME_BACKGROUND_SIZE_PX,
        ),
        legacyThemeBackground = createRoundedThemeBackground(
            colors = colors,
            widthPx = THEME_BACKGROUND_WIDTH_PX,
            heightPx = THEME_BACKGROUND_HEIGHT_PX,
            cornerRadiusFraction = 0.24f,
        ),
        legacyCompactThemeBackground = createRoundedThemeBackground(
            colors = colors,
            widthPx = COMPACT_THEME_BACKGROUND_SIZE_PX,
            heightPx = COMPACT_THEME_BACKGROUND_SIZE_PX,
            cornerRadiusFraction = 0.22f,
        ),
        primaryControl = createPrimaryControl(colors.primaryControl),
    )
}

private const val ARTWORK_MAX_DIMENSION_PX = 192
private const val THEME_BACKGROUND_WIDTH_PX = 384
private const val THEME_BACKGROUND_HEIGHT_PX = 176
private const val COMPACT_THEME_BACKGROUND_SIZE_PX = 192
private const val PRIMARY_CONTROL_SIZE_PX = 96

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
    canvas.drawRoundRect(rect, width * 0.15f, width * 0.15f, maskPaint)
    maskPaint.xfermode = android.graphics.PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    canvas.drawBitmap(this, null, rect, maskPaint)
    maskPaint.xfermode = null
    return output
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

private fun createThemeBackground(
    colors: PlaybackWidgetThemeColors,
    widthPx: Int,
    heightPx: Int,
): Bitmap {
    return createWidgetThemeBackground(
        colors = colors,
        widthPx = widthPx,
        heightPx = heightPx,
        cornerRadius = null,
    )
}

private fun createRoundedThemeBackground(
    colors: PlaybackWidgetThemeColors,
    widthPx: Int,
    heightPx: Int,
    cornerRadiusFraction: Float,
): Bitmap {
    return createWidgetThemeBackground(
        colors = colors,
        widthPx = widthPx,
        heightPx = heightPx,
        cornerRadius = minOf(widthPx, heightPx) * cornerRadiusFraction,
    )
}

private fun createWidgetThemeBackground(
    colors: PlaybackWidgetThemeColors,
    widthPx: Int,
    heightPx: Int,
    cornerRadius: Float?,
): Bitmap {
    val output = createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
    val bounds = RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat())
    val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f,
            0f,
            widthPx.toFloat(),
            heightPx.toFloat(),
            colors.backgroundStart,
            colors.backgroundEnd,
            Shader.TileMode.CLAMP,
        )
    }
    if (cornerRadius == null) {
        canvas.drawRect(bounds, backgroundPaint)
    } else {
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, backgroundPaint)
    }
    return output
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
    Canvas(output).drawCircle(
        center,
        center,
        radius,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            this.color = Color.argb(55, 255, 255, 255)
        },
    )
    return output
}
