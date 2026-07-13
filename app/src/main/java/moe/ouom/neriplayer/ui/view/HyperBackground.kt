package moe.ouom.neriplayer.ui.view

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.ui.view/HyperBackground
 * Created: 2025/8/10
 */

import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import coil.Coil
import coil.size.Precision
import androidx.palette.graphics.Palette
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.util.media.isRemoteImageSource
import kotlin.math.abs

private const val DynamicBackgroundFallbackColor = 0xFF808080.toInt()
private const val StrongHueConflictDegrees = 105f
private const val PaletteCoverDecodeSizePx = 320
private const val DynamicBackgroundPaletteTransitionDurationMs = 520L
private const val DynamicBackgroundSteadyFrameIntervalNs = 1_000_000_000L / 45L
private const val DynamicBackgroundBoostFrameIntervalNs = 1_000_000_000L / 60L
private const val DynamicBackgroundBoostDurationNs = 900_000_000L

private enum class DynamicBackgroundColorRole {
    Base,
    Accent,
    Light,
    Dark,
    Bridge
}

private data class DynamicBackgroundPalette(
    val colors: FloatArray,
    val primaryColor: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DynamicBackgroundPalette

        if (primaryColor != other.primaryColor) return false
        if (!colors.contentEquals(other.colors)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = primaryColor
        result = 31 * result + colors.contentHashCode()
        return result
    }
}

private data class DynamicBackgroundShaderPalette(
    val colors: FloatArray,
    val lightOffset: Float,
    val saturateOffset: Float
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DynamicBackgroundShaderPalette

        if (lightOffset != other.lightOffset) return false
        if (saturateOffset != other.saturateOffset) return false
        if (!colors.contentEquals(other.colors)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = lightOffset.hashCode()
        result = 31 * result + saturateOffset.hashCode()
        result = 31 * result + colors.contentHashCode()
        return result
    }
}

/**
 * 渲染 Hyper 背景
 * - Android 13+（API 33）启用 RuntimeShader；低版本自动降级为透明
 * - 通过 withFrameNanos 获取逐帧时间，驱动 BgEffectPainter
 */
