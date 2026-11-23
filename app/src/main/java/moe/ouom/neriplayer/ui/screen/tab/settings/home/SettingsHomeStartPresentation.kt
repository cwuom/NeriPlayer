package moe.ouom.neriplayer.ui.screen.tab.settings.home

import android.content.res.Resources
import androidx.compose.runtime.Composable
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.home.InternationalHomeLayout
import moe.ouom.neriplayer.data.model.settings.home.InternationalHomeSectionIds
import moe.ouom.neriplayer.R as AppR

internal val internationalHomeSectionTitles = mapOf(
    InternationalHomeSectionIds.SPOTLIGHT to CoreCommonR.string.international_home_spotlight,
    InternationalHomeSectionIds.CONTINUE to CoreCommonR.string.player_continue,
    InternationalHomeSectionIds.FEED to CoreCommonR.string.international_home_feed
)

internal fun isSettingsHomeStartAvailable(
    showTrending: Boolean,
    showRadar: Boolean,
    showRecommended: Boolean,
    showContinue: Boolean,
    hasRecentUsage: Boolean,
    showBilibili: Boolean = false,
    internationalLayout: InternationalHomeLayout? = null
): Boolean = internationalLayout?.isHomeAvailable(hasRecentUsage)
    ?: (showTrending || showRadar || showRecommended || showBilibili || (showContinue && hasRecentUsage))

internal fun effectiveSettingsStartDestination(
    configured: String,
    homeAvailable: Boolean
): String = if (!homeAvailable && configured == "home") "explore" else configured

private val settingsStartDestinationLabels = mapOf(
    "explore" to AppR.string.nav_explore,
    "library" to AppR.string.nav_library,
    "settings" to AppR.string.nav_settings
)

internal fun settingsStartDestinationLabelRes(destination: String): Int =
    settingsStartDestinationLabels[destination] ?: AppR.string.nav_home

internal data class SettingsHomeStartPresentation(
    val available: Boolean,
    val destination: String,
    val destinationLabel: String
)

@Composable
internal fun rememberSettingsHomeStartPresentation(
    resources: Resources,
    configuredDestination: String,
    internationalLayout: InternationalHomeLayout?,
    showTrending: Boolean,
    showRadar: Boolean,
    showRecommended: Boolean,
    showContinue: Boolean,
    hasRecentUsage: Boolean,
    showBilibili: Boolean
): SettingsHomeStartPresentation {
    val available = isSettingsHomeStartAvailable(
        showTrending,
        showRadar,
        showRecommended,
        showContinue,
        hasRecentUsage,
        showBilibili,
        internationalLayout
    )
    val destination = effectiveSettingsStartDestination(configuredDestination, available)
    return SettingsHomeStartPresentation(
        available = available,
        destination = destination,
        destinationLabel = resources.getString(settingsStartDestinationLabelRes(destination))
    )
}
