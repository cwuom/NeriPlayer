package moe.ouom.neriplayer.ui.component.navigation

// Shader adapted from Kyant0/AndroidLiquidGlass (https://github.com/Kyant0/AndroidLiquidGlass,
// Apache-2.0), via the port in the np-submodule/miuix example.

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect

/**
 * Thick-glass refraction for the enhanced tab lens: content inside a bevel band bends toward the
 * centre along a circular profile, the whole lens bulges a little (depth), and the rim splits
 * into seven chromatic taps. Needs RuntimeShader, so it is absent below Android 13
 */
internal class CleanTabLensRefraction {
    private val shader: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        runCatching { RuntimeShader(LiquidLensShaderSource) }.getOrNull()
    } else {
        null
    }

    fun effect(
        width: Float,
        height: Float,
        refractionHeight: Float,
        refractionAmount: Float,
        chromaticAberration: Float
    ): RenderEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        if (refractionHeight <= 0f || refractionAmount <= 0f || width <= 0f || height <= 0f) return null
        val runtime = shader as? RuntimeShader ?: return null
        return build(runtime, width, height, refractionHeight, refractionAmount, chromaticAberration)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun build(
        runtime: RuntimeShader,
        width: Float,
        height: Float,
        refractionHeight: Float,
        refractionAmount: Float,
        chromaticAberration: Float
    ): RenderEffect {
        val radius = minOf(width, height) / 2f
        runtime.setFloatUniform("size", width, height)
        runtime.setFloatUniform("cornerRadius", radius)
        runtime.setFloatUniform("refractionHeight", refractionHeight)
        // 负值让采样点向内偏移，边缘内容被拉伸外扩，读作凸透镜
        runtime.setFloatUniform("refractionAmount", -refractionAmount)
        runtime.setFloatUniform("chromaticAberration", chromaticAberration)
        return android.graphics.RenderEffect
            .createRuntimeShaderEffect(runtime, "content")
            .asComposeRenderEffect()
    }
}

private const val LiquidLensShaderSource = """
uniform shader content;
uniform float2 size;
uniform float cornerRadius;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float chromaticAberration;

float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        return sign(coord) * normalize(max(cornerCoord, 0.0));
    }
    float gradX = step(cornerCoord.y, cornerCoord.x);
    return sign(coord) * float2(gradX, 1.0 - gradX);
}

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centered = coord - halfSize;
    float sd = sdRoundedRect(centered, halfSize, cornerRadius);
    if (-sd >= refractionHeight) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);
    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(cornerRadius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centered, halfSize, gradRadius) + normalize(centered + float2(0.0001)));
    float2 refracted = coord + d * grad;
    float intensity = chromaticAberration * ((centered.x * centered.y) / (halfSize.x * halfSize.y));
    float2 dispersed = d * grad * intensity;

    half4 color = half4(0.0);
    half4 red = content.eval(refracted + dispersed);
    color.r += red.r / 3.5;
    color.a += red.a / 7.0;
    half4 orange = content.eval(refracted + dispersed * (2.0 / 3.0));
    color.r += orange.r / 3.5;
    color.g += orange.g / 7.0;
    color.a += orange.a / 7.0;
    half4 yellow = content.eval(refracted + dispersed * (1.0 / 3.0));
    color.r += yellow.r / 3.5;
    color.g += yellow.g / 3.5;
    color.a += yellow.a / 7.0;
    half4 green = content.eval(refracted);
    color.g += green.g / 3.5;
    color.a += green.a / 7.0;
    half4 cyan = content.eval(refracted - dispersed * (1.0 / 3.0));
    color.g += cyan.g / 3.5;
    color.b += cyan.b / 3.0;
    color.a += cyan.a / 7.0;
    half4 blue = content.eval(refracted - dispersed * (2.0 / 3.0));
    color.b += blue.b / 3.0;
    color.a += blue.a / 7.0;
    half4 purple = content.eval(refracted - dispersed);
    color.r += purple.r / 7.0;
    color.b += purple.b / 3.0;
    color.a += purple.a / 7.0;
    return color;
}
"""
