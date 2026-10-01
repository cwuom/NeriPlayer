package moe.ouom.neriplayer.ui.screen.tab.settings.playback

import android.content.Context
import androidx.compose.runtime.Composable
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.settings.playback.normalizeMobileDataBiliAudioQuality
import moe.ouom.neriplayer.data.settings.playback.normalizeMobileDataNeteaseAudioQuality
import moe.ouom.neriplayer.data.settings.playback.normalizeMobileDataYouTubeAudioQuality

internal fun neteaseQualityLabelRes(value: String): Int? = when (value) {
    "standard" -> CoreCommonR.string.settings_audio_quality_standard
    "higher" -> CoreCommonR.string.settings_audio_quality_higher
    "exhigh" -> CoreCommonR.string.settings_audio_quality_exhigh
    "lossless" -> CoreCommonR.string.settings_audio_quality_lossless
    "hires" -> CoreCommonR.string.quality_hires
    "jyeffect" -> CoreCommonR.string.settings_audio_quality_jyeffect
    "sky" -> CoreCommonR.string.settings_audio_quality_sky
    "jymaster" -> CoreCommonR.string.settings_audio_quality_jymaster
    else -> null
}

internal fun youtubeQualityLabelRes(value: String): Int? = when (value) {
    "low" -> CoreCommonR.string.settings_audio_quality_standard
    "medium" -> CoreCommonR.string.settings_audio_quality_medium
    "high" -> CoreCommonR.string.settings_audio_quality_high
    "very_high" -> CoreCommonR.string.quality_very_high
    else -> null
}

internal fun biliQualityLabelRes(value: String): Int? =
    biliPremiumQualityLabelRes(value) ?: biliRegularQualityLabelRes(value)

private fun biliPremiumQualityLabelRes(value: String): Int? = when (value) {
    "dolby" -> CoreCommonR.string.settings_audio_quality_dolby
    "hires" -> CoreCommonR.string.quality_hires
    "lossless" -> CoreCommonR.string.settings_audio_quality_lossless
    else -> null
}

private fun biliRegularQualityLabelRes(value: String): Int? = when (value) {
    "high" -> CoreCommonR.string.settings_audio_quality_high
    "medium" -> CoreCommonR.string.settings_audio_quality_medium
    "low" -> CoreCommonR.string.settings_audio_quality_low
    else -> null
}

private fun Context.qualityLabel(value: String, labelRes: Int?): String =
    if (labelRes == null) value else getString(labelRes)

internal data class SettingsQualityPresentation(
    val neteaseLabel: String,
    val youtubeLabel: String,
    val biliLabel: String,
    val mobileNeteaseValue: String,
    val mobileYouTubeValue: String,
    val mobileBiliValue: String,
    val mobileNeteaseLabel: String,
    val mobileYouTubeLabel: String,
    val mobileBiliLabel: String
)

@Composable
internal fun rememberSettingsQualityPresentation(
    context: Context,
    neteaseValue: String,
    youtubeValue: String,
    biliValue: String,
    mobileNeteaseValue: String,
    mobileYouTubeValue: String,
    mobileBiliValue: String
): SettingsQualityPresentation {
    val normalizedNetease = normalizeMobileDataNeteaseAudioQuality(mobileNeteaseValue)
    val normalizedYouTube = normalizeMobileDataYouTubeAudioQuality(mobileYouTubeValue)
    val normalizedBili = normalizeMobileDataBiliAudioQuality(mobileBiliValue)
    return SettingsQualityPresentation(
        neteaseLabel = context.qualityLabel(neteaseValue, neteaseQualityLabelRes(neteaseValue)),
        youtubeLabel = context.qualityLabel(youtubeValue, youtubeQualityLabelRes(youtubeValue)),
        biliLabel = context.qualityLabel(biliValue, biliQualityLabelRes(biliValue)),
        mobileNeteaseValue = normalizedNetease,
        mobileYouTubeValue = normalizedYouTube,
        mobileBiliValue = normalizedBili,
        mobileNeteaseLabel = context.qualityLabel(
            normalizedNetease, neteaseQualityLabelRes(normalizedNetease)
        ),
        mobileYouTubeLabel = context.qualityLabel(
            normalizedYouTube, youtubeQualityLabelRes(normalizedYouTube)
        ),
        mobileBiliLabel = context.qualityLabel(normalizedBili, biliQualityLabelRes(normalizedBili))
    )
}
