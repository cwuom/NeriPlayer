package moe.ouom.neriplayer.ui.component.navigation

// Press spot adapted from Kyant0/AndroidLiquidGlass's InteractiveHighlight
// (https://github.com/Kyant0/AndroidLiquidGlass, Apache-2.0), via the np-submodule/miuix example.

import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb

// 参照 Kyant / miuix：整片透镜先加一层极淡的亮度，再在手指处叠一团柔光；浅色下底子已经很亮，
// 叠加减半，避免透镜被提成一块纯白
private const val SpotWashAlpha = 0.04f
private const val SpotAlpha = 0.1f
private const val SpotLightThemeFactor = 0.4f
// 光斑半径取透镜短边的 1.2 倍，一半半径内是实心，往外 smoothstep 过渡到 0
private const val SpotRadiusFactor = 1.2f
private const val SpotSolidFraction = 0.5f

/**
 * The touch glow of the enhanced lens, drawn over the refracted glass with additive blending: a
 * faint wash over the whole lens plus a soft spot under the finger. Android 13+ shades the spot
 * with a smoothstep runtime shader, which has no gradient stops and so no banding; older
 * versions fall back to a dithered radial gradient
 */
internal class CleanTabLensSpot {
    private val runtimeShader: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        runCatching { RuntimeShader(SpotShaderSource) }.getOrNull()
    } else {
        null
    }
    private val fallbackPaint by lazy {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            isDither = true
            xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        }
    }

    /** Draws the glow at lens-local x [touchX] with [pressed] from 0 (none) to 1 (pressed). */
    fun draw(scope: DrawScope, touchX: Float, pressed: Float, dark: Boolean) = with(scope) {
        if (pressed <= 0.01f || touchX.isNaN()) return@with
        val progress = if (dark) pressed else pressed * SpotLightThemeFactor
        val corner = CornerRadius(size.height / 2f)
        drawRoundRect(
            color = Color.White.copy(alpha = SpotWashAlpha * progress),
            cornerRadius = corner,
            blendMode = BlendMode.Plus
        )
        val center = Offset(touchX.coerceIn(0f, size.width), size.height / 2f)
        val radius = (size.minDimension * SpotRadiusFactor).coerceAtLeast(1f)
        val color = Color.White.copy(alpha = SpotAlpha * progress)
        val shader = runtimeShader
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shader is RuntimeShader) {
            drawRoundRect(
                brush = smoothstepBrush(shader, color, center, radius),
                cornerRadius = corner,
                blendMode = BlendMode.Plus
            )
        } else {
            drawFallbackSpot(color, center, radius)
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun smoothstepBrush(shader: RuntimeShader, color: Color, center: Offset, radius: Float): ShaderBrush {
        shader.setColorUniform("color", color.toArgb())
        shader.setFloatUniform("radius", radius)
        shader.setFloatUniform("solid", SpotSolidFraction)
        shader.setFloatUniform("position", center.x, center.y)
        return ShaderBrush(shader)
    }

    private fun DrawScope.drawFallbackSpot(color: Color, center: Offset, radius: Float) {
        val argb = color.toArgb()
        fallbackPaint.shader = RadialGradient(
            center.x, center.y, radius,
            intArrayOf(argb, argb, Color.Transparent.toArgb()),
            floatArrayOf(0f, SpotSolidFraction, 1f),
            Shader.TileMode.CLAMP
        )
        val corner = size.height / 2f
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, corner, corner, fallbackPaint)
        }
    }
}

private const val SpotShaderSource = """
layout(color) uniform half4 color;
uniform float radius;
uniform float solid;
uniform float2 position;

half4 main(float2 coord) {
    float dist = distance(coord, position);
    float intensity = smoothstep(radius, radius * solid, dist);
    return color * intensity;
}
"""
