package moe.ouom.neriplayer.ui.effect.glass

import android.graphics.BlendMode
import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import org.junit.Assert.assertEquals

internal fun frozenAdvancedGlassMaskSource(): String {
    val bytes = InstrumentationRegistry.getInstrumentation().context.assets
        .open("shaders/advanced_glass_region_mask_frozen_reference.agsl").use { it.readBytes() }
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    assertEquals(
        "frozen glass reference changed",
        "fdb48c6ff609a46215781ba50e9a55465fe3b351b4c2f1408ebe47b5afb20f48",
        digest
    )
    return bytes.toString(Charsets.UTF_8)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class FrozenAdvancedGlassEffectSession(
    private val source: String
) : AdvancedGlassRenderEffectSession {
    private var blurRadius = Float.NaN
    private var blurEffect: AndroidRenderEffect? = null

    override fun update(
        radiusPx: Float,
        regions: List<AdvancedGlassRenderRegion>,
        lens: AdvancedGlassLensSpec
    ): RenderEffect? {
        if (!radiusPx.isFinite() || radiusPx <= 0f || regions.isEmpty()) return null
        require(regions.size <= 32)
        val bounds = FloatArray(128)
        val corners = FloatArray(128)
        val opacity = FloatArray(32)
        val regionLens = FloatArray(32)
        regions.forEachIndexed { index, region ->
            val offset = index * 4
            bounds[offset] = region.left
            bounds[offset + 1] = region.top
            bounds[offset + 2] = region.right
            bounds[offset + 3] = region.bottom
            corners[offset] = region.cornerRadiiPx.topLeft.coerceAtLeast(0f)
            corners[offset + 1] = region.cornerRadiiPx.topRight.coerceAtLeast(0f)
            corners[offset + 2] = region.cornerRadiiPx.bottomRight.coerceAtLeast(0f)
            corners[offset + 3] = region.cornerRadiiPx.bottomLeft.coerceAtLeast(0f)
            opacity[index] = region.opacity.coerceIn(0f, 1f)
            regionLens[index] = region.lens.coerceIn(0f, 1f)
        }
        val lensParams = floatArrayOf(
            lens.refraction.coerceAtLeast(0f),
            lens.edgeBandPx.coerceAtLeast(0f),
            lens.rimPx.coerceAtLeast(0f),
            lens.rimHighlight.coerceAtLeast(0f)
        )
        val activeBlur = blurEffect?.takeIf { blurRadius == radiusPx }
            ?: AndroidRenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP).also {
                blurRadius = radiusPx
                blurEffect = it
            }
        fun mask(invert: Boolean): RuntimeShader = RuntimeShader(source).apply {
            setFloatUniform("regionCount", regions.size.toFloat())
            setFloatUniform("regionBounds", bounds)
            setFloatUniform("cornerRadii", corners)
            setFloatUniform("regionOpacity", opacity)
            setFloatUniform("regionLens", regionLens)
            setFloatUniform("lensParams", lensParams)
            setFloatUniform("invertMask", if (invert) 1f else 0f)
        }
        val positive = AndroidRenderEffect.createRuntimeShaderEffect(mask(false), "child")
        val outside = AndroidRenderEffect.createRuntimeShaderEffect(mask(true), "child")
        val maskedBlur = AndroidRenderEffect.createChainEffect(positive, activeBlur)
        return AndroidRenderEffect.createBlendModeEffect(outside, maskedBlur, BlendMode.PLUS)
            .asComposeRenderEffect()
    }
}
