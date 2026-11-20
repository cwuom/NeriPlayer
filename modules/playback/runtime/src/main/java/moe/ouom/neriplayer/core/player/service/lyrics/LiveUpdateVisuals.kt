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
// 系统只按 1.3:1 调整进度条颜色，播放头位图不会被调整；按浅色与深色卡片背景分别校验
private const val LIVE_UPDATE_MIN_SURFACE_CONTRAST = 1.3
private const val LIVE_UPDATE_LIGHT_SURFACE = 0xFFFFFFFF.toInt()
private const val LIVE_UPDATE_DARK_SURFACE = 0xFF1F1F1F.toInt()
private const val LIVE_UPDATE_LIGHTNESS_STEP = 0.02f
private const val LIVE_UPDATE_CONTRAST_MAX_STEPS = 25

/** 系统按 trackerHeight 缩放，位图只需比显示尺寸略大以保证清晰 */
internal const val LIVE_UPDATE_TRACKER_SIZE_PX = 72
private const val LIVE_UPDATE_TRACKER_RING_RATIO = 0.27f
private const val LIVE_UPDATE_TRACKER_CORE_RATIO = 0.19f

/**
 * 封面取色多为偏灰的 Vibrant/Muted 色，直接画在浅色通知上发闷；
 * 提到中等明度与较高饱和度，再确保与浅色、深色卡片背景都至少 1.3:1，深浅主题下都醒目。
 */
@ColorInt
internal fun resolveLiveUpdateAccentColor(@ColorInt baseColor: Int?): Int? {
    baseColor ?: return null
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(baseColor, hsl)
    if (hsl[1] < LIVE_UPDATE_NEUTRAL_SATURATION) return null
    val saturation = hsl[1].coerceAtLeast(LIVE_UPDATE_MIN_SATURATION)
    val lightness = hsl[2].coerceIn(LIVE_UPDATE_MIN_LIGHTNESS, LIVE_UPDATE_MAX_LIGHTNESS)
    val lifted = if (saturation == hsl[1] && lightness == hsl[2]) {
        baseColor or 0xFF000000.toInt()
    } else {
        ColorUtils.HSLToColor(floatArrayOf(hsl[0], saturation, lightness))
    }
    return ensureLiveUpdateSurfaceContrast(lifted, hsl[0], saturation, lightness)
}

/** 黄、青等高亮度色相即使在中等明度下也贴近浅色背景：太亮就压暗，太暗就提亮，直到两种背景都达标 */
@ColorInt
private fun ensureLiveUpdateSurfaceContrast(@ColorInt color: Int, hue: Float, saturation: Float, lightness: Float): Int {
    var candidate = color
    var currentLightness = lightness
    repeat(LIVE_UPDATE_CONTRAST_MAX_STEPS) {
        val step = when {
            ColorUtils.calculateContrast(candidate, LIVE_UPDATE_LIGHT_SURFACE) < LIVE_UPDATE_MIN_SURFACE_CONTRAST ->
                -LIVE_UPDATE_LIGHTNESS_STEP
            ColorUtils.calculateContrast(candidate, LIVE_UPDATE_DARK_SURFACE) < LIVE_UPDATE_MIN_SURFACE_CONTRAST ->
                LIVE_UPDATE_LIGHTNESS_STEP
            else -> return candidate
        }
        currentLightness = (currentLightness + step).coerceIn(0f, 1f)
        candidate = ColorUtils.HSLToColor(floatArrayOf(hue, saturation, currentLightness))
    }
    return candidate
}

/** 取色只为正在展示的歌曲进行；已有颜色、正在取色或同一封面失败过时不再发起 */
internal fun shouldLoadLiveUpdateAccent(
    enabled: Boolean,
    artworkSongKey: String,
    frameSongKey: String?,
    hasAccent: Boolean,
    loading: Boolean,
    alreadyAttempted: Boolean,
): Boolean = enabled && artworkSongKey == frameSongKey && !hasAccent && !loading && !alreadyAttempted

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
