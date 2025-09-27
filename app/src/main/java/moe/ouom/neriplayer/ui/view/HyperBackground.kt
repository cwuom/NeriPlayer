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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.ViewCompat
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.player.PlayerManager
import kotlin.math.max

/**
 * 渲染 Hyper 背景
 * - Android 13+（API 33）启用 RuntimeShader；低版本自动降级为透明
 * - 通过 withFrameNanos 获取逐帧时间，驱动 BgEffectPainter
 */
@Composable
fun HyperBackground(
    modifier: Modifier = Modifier,
    isDark: Boolean,
    coverUrl: String?
) {
    val context = LocalContext.current
    val currentIsDark by rememberUpdatedState(isDark)

    // 仅 T+ 创建 painter
    val painter = remember(currentIsDark) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            BgEffectPainter(context)
        } else null
    }

    var hostView by remember { mutableStateOf<View?>(null) }

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

    // 等待视图真正 ready
    suspend fun awaitViewReady(v: View) {
        while (
            !v.isAttachedToWindow ||
            v.parent == null ||
            !ViewCompat.isLaidOut(v) ||
            v.width == 0 || v.height == 0
        ) {
            withFrameNanos { /* just wait next frame */ }
        }
    }

    val level by PlayerManager.audioLevelFlow.collectAsState(0f)
    val beat  by PlayerManager.beatImpulseFlow.collectAsState(0f)

    LaunchedEffect(painter, hostView, currentIsDark, coverUrl) {
        if (painter == null || hostView == null) return@LaunchedEffect
        val v = hostView!!

        awaitViewReady(v)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                painter.showRuntimeShader(context, v, null, currentIsDark)
            } catch (_: Throwable) { return@LaunchedEffect }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !coverUrl.isNullOrBlank()) {
            try {
                val loader = ImageLoader(context)
                val req = ImageRequest.Builder(context)
                    .data(coverUrl)
                    .allowHardware(false) // Palette 需要 software bitmap
                    .build()
                val result = withContext(Dispatchers.IO) { loader.execute(req) }
                val bmp = (result as? SuccessResult)?.drawable?.toBitmap()

                if (bmp != null) {
                    val palette = withContext(Dispatchers.Default) {
                        Palette.from(bmp)
                            .clearFilters() // 保留更真实的颜色
                            .maximumColorCount(16)
                            .generate()
                    }

                    // dominant / lightVibrant / muted / darkMuted
                    fun pickColor(vararg candidates: Int?): Int {
                        val ok = candidates.firstOrNull { it != null && it != 0 } ?: 0xFF808080.toInt()
                        return ok
                    }

                    val c1 = pickColor(
                        palette.dominantSwatch?.rgb,
                        palette.vibrantSwatch?.rgb,
                        palette.mutedSwatch?.rgb
                    )
                    val c2 = pickColor(
                        palette.lightVibrantSwatch?.rgb,
                        palette.lightMutedSwatch?.rgb,
                        c1
                    )
                    val c3 = pickColor(
                        palette.mutedSwatch?.rgb,
                        palette.vibrantSwatch?.rgb,
                        c1
                    )
                    val c4 = pickColor(
                        palette.darkMutedSwatch?.rgb,
                        palette.darkVibrantSwatch?.rgb,
                        c1
                    )

                    fun to01(x: Int) = (x and 0xFF) / 255f
                    fun argbToVec4(c: Int): FloatArray {
                        val a = to01(c ushr 24)
                        val r = to01(c ushr 16)
                        val g = to01(c ushr 8)
                        val b = to01(c)
                        // shader 里把 rgb 乘以 a，所以这里把 a 固定为 1，避免过暗
                        return floatArrayOf(r, g, b, 1f)
                    }

                    val colors = floatArrayOf(
                        *argbToVec4(c1),
                        *argbToVec4(c2),
                        *argbToVec4(c3),
                        *argbToVec4(c4),
                    )

                    // 亮度估计，调一点点亮度/饱和度
                    fun luma(c: Int): Float {
                        val r = to01(c ushr 16); val g = to01(c ushr 8); val b = to01(c)
                        return (0.2126f*r + 0.7152f*g + 0.0722f*b)
                    }
                    val L = luma(c1)
                    val lightOffset = when {
                        currentIsDark -> (-0.06f + (0.12f * (L - 0.5f)))  // 暗色下略降亮，偏亮封面就少降一点
                        else          -> ( 0.08f + (0.10f * (0.5f - L)))  // 亮色下略升亮，偏暗封面就多升一点
                    }.coerceIn(-0.12f, 0.12f)

                    val saturateOffset = (if (currentIsDark) 0.24f else 0.16f)

                    // 喂给着色器
                    painter.setColors(colors)
                    painter.setLightOffset(lightOffset)
                    painter.setSaturateOffset(saturateOffset)
                }
            } catch (_: Throwable) {
                // 忽略提色失败，继续使用默认配色
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            var startNs = 0L
            var beatEnv = 0f
            while (isActive) {
                withFrameNanos { t ->
                    if (startNs == 0L) startNs = t
                    val seconds = ((t - startNs) / 1_000_000_000.0).toFloat()
                    painter.setAnimTime(seconds % 62.831852f)

                    beatEnv = max(beatEnv * 0.92f, beat)
                    painter.setReactive(level, beatEnv)

                    val w = v.width; val h = v.height
                    if (w > 0 && h > 0) painter.setResolution(floatArrayOf(w.toFloat(), h.toFloat()))
                    painter.updateMaterials()
                    v.setRenderEffect(painter.renderEffect)
                }
            }
        }
    }
}