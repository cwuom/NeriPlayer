package moe.ouom.neriplayer.ui.effect.glass

import moe.ouom.neriplayer.data.settings.AdvancedBlurQuality

internal enum class AdvancedGlassBlurAlgorithm {
    Native,
    SeparableGaussian
}

internal data class AdvancedGlassRenderProfile(
    val algorithm: AdvancedGlassBlurAlgorithm,
    val sampleCount: Int = 0,
    val sampleSpacingMultiplier: Float = 0f
) {
    init {
        if (algorithm == AdvancedGlassBlurAlgorithm.SeparableGaussian) {
            require(sampleCount in 1..MaxReducedSampleCount)
            require(sampleSpacingMultiplier > 0f)
        }
    }

    fun sampleSpacingPx(radiusPx: Float): Float =
        (radiusPx * sampleSpacingMultiplier).coerceAtLeast(1f)

    companion object {
        const val MaxReducedSampleCount = 5

        val Native = AdvancedGlassRenderProfile(AdvancedGlassBlurAlgorithm.Native)
        val UltraLow = AdvancedGlassRenderProfile(
            algorithm = AdvancedGlassBlurAlgorithm.SeparableGaussian,
            sampleCount = 3,
            sampleSpacingMultiplier = 0.28f
        )
        val Low = AdvancedGlassRenderProfile(
            algorithm = AdvancedGlassBlurAlgorithm.SeparableGaussian,
            sampleCount = 5,
            sampleSpacingMultiplier = 0.30f
        )
    }
}

internal fun AdvancedBlurQuality.renderProfile(): AdvancedGlassRenderProfile = when (this) {
    AdvancedBlurQuality.UltraLow -> AdvancedGlassRenderProfile.UltraLow
    AdvancedBlurQuality.Low -> AdvancedGlassRenderProfile.Low
    AdvancedBlurQuality.Default,
    AdvancedBlurQuality.High -> AdvancedGlassRenderProfile.Native
}
