package moe.ouom.neriplayer.ui.effect.glass

import moe.ouom.neriplayer.data.settings.DEFAULT_ENHANCED_ADVANCED_BLUR_RADIUS_DP
import moe.ouom.neriplayer.data.settings.AdvancedBlurQuality
import moe.ouom.neriplayer.data.settings.EnhancedAdvancedBlurPreference

internal const val ADVANCED_GLASS_MIN_SDK = ADVANCED_GLASS_BACKEND_MIN_SDK

internal data class AdvancedGlassController(
    val sdkInt: Int,
    val advancedBlurEnabled: Boolean,
    val enhancedAdvancedBlurEnabled: Boolean,
    val backendReady: Boolean,
    val sessionHealthy: Boolean = true,
    val enhancedAdvancedBlurRadiusDp: Float = DEFAULT_ENHANCED_ADVANCED_BLUR_RADIUS_DP,
    val advancedBlurQuality: AdvancedBlurQuality = AdvancedBlurQuality.Default
) {
    val isBaseBlurRequested: Boolean
        get() = sdkInt >= ADVANCED_GLASS_MIN_SDK &&
            advancedBlurEnabled &&
            backendReady

    val isBaseBlurEnabled: Boolean
        get() = isBaseBlurRequested && sessionHealthy

    val isEnabled: Boolean
        get() = isBaseBlurEnabled &&
            enhancedAdvancedBlurEnabled

    val normalizedEnhancedBlurRadiusDp: Float
        get() = EnhancedAdvancedBlurPreference.normalize(enhancedAdvancedBlurRadiusDp)

    val normalizedBlurAmountDp: Float
        get() = normalizedEnhancedBlurRadiusDp

    fun afterBackendFailure(): AdvancedGlassController = copy(sessionHealthy = false)
}

internal fun shouldShowAdvancedBlurSettings(
    sdkInt: Int,
    advancedBlurEnabled: Boolean
): Boolean = sdkInt >= ADVANCED_GLASS_MIN_SDK && advancedBlurEnabled

internal fun canSampleAdvancedGlassBackdrop(
    controller: AdvancedGlassController,
    glassDepth: Int,
    role: AdvancedGlassRole
): Boolean {
    val roleEnabled = when (role) {
        AdvancedGlassRole.MiniPlayer,
        AdvancedGlassRole.BottomNavigation -> controller.isBaseBlurEnabled
        else -> controller.isEnabled && controller.advancedBlurQuality.supports(role)
    }
    return roleEnabled && glassDepth == 0 && advancedGlassTokens(role, false).samplesBackdrop
}

internal fun AdvancedBlurQuality.supports(role: AdvancedGlassRole): Boolean = when (this) {
    AdvancedBlurQuality.UltraLow,
    AdvancedBlurQuality.Low,
    AdvancedBlurQuality.Default -> role != AdvancedGlassRole.ExploreTag &&
        role != AdvancedGlassRole.ThemeModeToggle &&
        role != AdvancedGlassRole.InlineControl
    AdvancedBlurQuality.High -> role != AdvancedGlassRole.InlineControl
}
