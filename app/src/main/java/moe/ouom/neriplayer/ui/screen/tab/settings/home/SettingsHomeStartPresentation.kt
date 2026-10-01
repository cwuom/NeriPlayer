package moe.ouom.neriplayer.ui.screen.tab.settings.home

import android.content.res.Resources
import androidx.compose.runtime.Composable
import moe.ouom.neriplayer.core.common.R as CoreCommonR

internal data class SettingsHomeCardCopy(
    val trendingLabelRes: Int,
    val radarLabelRes: Int,
    val recommendedLabelRes: Int,
    val trendingSupportingRes: Int,
    val radarSupportingRes: Int,
    val recommendedSupportingRes: Int
)

internal fun settingsHomeCardCopy(internationalEnabled: Boolean): SettingsHomeCardCopy =
    if (internationalEnabled) {
        SettingsHomeCardCopy(
            trendingLabelRes = CoreCommonR.string.home_ytmusic_guess_you_like,
            radarLabelRes = CoreCommonR.string.home_ytmusic_daily_discover,
            recommendedLabelRes = CoreCommonR.string.home_ytmusic_more_recommendations,
            trendingSupportingRes = CoreCommonR.string.settings_home_card_ytmusic_guess_you_like_desc,
            radarSupportingRes = CoreCommonR.string.settings_home_card_ytmusic_daily_discover_desc,
            recommendedSupportingRes = CoreCommonR.string.settings_home_card_ytmusic_more_recommendations_desc
        )
    } else {
        SettingsHomeCardCopy(
            trendingLabelRes = CoreCommonR.string.settings_home_card_netease_trending,
            radarLabelRes = CoreCommonR.string.settings_home_card_netease_radar,
            recommendedLabelRes = CoreCommonR.string.settings_home_card_netease_recommended,
            trendingSupportingRes = CoreCommonR.string.settings_home_card_netease_trending_desc,
            radarSupportingRes = CoreCommonR.string.settings_home_card_netease_radar_desc,
            recommendedSupportingRes = CoreCommonR.string.settings_home_card_netease_recommended_desc
        )
    }

internal fun isSettingsHomeStartAvailable(
    showTrending: Boolean,
    showRadar: Boolean,
    showRecommended: Boolean,
    showContinue: Boolean,
    hasRecentUsage: Boolean
): Boolean = showTrending || showRadar || showRecommended || (showContinue && hasRecentUsage)

internal fun effectiveSettingsStartDestination(
    configured: String,
    homeAvailable: Boolean
): String = if (!homeAvailable && configured == "home") "explore" else configured

private val settingsStartDestinationLabels = mapOf(
    "explore" to CoreCommonR.string.nav_explore,
    "library" to CoreCommonR.string.nav_library,
    "settings" to CoreCommonR.string.nav_settings
)

internal fun settingsStartDestinationLabelRes(destination: String): Int =
    settingsStartDestinationLabels[destination] ?: CoreCommonR.string.nav_home

internal data class SettingsHomeStartPresentation(
    val available: Boolean,
    val copy: SettingsHomeCardCopy,
    val destination: String,
    val destinationLabel: String
)

@Composable
internal fun rememberSettingsHomeStartPresentation(
    resources: Resources,
    configuredDestination: String,
    internationalEnabled: Boolean,
    showTrending: Boolean,
    showRadar: Boolean,
    showRecommended: Boolean,
    showContinue: Boolean,
    hasRecentUsage: Boolean
): SettingsHomeStartPresentation {
    val available = isSettingsHomeStartAvailable(
        showTrending,
        showRadar,
        showRecommended,
        showContinue,
        hasRecentUsage
    )
    val destination = effectiveSettingsStartDestination(configuredDestination, available)
    return SettingsHomeStartPresentation(
        available = available,
        copy = settingsHomeCardCopy(internationalEnabled),
        destination = destination,
        destinationLabel = resources.getString(settingsStartDestinationLabelRes(destination))
    )
}
