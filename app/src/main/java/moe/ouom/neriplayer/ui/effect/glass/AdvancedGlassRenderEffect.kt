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
    return createAdvancedGlassRenderEffectSession(sdkInt).update(radiusPx, regions)
}

internal interface AdvancedGlassRenderEffectSession {
    fun update(
        radiusPx: Float,
        regions: List<AdvancedGlassRenderRegion>
    ): RenderEffect?
}

internal fun createAdvancedGlassRenderEffectSession(
    sdkInt: Int
): AdvancedGlassRenderEffectSession {
    if (Build.VERSION.SDK_INT < ADVANCED_GLASS_BACKEND_MIN_SDK ||
        !isAdvancedGlassBackendSupported(sdkInt)
    ) {
        return UnsupportedAdvancedGlassRenderEffectSession
    }
    return AdvancedGlassRuntimeShaderSession()
}

private object UnsupportedAdvancedGlassRenderEffectSession : AdvancedGlassRenderEffectSession {
    override fun update(
        radiusPx: Float,
        regions: List<AdvancedGlassRenderRegion>
    ): RenderEffect? = null
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class AdvancedGlassRuntimeShaderSession : AdvancedGlassRenderEffectSession {
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
        val activeBackend = backend ?: AdvancedGlassRuntimeShaderBackend.Session().also {
            backend = it
        }
        return activeBackend.update(radiusPx, regions)
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object AdvancedGlassRuntimeShaderBackend {
    class Session {
        private val regionBounds = FloatArray(
            ADVANCED_GLASS_MAX_REGIONS * RegionComponentCount
        )
        private val cornerRadii = FloatArray(
            ADVANCED_GLASS_MAX_REGIONS * RegionComponentCount
        )
        private val maskShader = createMaskShader(invertMask = false)
        private val outsideShader = createMaskShader(invertMask = true)
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

        private fun createMaskShader(invertMask: Boolean) = RuntimeShader(RegionMaskShader).apply {
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

    private const val RegionMaskShader = """
        uniform shader child;
        uniform float regionCount;
        uniform float4 regionBounds[32];
        uniform float4 cornerRadii[32];
        uniform float invertMask;

        float roundedRectDistance(float2 position, float4 bounds, float4 radii) {
            float2 center = (bounds.xy + bounds.zw) * 0.5;
            float2 halfSize = max((bounds.zw - bounds.xy) * 0.5, float2(0.0));
            float radius = position.y < center.y
                ? (position.x < center.x ? radii.x : radii.y)
                : (position.x < center.x ? radii.w : radii.z);
            float safeRadius = clamp(radius, 0.0, min(halfSize.x, halfSize.y));
            float2 offset = abs(position - center) - halfSize + safeRadius;
            return length(max(offset, float2(0.0))) +
                min(max(offset.x, offset.y), 0.0) - safeRadius;
        }

        half4 main(float2 position) {
            float coverage = 0.0;
            for (int index = 0; index < 32; index++) {
                if (float(index) >= regionCount) {
                    break;
                }
                float distance = roundedRectDistance(
                    position,
                    regionBounds[index],
                    cornerRadii[index]
                );
                coverage = max(coverage, clamp(0.5 - distance, 0.0, 1.0));
            }
            float resolvedCoverage = mix(coverage, 1.0 - coverage, invertMask);
            return child.eval(position) * half(resolvedCoverage);
        }
    """
}