@Composable
fun HyperBackground(
    modifier: Modifier = Modifier,
    isDark: Boolean,
    coverUrl: String?,
    refreshKey: Int = 0,
    offlineMode: Boolean = false
) {
    val context = LocalContext.current
    val applicationContext = context.applicationContext
    val currentIsDark by rememberUpdatedState(isDark)

    // 仅 T+ 创建 painter
    val painter = remember(currentIsDark, applicationContext) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            BgEffectPainter(applicationContext)
        } else null
    }

    var hostView by remember { mutableStateOf<View?>(null) }
    var shaderInitialized by remember(painter, hostView, currentIsDark) {
        mutableStateOf(false)
    }
    var targetShaderPalette by remember { mutableStateOf<DynamicBackgroundShaderPalette?>(null) }
    var activeShaderPalette by remember(painter, currentIsDark) {
        mutableStateOf<DynamicBackgroundShaderPalette?>(null)
    }
    var boostedAnimationUntilNs by remember { mutableStateOf(0L) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            View(ctx).apply {
                setWillNotDraw(false)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                hostView = this
            }
        },
        update = { v ->
            hostView = v
        }
    )

    DisposableEffect(hostView) {
        val view = hostView
        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runCatching { view?.setRenderEffect(null) }
                    .onFailure { error ->
                        NPLogger.w("NERI-HyperBg", "clear render effect failed", error)
                    }
            }
        }
    }

    // 等待视图真正 ready
    suspend fun awaitViewReady(v: View) {
        while (
            !v.isAttachedToWindow ||
            v.parent == null ||
            !v.isLaidOut ||
            v.width == 0 || v.height == 0
        ) {
            withFrameNanos { /* just wait next frame */ }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val latestBoostedAnimationUntilNs by rememberUpdatedState(boostedAnimationUntilNs)

    LaunchedEffect(coverUrl, refreshKey, offlineMode, currentIsDark) {
        boostedAnimationUntilNs = System.nanoTime() + DynamicBackgroundBoostDurationNs
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || coverUrl.isNullOrBlank()) {
            return@LaunchedEffect
        }
        try {
            val loader = Coil.imageLoader(context)
            val req = ImageRequest.Builder(context)
                .data(coverUrl)
                .size(PaletteCoverDecodeSizePx)
                .precision(Precision.INEXACT)
                .allowHardware(false) // Palette 需要 software bitmap
                .networkCachePolicy(
                    if (offlineMode && isRemoteImageSource(coverUrl)) {
                        CachePolicy.DISABLED
                    } else {
                        CachePolicy.ENABLED
                    }
                )
                .build()
            val result = withContext(Dispatchers.IO) { loader.execute(req) }
            val bmp = (result as? SuccessResult)?.drawable?.toBitmap() ?: return@LaunchedEffect
            val palette = withContext(Dispatchers.Default) {
                Palette.from(bmp)
                    .clearFilters() // 保留更真实的颜色
                    .maximumColorCount(16)
                    .generate()
            }
            targetShaderPalette = buildDynamicBackgroundShaderPalette(
                palette = palette,
                isDark = currentIsDark
            )
        } catch (_: Throwable) {
            // 提色失败时保留上一组颜色，避免切歌瞬间退回默认背景
        }
    }

    LaunchedEffect(painter, hostView, shaderInitialized, targetShaderPalette) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
        val target = targetShaderPalette ?: return@LaunchedEffect
        val view = hostView ?: return@LaunchedEffect
        if (painter == null || !shaderInitialized) return@LaunchedEffect
        val applied = animateDynamicBackgroundPalette(
            painter = painter,
            view = view,
            from = activeShaderPalette,
            to = target
        )
        activeShaderPalette = applied
    }

    LaunchedEffect(painter, hostView, currentIsDark, lifecycleOwner) {
        if (painter == null || hostView == null) return@LaunchedEffect
        val v = hostView!!

        awaitViewReady(v)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !shaderInitialized) {
            try {
                painter.showRuntimeShader(context, v, null, currentIsDark)
                v.setRenderEffect(painter.renderEffect)
                v.postInvalidateOnAnimation()
                shaderInitialized = true
            } catch (_: Throwable) { return@LaunchedEffect }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                var currentLevel = 0f
                var currentBeat = 0f
                var pendingBeatPeak = 0f
                var startNs = 0L
                var nextRenderNs = Long.MIN_VALUE
                var smoothLevel = 0f
                var smoothBeat = 0f
                var lastRenderWidth = 0
                var lastRenderHeight = 0
                val levelJob = launch {
                    PlayerManager.audioLevelFlow.collect { currentLevel = it }
                }
                val beatJob = launch {
                    PlayerManager.beatImpulseFlow.collect { beat ->
                        val boundedBeat = beat.coerceIn(0f, 1f)
                        currentBeat = boundedBeat
                        pendingBeatPeak = maxOf(pendingBeatPeak, boundedBeat)
                    }
                }
                try {
                    while (isActive) {
                        withFrameNanos { t ->
                            val frameIntervalNs = if (t < latestBoostedAnimationUntilNs) {
                                DynamicBackgroundBoostFrameIntervalNs
                            } else {
                                DynamicBackgroundSteadyFrameIntervalNs
                            }
                            if (nextRenderNs != Long.MIN_VALUE && t < nextRenderNs) {
                                return@withFrameNanos
                            }
                            nextRenderNs = nextDynamicBackgroundRenderNs(
                                frameNs = t,
                                currentNextNs = nextRenderNs,
                                intervalNs = frameIntervalNs
                            )
                            if (startNs == 0L) startNs = t
                            val seconds = ((t - startNs) / 1_000_000_000.0).toFloat()
                            painter.setAnimTime(seconds % 62.831852f)

                            val targetLevel = currentLevel.coerceIn(0f, 1f)
                            val targetBeat = (maxOf(currentBeat, pendingBeatPeak) * 0.94f).coerceIn(0f, 1f)
                            pendingBeatPeak = 0f
                            val levelRate = if (targetLevel > smoothLevel) 0.12f else 0.045f
                            val beatRate = if (targetBeat > smoothBeat) 0.46f else 0.12f
                            smoothLevel += (targetLevel - smoothLevel) * levelRate
                            smoothBeat += (targetBeat - smoothBeat) * beatRate
                            painter.setReactive(smoothLevel, smoothBeat)

                            val width = v.width
                            val height = v.height
                            if (
                                width > 0 &&
                                height > 0 &&
                                (width != lastRenderWidth || height != lastRenderHeight)
                            ) {
                                painter.setResolution(width.toFloat(), height.toFloat())
                                lastRenderWidth = width
                                lastRenderHeight = height
                            }
                            painter.updateMaterials()
                            v.setRenderEffect(painter.renderEffect)
                            v.postInvalidateOnAnimation()
                        }
                    }
                } finally {
                    levelJob.cancel()
                    beatJob.cancel()
                }
            }
        }
    }
}

