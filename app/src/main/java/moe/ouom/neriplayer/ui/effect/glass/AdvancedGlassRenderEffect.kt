package moe.ouom.neriplayer.ui.effect.glass

import android.graphics.BlendMode
import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import moe.ouom.neriplayer.core.logging.NPLogger

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
    renderProfile: AdvancedGlassRenderProfile = AdvancedGlassRenderProfile.Native,
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
        .update(radiusPx, renderProfile, regions)
}

internal interface AdvancedGlassRenderEffectSession {
    fun update(
        radiusPx: Float,
        renderProfile: AdvancedGlassRenderProfile,
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
        renderProfile: AdvancedGlassRenderProfile,
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
        renderProfile: AdvancedGlassRenderProfile,
        regions: List<AdvancedGlassRenderRegion>
    ): RenderEffect? {
        if (!radiusPx.isFinite() || radiusPx <= 0f || regions.isEmpty()) {
            return null
        }
        require(regions.size <= ADVANCED_GLASS_MAX_REGIONS) {
            "Advanced glass supports at most $ADVANCED_GLASS_MAX_REGIONS visible regions"
        }
        val activeBackend = backend ?: AdvancedGlassRuntimeShaderBackend.Session(
            maskShaderSource = shaderSource.load(),
            reducedQualityShaderSource = shaderSource::loadReducedQuality,
            ultraLowQualityShaderSource = shaderSource::loadUltraLowQuality
        ).also { backend = it }
        return activeBackend.update(radiusPx, renderProfile, regions)
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object AdvancedGlassRuntimeShaderBackend {
    class Session(
        private val maskShaderSource: String,
        private val reducedQualityShaderSource: () -> String,
        private val ultraLowQualityShaderSource: () -> String
    ) {
        private val unavailableReducedSampleCounts = mutableSetOf<Int>()
        private var cachedBlurRadiusPx = Float.NaN
        private var cachedBlurEffect: AndroidRenderEffect? = null
        private var cachedReducedBlurKey: ReducedBlurCacheKey? = null
        private var cachedReducedBlurEffect: AndroidRenderEffect? = null

        fun update(
            radiusPx: Float,
            renderProfile: AdvancedGlassRenderProfile,
            regions: List<AdvancedGlassRenderRegion>
        ): RenderEffect {
            val regionUniforms = regionUniforms(regions)
            return when (renderProfile.algorithm) {
                AdvancedGlassBlurAlgorithm.Native -> resolveNativeEffect(
                    regionUniforms = regionUniforms,
                    radiusPx = radiusPx
                )
                AdvancedGlassBlurAlgorithm.SeparableGaussian -> {
                    resolveReducedQualityEffect(
                        regionUniforms = regionUniforms,
                        renderProfile = renderProfile,
                        radiusPx = radiusPx
                    ) ?: resolveNativeEffect(
                        regionUniforms = regionUniforms,
                        radiusPx = radiusPx
                    )
                }
            }
        }

        private fun regionUniforms(
            regions: List<AdvancedGlassRenderRegion>
        ): AdvancedGlassRegionUniforms {
            val regionBounds = FloatArray(ADVANCED_GLASS_MAX_REGIONS * RegionComponentCount)
            val cornerRadii = FloatArray(ADVANCED_GLASS_MAX_REGIONS * RegionComponentCount)
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
            return AdvancedGlassRegionUniforms(
                count = regions.size,
                bounds = regionBounds,
                cornerRadii = cornerRadii
            )
        }

        private fun resolveNativeEffect(
            regionUniforms: AdvancedGlassRegionUniforms,
            radiusPx: Float
        ): RenderEffect {
            val blurEffect = cachedBlurEffect?.takeIf { cachedBlurRadiusPx == radiusPx }
                ?: AndroidRenderEffect.createBlurEffect(
                    radiusPx,
                    radiusPx,
                    Shader.TileMode.CLAMP
                ).also { effect ->
                    cachedBlurRadiusPx = radiusPx
                    cachedBlurEffect = effect
                }
            return applyRegionMask(blurEffect, regionUniforms)
        }

        private fun resolveReducedQualityEffect(
            regionUniforms: AdvancedGlassRegionUniforms,
            renderProfile: AdvancedGlassRenderProfile,
            radiusPx: Float
        ): RenderEffect? {
            val sampleCount = renderProfile.sampleCount
            if (sampleCount in unavailableReducedSampleCounts) {
                return null
            }
            return try {
                val blurEffect = resolveCachedReducedBlurEffect(renderProfile, radiusPx)
                applyRegionMask(blurEffect, regionUniforms)
            } catch (error: RuntimeException) {
                unavailableReducedSampleCounts += sampleCount
                NPLogger.w(
                    AdvancedGlassLogTag,
                    "reduced quality shader unavailable; falling back to native blur",
                    error
                )
                null
            }
        }

        private fun resolveCachedReducedBlurEffect(
            renderProfile: AdvancedGlassRenderProfile,
            radiusPx: Float
        ): AndroidRenderEffect {
            val sampleSpacingPx = renderProfile.sampleSpacingPx(radiusPx)
            val cacheKey = ReducedBlurCacheKey(
                sampleCount = renderProfile.sampleCount,
                sampleSpacingPx = sampleSpacingPx
            )
            return cachedReducedBlurEffect?.takeIf { cachedReducedBlurKey == cacheKey }
                ?: createSeparableGaussianBlurEffect(
                    shaderSource = resolveReducedQualityShaderSource(renderProfile.sampleCount),
                    sampleSpacingPx = sampleSpacingPx
                ).also { effect ->
                    cachedReducedBlurKey = cacheKey
                    cachedReducedBlurEffect = effect
                }
        }

        private fun resolveReducedQualityShaderSource(sampleCount: Int): String = when (
            sampleCount
        ) {
            UltraLowSampleCount -> ultraLowQualityShaderSource()
            LowSampleCount -> reducedQualityShaderSource()
            else -> error("Unsupported reduced-quality gaussian kernel: $sampleCount")
        }

        private fun createSeparableGaussianBlurEffect(
            shaderSource: String,
            sampleSpacingPx: Float
        ): AndroidRenderEffect {
            val horizontalEffect = AndroidRenderEffect.createRuntimeShaderEffect(
                createReducedQualityShader(
                    shaderSource = shaderSource,
                    axisX = 1f,
                    axisY = 0f,
                    sampleSpacingPx = sampleSpacingPx
                ),
                ChildShaderUniform
            )
            val verticalEffect = AndroidRenderEffect.createRuntimeShaderEffect(
                createReducedQualityShader(
                    shaderSource = shaderSource,
                    axisX = 0f,
                    axisY = 1f,
                    sampleSpacingPx = sampleSpacingPx
                ),
                ChildShaderUniform
            )
            return AndroidRenderEffect.createChainEffect(verticalEffect, horizontalEffect)
        }

        private fun applyRegionMask(
            blurEffect: AndroidRenderEffect,
            regionUniforms: AdvancedGlassRegionUniforms
        ): RenderEffect {
            // some GPU backends capture uniforms on the first effect bind, so each
            // mask shader begins with the current region data rather than an empty mask
            val maskEffect = AndroidRenderEffect.createRuntimeShaderEffect(
                createMaskShader(regionUniforms, invertMask = false),
                ChildShaderUniform
            )
            val outsideOriginalEffect = AndroidRenderEffect.createRuntimeShaderEffect(
                createMaskShader(regionUniforms, invertMask = true),
                ChildShaderUniform
            )
            val maskedBlurEffect = AndroidRenderEffect.createChainEffect(maskEffect, blurEffect)
            return AndroidRenderEffect.createBlendModeEffect(
                outsideOriginalEffect,
                maskedBlurEffect,
                BlendMode.SRC_OVER
            ).asComposeRenderEffect()
        }

        private fun createMaskShader(
            regionUniforms: AdvancedGlassRegionUniforms,
            invertMask: Boolean
        ) = RuntimeShader(maskShaderSource).apply {
            setFloatUniform(RegionCountUniform, regionUniforms.count.toFloat())
            setFloatUniform(RegionBoundsUniform, regionUniforms.bounds)
            setFloatUniform(CornerRadiiUniform, regionUniforms.cornerRadii)
            setFloatUniform(InvertMaskUniform, if (invertMask) 1f else 0f)
        }

        private fun createReducedQualityShader(
            shaderSource: String,
            axisX: Float,
            axisY: Float,
            sampleSpacingPx: Float
        ) = RuntimeShader(shaderSource).apply {
            setFloatUniform(ReducedSampleAxisUniform, axisX, axisY)
            setFloatUniform(ReducedSampleSpacingUniform, sampleSpacingPx)
        }

        private data class AdvancedGlassRegionUniforms(
            val count: Int,
            val bounds: FloatArray,
            val cornerRadii: FloatArray
        )

        private data class ReducedBlurCacheKey(
            val sampleCount: Int,
            val sampleSpacingPx: Float
        )
    }

    private const val RegionComponentCount = 4
    private const val ChildShaderUniform = "child"
    private const val RegionCountUniform = "regionCount"
    private const val RegionBoundsUniform = "regionBounds"
    private const val CornerRadiiUniform = "cornerRadii"
    private const val InvertMaskUniform = "invertMask"
    private const val ReducedSampleAxisUniform = "sampleAxis"
    private const val ReducedSampleSpacingUniform = "sampleSpacing"
    private const val AdvancedGlassLogTag = "AdvancedGlass"
    private const val UltraLowSampleCount = 3
    private const val LowSampleCount = 5
}
