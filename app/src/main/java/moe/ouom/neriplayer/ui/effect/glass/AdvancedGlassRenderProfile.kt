package moe.ouom.neriplayer.ui.effect.glass

import moe.ouom.neriplayer.data.settings.AdvancedBlurQuality

internal enum class AdvancedGlassBlurAlgorithm {
    Native
}

internal enum class AdvancedGlassRenderPipeline {
    FullscreenMask,
    RegionLocal
}

internal data class AdvancedGlassRenderProfile(
    val algorithm: AdvancedGlassBlurAlgorithm,
    val pipeline: AdvancedGlassRenderPipeline = AdvancedGlassRenderPipeline.FullscreenMask,
    val maximumMergedInputAreaRatio: Float = 1f,
    val maximumDownscaleFactor: Int = 1
) {
    init {
        require(maximumMergedInputAreaRatio >= 1f)
        require(maximumDownscaleFactor in SupportedDownscaleFactors)
    }

    val usesRegionLocalRendering: Boolean
        get() = pipeline == AdvancedGlassRenderPipeline.RegionLocal

    fun downscaleFactorFor(radiusPx: Float): Int {
        if (!usesRegionLocalRendering || !radiusPx.isFinite()) return 1
        return when {
            maximumDownscaleFactor >= 4 && radiusPx >= UltraLowFourXThresholdPx -> 4
            maximumDownscaleFactor >= 2 && radiusPx >= LowTwoXThresholdPx -> 2
            else -> 1
        }
    }

    companion object {
        private val SupportedDownscaleFactors = setOf(1, 2, 4)
        private const val LowTwoXThresholdPx = 18f
        private const val UltraLowFourXThresholdPx = 48f

        val Native = AdvancedGlassRenderProfile(AdvancedGlassBlurAlgorithm.Native)
        val UltraLow = AdvancedGlassRenderProfile(
            algorithm = AdvancedGlassBlurAlgorithm.Native,
            pipeline = AdvancedGlassRenderPipeline.RegionLocal,
            maximumMergedInputAreaRatio = 1.20f,
            maximumDownscaleFactor = 4
        )
        val Low = AdvancedGlassRenderProfile(
            algorithm = AdvancedGlassBlurAlgorithm.Native,
            pipeline = AdvancedGlassRenderPipeline.RegionLocal,
            maximumMergedInputAreaRatio = 1.08f,
            maximumDownscaleFactor = 2
        )
    }
}

internal fun AdvancedBlurQuality.renderProfile(): AdvancedGlassRenderProfile = when (this) {
    AdvancedBlurQuality.UltraLow -> AdvancedGlassRenderProfile.UltraLow
    AdvancedBlurQuality.Low -> AdvancedGlassRenderProfile.Low
    AdvancedBlurQuality.Default,
    AdvancedBlurQuality.High -> AdvancedGlassRenderProfile.Native
}
