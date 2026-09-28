package moe.ouom.neriplayer.ui.screen.tab.settings.playback

import android.content.Context
import androidx.compose.runtime.Composable
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.settings.playback.normalizeMobileDataBiliAudioQuality
import moe.ouom.neriplayer.data.settings.playback.normalizeMobileDataNeteaseAudioQuality
import moe.ouom.neriplayer.data.settings.playback.normalizeMobileDataYouTubeAudioQuality

internal fun neteaseQualityLabelRes(value: String): Int? = when (value) {
    "standard" -> R.string.settings_audio_quality_standard
    "higher" -> R.string.settings_audio_quality_higher
    "exhigh" -> R.string.settings_audio_quality_exhigh
    "lossless" -> R.string.settings_audio_quality_lossless
    "hires" -> R.string.quality_hires
    "jyeffect" -> R.string.settings_audio_quality_jyeffect
    "sky" -> R.string.settings_audio_quality_sky
    "jymaster" -> R.string.settings_audio_quality_jymaster
    else -> null
}

internal fun youtubeQualityLabelRes(value: String): Int? = when (value) {
    "low" -> R.string.settings_audio_quality_standard
    "medium" -> R.string.settings_audio_quality_medium
    "high" -> R.string.settings_audio_quality_high
    "very_high" -> R.string.quality_very_high
    else -> null
}

internal fun biliQualityLabelRes(value: String): Int? =
    biliPremiumQualityLabelRes(value) ?: biliRegularQualityLabelRes(value)

private fun biliPremiumQualityLabelRes(value: String): Int? = when (value) {
    "dolby" -> R.string.settings_audio_quality_dolby
    "hires" -> R.string.quality_hires
    "lossless" -> R.string.settings_audio_quality_lossless
    else -> null
}

private fun biliRegularQualityLabelRes(value: String): Int? = when (value) {
    "high" -> R.string.settings_audio_quality_high
    "medium" -> R.string.settings_audio_quality_medium
    "low" -> R.string.settings_audio_quality_low
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
