package moe.ouom.neriplayer.ui.effect.glass

import android.graphics.BlendMode
import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect

internal const val ADVANCED_GLASS_BACKEND_MIN_SDK = Build.VERSION_CODES.TIRAMISU
internal const val ADVANCED_GLASS_MAX_REGIONS = 32

internal data class AdvancedGlassRenderRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val cornerRadiiPx: AdvancedGlassCornerRadii
)

internal fun isAdvancedGlassBackendSupported(sdkInt: Int): Boolean =
    sdkInt >= ADVANCED_GLASS_BACKEND_MIN_SDK

internal fun createAdvancedGlassRenderEffect(
    shaderSource: AdvancedGlassShaderSource,
    sdkInt: Int,
    radiusPx: Float,
    regions: List<AdvancedGlassRenderRegion>
): RenderEffect? {
    if (Build.VERSION.SDK_INT < ADVANCED_GLASS_BACKEND_MIN_SDK ||
        !isAdvancedGlassBackendSupported(sdkInt) ||
        !radiusPx.isFinite() ||
        radiusPx <= 0f ||
        regions.isEmpty()
    ) {
        return null
    }
    require(regions.size <= ADVANCED_GLASS_MAX_REGIONS) {
        "Advanced glass supports at most $ADVANCED_GLASS_MAX_REGIONS visible regions"
    }
    return createAdvancedGlassRenderEffectSession(shaderSource, sdkInt)
        .update(radiusPx, regions)
}

internal interface AdvancedGlassRenderEffectSession {
    fun update(
        radiusPx: Float,
        regions: List<AdvancedGlassRenderRegion>
    ): RenderEffect?
}

internal fun createAdvancedGlassRenderEffectSession(
    shaderSource: AdvancedGlassShaderSource,
    sdkInt: Int
): AdvancedGlassRenderEffectSession {
    if (Build.VERSION.SDK_INT < ADVANCED_GLASS_BACKEND_MIN_SDK ||
        !isAdvancedGlassBackendSupported(sdkInt)
    ) {
        return UnsupportedAdvancedGlassRenderEffectSession
    }
    return AdvancedGlassRuntimeShaderSession(shaderSource)
}

private object UnsupportedAdvancedGlassRenderEffectSession : AdvancedGlassRenderEffectSession {
    override fun update(
        radiusPx: Float,
        regions: List<AdvancedGlassRenderRegion>
    ): RenderEffect? = null
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class AdvancedGlassRuntimeShaderSession(
    private val shaderSource: AdvancedGlassShaderSource
) : AdvancedGlassRenderEffectSession {
    private var backend: AdvancedGlassRuntimeShaderBackend.Session? = null

    override fun update(
        radiusPx: Float,
        regions: List<AdvancedGlassRenderRegion>
    ): RenderEffect? {
        if (!radiusPx.isFinite() || radiusPx <= 0f || regions.isEmpty()) {
            return null
        }
        require(regions.size <= ADVANCED_GLASS_MAX_REGIONS) {
            "Advanced glass supports at most $ADVANCED_GLASS_MAX_REGIONS visible regions"
        }
        val activeBackend = backend ?: AdvancedGlassRuntimeShaderBackend.Session(
            shaderSource.load()
        ).also { backend = it }
        return activeBackend.update(radiusPx, regions)
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object AdvancedGlassRuntimeShaderBackend {
    class Session(shaderSource: String) {
        private val regionBounds = FloatArray(
            ADVANCED_GLASS_MAX_REGIONS * RegionComponentCount
        )
        private val cornerRadii = FloatArray(
            ADVANCED_GLASS_MAX_REGIONS * RegionComponentCount
        )
        private val maskShader = createMaskShader(shaderSource, invertMask = false)
        private val outsideShader = createMaskShader(shaderSource, invertMask = true)
        private var cachedRadiusPx = Float.NaN
        private var cachedBlurEffect: AndroidRenderEffect? = null

        fun update(
            radiusPx: Float,
            regions: List<AdvancedGlassRenderRegion>
        ): RenderEffect {
            updateRegionUniforms(regions)
            val blurEffect = cachedBlurEffect?.takeIf { cachedRadiusPx == radiusPx }
                ?: AndroidRenderEffect.createBlurEffect(
                    radiusPx,
                    radiusPx,
                    Shader.TileMode.CLAMP
                ).also { effect ->
                    cachedRadiusPx = radiusPx
                    cachedBlurEffect = effect
                }
            val maskEffect = AndroidRenderEffect.createRuntimeShaderEffect(
                maskShader,
                ChildShaderUniform
            )
            val outsideOriginalEffect = AndroidRenderEffect.createRuntimeShaderEffect(
                outsideShader,
                ChildShaderUniform
            )
            val maskedBlurEffect = AndroidRenderEffect.createChainEffect(
                maskEffect,
                blurEffect
            )
            return AndroidRenderEffect.createBlendModeEffect(
                outsideOriginalEffect,
                maskedBlurEffect,
                BlendMode.SRC_OVER
            ).asComposeRenderEffect()
        }

        private fun updateRegionUniforms(regions: List<AdvancedGlassRenderRegion>) {
            regions.forEachIndexed { index, region ->
                val offset = index * RegionComponentCount
                regionBounds[offset] = region.left
                regionBounds[offset + 1] = region.top
                regionBounds[offset + 2] = region.right
                regionBounds[offset + 3] = region.bottom
                cornerRadii[offset] = region.cornerRadiiPx.topLeft.coerceAtLeast(0f)
                cornerRadii[offset + 1] = region.cornerRadiiPx.topRight.coerceAtLeast(0f)
                cornerRadii[offset + 2] = region.cornerRadiiPx.bottomRight.coerceAtLeast(0f)
                cornerRadii[offset + 3] = region.cornerRadiiPx.bottomLeft.coerceAtLeast(0f)
            }
            updateShaderUniforms(maskShader, regions.size)
            updateShaderUniforms(outsideShader, regions.size)
        }

        private fun createMaskShader(
            shaderSource: String,
            invertMask: Boolean
        ) = RuntimeShader(shaderSource).apply {
            setFloatUniform(RegionCountUniform, 0f)
            setFloatUniform(RegionBoundsUniform, regionBounds)
            setFloatUniform(CornerRadiiUniform, cornerRadii)
            setFloatUniform(InvertMaskUniform, if (invertMask) 1f else 0f)
        }

        private fun updateShaderUniforms(shader: RuntimeShader, regionCount: Int) {
            shader.setFloatUniform(RegionCountUniform, regionCount.toFloat())
            shader.setFloatUniform(RegionBoundsUniform, regionBounds)
            shader.setFloatUniform(CornerRadiiUniform, cornerRadii)
        }
    }

    private const val RegionComponentCount = 4
    private const val ChildShaderUniform = "child"
    private const val RegionCountUniform = "regionCount"
    private const val RegionBoundsUniform = "regionBounds"
    private const val CornerRadiiUniform = "cornerRadii"
    private const val InvertMaskUniform = "invertMask"
}