private fun buildDynamicBackgroundShaderPalette(
    palette: Palette,
    isDark: Boolean
): DynamicBackgroundShaderPalette {
    val dynamicPalette = buildDynamicBackgroundPalette(
        palette = palette,
        isDark = isDark
    )
    val lumaValue = colorLuma(dynamicPalette.primaryColor)
    val lightOffset = when {
        isDark -> -0.06f + (0.12f * (lumaValue - 0.5f))
        else -> 0.08f + (0.10f * (0.5f - lumaValue))
    }.coerceIn(-0.12f, 0.12f)
    val saturateOffset = if (isDark) 0.24f else 0.16f
    return DynamicBackgroundShaderPalette(
        colors = dynamicPalette.colors,
        lightOffset = lightOffset,
        saturateOffset = saturateOffset
    )
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private suspend fun animateDynamicBackgroundPalette(
    painter: BgEffectPainter,
    view: View,
    from: DynamicBackgroundShaderPalette?,
    to: DynamicBackgroundShaderPalette
): DynamicBackgroundShaderPalette {
    if (from == null || from.colors.size != to.colors.size) {
        painter.setColors(to.colors)
        painter.setLightOffset(to.lightOffset)
        painter.setSaturateOffset(to.saturateOffset)
        view.postInvalidateOnAnimation()
        return to
    }

    val startColors = from.colors.copyOf()
    val interpolatedColors = FloatArray(startColors.size)
    var startNanos = 0L
    var lastUpdateNanos = Long.MIN_VALUE
    var finished = false
    while (!finished) {
        withFrameNanos { frameNanos ->
            if (startNanos == 0L) {
                startNanos = frameNanos
            }
            val rawFraction = ((frameNanos - startNanos).toDouble() /
                (DynamicBackgroundPaletteTransitionDurationMs * 1_000_000.0)).toFloat()
            if (
                rawFraction < 1f &&
                lastUpdateNanos != Long.MIN_VALUE &&
                frameNanos - lastUpdateNanos < DynamicBackgroundBoostFrameIntervalNs
            ) {
                return@withFrameNanos
            }
            lastUpdateNanos = frameNanos
            val fraction = smoothStep01(rawFraction.coerceIn(0f, 1f))
            lerpFloatArrayInto(startColors, to.colors, fraction, interpolatedColors)
            painter.setColors(interpolatedColors)
            painter.setLightOffset(lerpFloat(from.lightOffset, to.lightOffset, fraction))
            painter.setSaturateOffset(lerpFloat(from.saturateOffset, to.saturateOffset, fraction))
            view.postInvalidateOnAnimation()
            finished = rawFraction >= 1f
        }
    }
    painter.setColors(to.colors)
    painter.setLightOffset(to.lightOffset)
    painter.setSaturateOffset(to.saturateOffset)
    view.postInvalidateOnAnimation()
    return to
}

internal fun nextDynamicBackgroundRenderNs(
    frameNs: Long,
    currentNextNs: Long,
    intervalNs: Long
): Long {
    if (currentNextNs == Long.MIN_VALUE || frameNs - currentNextNs > intervalNs * 2L) {
        return frameNs + intervalNs
    }

    var nextNs = currentNextNs
    while (nextNs <= frameNs) {
        nextNs += intervalNs
    }
    return nextNs
}

private fun buildDynamicBackgroundPalette(
    palette: Palette,
    isDark: Boolean
): DynamicBackgroundPalette {
    val baseColor = pickPaletteColor(
        palette.mutedSwatch?.rgb,
        palette.dominantSwatch?.rgb,
        palette.darkMutedSwatch?.rgb,
        palette.vibrantSwatch?.rgb
    )
    val accentColor = pickAccentPaletteColor(
        fallback = baseColor,
        palette.vibrantSwatch?.rgb,
        palette.lightVibrantSwatch?.rgb,
        palette.darkVibrantSwatch?.rgb,
        palette.dominantSwatch?.rgb,
        palette.mutedSwatch?.rgb
    )
    val lightColor = pickPaletteColor(
        palette.lightVibrantSwatch?.rgb,
        palette.lightMutedSwatch?.rgb,
        accentColor,
        baseColor
    )
    val darkColor = pickPaletteColor(
        palette.darkVibrantSwatch?.rgb,
        palette.darkMutedSwatch?.rgb,
        darkenColor(accentColor, if (isDark) 0.44f else 0.34f),
        palette.mutedSwatch?.rgb,
        baseColor
    )
    val bridgeColor = ColorUtils.blendARGB(
        ColorUtils.blendARGB(accentColor, lightColor, 0.50f),
        baseColor,
        0.24f
    )
    val softenedColors = intArrayOf(
        softenPaletteColor(baseColor, baseColor, isDark, DynamicBackgroundColorRole.Base),
        softenPaletteColor(accentColor, baseColor, isDark, DynamicBackgroundColorRole.Accent),
        softenPaletteColor(lightColor, baseColor, isDark, DynamicBackgroundColorRole.Light),
        softenPaletteColor(darkColor, baseColor, isDark, DynamicBackgroundColorRole.Dark),
        softenPaletteColor(bridgeColor, baseColor, isDark, DynamicBackgroundColorRole.Bridge)
    )
    return DynamicBackgroundPalette(
        colors = colorsToShaderVec4Array(softenedColors),
        primaryColor = softenedColors.first()
    )
}

private fun pickPaletteColor(vararg candidates: Int?): Int {
    return candidates.firstOrNull { it != null && it != 0 } ?: DynamicBackgroundFallbackColor
}

private fun pickAccentPaletteColor(
    fallback: Int,
    vararg candidates: Int?
): Int {
    val accent = candidates
        .filterNotNull()
        .filter { it != 0 }
        .maxByOrNull { colorfulnessScore(it) }
    val selected = accent?.takeIf { colorfulnessScore(it) >= 0.10f } ?: fallback
    return ensureAccentColor(selected, fallback)
}

private fun softenPaletteColor(
    rawColor: Int,
    anchorColor: Int,
    isDark: Boolean,
    role: DynamicBackgroundColorRole
): Int {
    val rawHsl = FloatArray(3)
    val anchorHsl = FloatArray(3)
    ColorUtils.colorToHSL(rawColor, rawHsl)
    ColorUtils.colorToHSL(anchorColor, anchorHsl)

    val hueConflict = hueDistanceDegrees(rawHsl[0], anchorHsl[0]) > StrongHueConflictDegrees &&
        rawHsl[1] > 0.28f &&
        anchorHsl[1] > 0.20f
    val anchorBlend = when (role) {
        DynamicBackgroundColorRole.Base -> 0.08f
        DynamicBackgroundColorRole.Accent -> if (hueConflict) 0.18f else 0.04f
        DynamicBackgroundColorRole.Light -> if (hueConflict) 0.24f else 0.08f
        DynamicBackgroundColorRole.Dark -> if (hueConflict) 0.42f else 0.22f
        DynamicBackgroundColorRole.Bridge -> 0.20f
    }
    val blendedColor = ColorUtils.blendARGB(rawColor, anchorColor, anchorBlend)
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(blendedColor, hsl)

    val saturationScale = when (role) {
        DynamicBackgroundColorRole.Base -> 0.94f
        DynamicBackgroundColorRole.Accent -> 1.14f
        DynamicBackgroundColorRole.Light -> 1.02f
        DynamicBackgroundColorRole.Dark -> 0.76f
        DynamicBackgroundColorRole.Bridge -> 0.96f
    }
    val saturationMax = if (role == DynamicBackgroundColorRole.Accent) {
        if (isDark) 0.70f else 0.64f
    } else if (isDark) 0.64f else 0.56f
    val saturationFloor = if (hsl[1] < 0.05f) {
        if (role == DynamicBackgroundColorRole.Accent) 0.30f else 0f
    } else if (role == DynamicBackgroundColorRole.Dark) {
        0.10f
    } else if (role == DynamicBackgroundColorRole.Accent) {
        0.30f
    } else {
        0.14f
    }
    hsl[1] = (hsl[1] * saturationScale).coerceIn(saturationFloor, saturationMax)

    val targetLightness = when (role) {
        DynamicBackgroundColorRole.Base -> if (isDark) 0.34f else 0.58f
        DynamicBackgroundColorRole.Accent -> if (isDark) 0.48f else 0.66f
        DynamicBackgroundColorRole.Light -> if (isDark) 0.62f else 0.82f
        DynamicBackgroundColorRole.Dark -> if (isDark) 0.18f else 0.34f
        DynamicBackgroundColorRole.Bridge -> if (isDark) 0.46f else 0.66f
    }
    val lightnessBlend = when (role) {
        DynamicBackgroundColorRole.Base -> 0.18f
        DynamicBackgroundColorRole.Accent -> 0.18f
        DynamicBackgroundColorRole.Light -> 0.24f
        DynamicBackgroundColorRole.Dark -> 0.34f
        DynamicBackgroundColorRole.Bridge -> 0.20f
    }
    val minLightness = if (isDark) 0.12f else 0.28f
    val maxLightness = if (isDark) 0.66f else 0.86f
    hsl[2] = lerpFloat(hsl[2], targetLightness, lightnessBlend)
        .coerceIn(minLightness, maxLightness)

    return ColorUtils.HSLToColor(hsl)
}

private fun colorfulnessScore(color: Int): Float {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color, hsl)
    return hsl[1] * (0.45f + abs(hsl[2] - 0.5f).coerceAtMost(0.5f))
}

