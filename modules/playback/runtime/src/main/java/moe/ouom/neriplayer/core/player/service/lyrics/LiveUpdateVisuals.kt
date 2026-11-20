package moe.ouom.neriplayer.core.player.service.lyrics

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap

/** 低于这个饱和度视为灰阶封面，硬提饱和度会凭空染出红色，交给系统强调色 */
private const val LIVE_UPDATE_NEUTRAL_SATURATION = 0.12f
private const val LIVE_UPDATE_MIN_SATURATION = 0.55f
private const val LIVE_UPDATE_MIN_LIGHTNESS = 0.48f
private const val LIVE_UPDATE_MAX_LIGHTNESS = 0.62f

/** 系统按 trackerHeight 缩放，位图只需比显示尺寸略大以保证清晰 */
internal const val LIVE_UPDATE_TRACKER_SIZE_PX = 72
private const val LIVE_UPDATE_TRACKER_RING_RATIO = 0.27f
private const val LIVE_UPDATE_TRACKER_CORE_RATIO = 0.19f

/**
 * 封面取色多为偏灰的 Vibrant/Muted 色，直接画在浅色通知上发闷；
 * 提到中等明度与较高饱和度，深浅主题下都醒目，且始终满足系统 1.3:1 的进度条对比度。
 */
@ColorInt
internal fun resolveLiveUpdateAccentColor(@ColorInt baseColor: Int?): Int? {
    baseColor ?: return null
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(baseColor, hsl)
    if (hsl[1] < LIVE_UPDATE_NEUTRAL_SATURATION) return null
    val saturation = hsl[1].coerceAtLeast(LIVE_UPDATE_MIN_SATURATION)
    val lightness = hsl[2].coerceIn(LIVE_UPDATE_MIN_LIGHTNESS, LIVE_UPDATE_MAX_LIGHTNESS)
    if (saturation == hsl[1] && lightness == hsl[2]) return baseColor or 0xFF000000.toInt()
    return ColorUtils.HSLToColor(floatArrayOf(hsl[0], saturation, lightness))
}

/** 没有封面色时用系统动态强调色，保证播放头与进度条同色 */
@ColorInt
internal fun liveUpdateSystemAccentColor(context: Context): Int? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getColor(android.R.color.system_accent1_500)
    } else {
        null
    }

/** 发光播放头：外圈渐隐光晕 + 白色描边 + 强调色圆点，模拟媒体卡片的进度头 */
internal fun createLiveUpdateTrackerBitmap(
    @ColorInt color: Int,
    sizePx: Int = LIVE_UPDATE_TRACKER_SIZE_PX,
): Bitmap {
    val bitmap = createBitmap(sizePx, sizePx)
    val canvas = Canvas(bitmap)
    val center = sizePx / 2f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.shader = RadialGradient(
        center,
        center,
        center,
        intArrayOf(
            ColorUtils.setAlphaComponent(color, 0x8C),
            ColorUtils.setAlphaComponent(color, 0x2E),
            Color.TRANSPARENT,
        ),
        floatArrayOf(LIVE_UPDATE_TRACKER_RING_RATIO * 2f, 0.72f, 1f),
        Shader.TileMode.CLAMP,
    )
    canvas.drawCircle(center, center, center, paint)
    paint.shader = null
    paint.color = Color.WHITE
    canvas.drawCircle(center, center, sizePx * LIVE_UPDATE_TRACKER_RING_RATIO, paint)
    paint.color = color
    canvas.drawCircle(center, center, sizePx * LIVE_UPDATE_TRACKER_CORE_RATIO, paint)
    return bitmap
}
