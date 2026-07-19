package moe.ouom.neriplayer.ui.effect.glass

import moe.ouom.neriplayer.data.settings.EnhancedAdvancedBlurPreference

internal data class AdvancedGlassTokens(
    val blurRadiusDp: Float,
    val tintAlpha: Float,
    val edgeAlpha: Float,
    val samplesBackdrop: Boolean = true
)

internal fun advancedGlassTokens(
    role: AdvancedGlassRole,
    isDarkTheme: Boolean,
    enhancedBlurRadiusDp: Float? = null
): AdvancedGlassTokens {
    val adjustableRadiusDp = enhancedBlurRadiusDp?.let(EnhancedAdvancedBlurPreference::normalize)
    return when (role) {
        AdvancedGlassRole.MiniPlayer -> AdvancedGlassTokens(
            blurRadiusDp = adjustableRadiusDp ?: 24f,
            tintAlpha = if (isDarkTheme) 0.30f else 0.36f,
            edgeAlpha = 0.10f
        )
        AdvancedGlassRole.BottomNavigation -> AdvancedGlassTokens(
            blurRadiusDp = adjustableRadiusDp ?: 28f,
            tintAlpha = 0.75f,
            edgeAlpha = 0.12f
        )
        AdvancedGlassRole.ScreenTopTab -> AdvancedGlassTokens(
            blurRadiusDp = adjustableRadiusDp ?: 22f,
            tintAlpha = if (isDarkTheme) 0.16f else 0.18f,
            edgeAlpha = 0.10f
        )
        AdvancedGlassRole.SettingsGroup -> AdvancedGlassTokens(
            blurRadiusDp = adjustableRadiusDp ?: 26f,
            tintAlpha = if (isDarkTheme) 0.30f else 0.34f,
            edgeAlpha = 0.12f
        )
        AdvancedGlassRole.SettingsHeader -> AdvancedGlassTokens(
            blurRadiusDp = adjustableRadiusDp ?: 24f,
            tintAlpha = if (isDarkTheme) 0.28f else 0.32f,
            edgeAlpha = 0.12f
        )
        AdvancedGlassRole.SettingsSection -> AdvancedGlassTokens(
            blurRadiusDp = adjustableRadiusDp ?: 24f,
            tintAlpha = if (isDarkTheme) 0.28f else 0.32f,
            edgeAlpha = 0.10f
        )
        AdvancedGlassRole.SemanticCard -> AdvancedGlassTokens(
            blurRadiusDp = adjustableRadiusDp ?: 22f,
            tintAlpha = if (isDarkTheme) 0.42f else 0.46f,
            edgeAlpha = 0.10f
        )
        AdvancedGlassRole.InlineControl -> AdvancedGlassTokens(
            blurRadiusDp = 0f,
            tintAlpha = 1f,
            edgeAlpha = 0f,
            samplesBackdrop = false
        )
    }
}