private fun ensureAccentColor(color: Int, fallback: Int): Int {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color, hsl)
    if (hsl[1] >= 0.24f) {
        return color
    }

    val fallbackHsl = FloatArray(3)
    ColorUtils.colorToHSL(fallback, fallbackHsl)
    val resolvedHue = if (hsl[1] > 0.04f) {
        hsl[0]
    } else if (fallbackHsl[1] > 0.04f) {
        fallbackHsl[0]
    } else {
        320f
    }
    hsl[0] = resolvedHue
    hsl[1] = 0.48f
    hsl[2] = hsl[2].coerceIn(0.40f, 0.68f)
    return ColorUtils.HSLToColor(hsl)
}

private fun darkenColor(color: Int, targetLightness: Float): Int {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color, hsl)
    hsl[2] = minOf(hsl[2], targetLightness)
    return ColorUtils.HSLToColor(hsl)
}

private fun colorsToShaderVec4Array(colors: IntArray): FloatArray {
    val shaderColors = FloatArray(colors.size * 4)
    colors.forEachIndexed { index, color ->
        val offset = index * 4
        shaderColors[offset] = colorChannelTo01(color ushr 16)
        shaderColors[offset + 1] = colorChannelTo01(color ushr 8)
        shaderColors[offset + 2] = colorChannelTo01(color)
        shaderColors[offset + 3] = 1f
    }
    return shaderColors
}

private fun colorChannelTo01(value: Int): Float {
    return (value and 0xFF) / 255f
}

private fun colorLuma(color: Int): Float {
    val r = colorChannelTo01(color ushr 16)
    val g = colorChannelTo01(color ushr 8)
    val b = colorChannelTo01(color)
    return 0.2126f * r + 0.7152f * g + 0.0722f * b
}

private fun hueDistanceDegrees(first: Float, second: Float): Float {
    val diff = abs(first - second) % 360f
    return minOf(diff, 360f - diff)
}

private fun lerpFloat(start: Float, stop: Float, fraction: Float): Float {
    return start + (stop - start) * fraction.coerceIn(0f, 1f)
}

private fun lerpFloatArrayInto(
    start: FloatArray,
    stop: FloatArray,
    fraction: Float,
    result: FloatArray
) {
    require(start.size == stop.size && start.size == result.size)
    for (index in start.indices) {
        result[index] = lerpFloat(start[index], stop[index], fraction)
    }
}

private fun smoothStep01(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
