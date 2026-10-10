package moe.ouom.neriplayer.data.settings.playback

import moe.ouom.neriplayer.data.model.settings.playback.DEFAULT_MOBILE_DATA_BILI_AUDIO_QUALITY
import moe.ouom.neriplayer.data.model.settings.playback.DEFAULT_MOBILE_DATA_NETEASE_AUDIO_QUALITY
import moe.ouom.neriplayer.data.model.settings.playback.DEFAULT_MOBILE_DATA_YOUTUBE_AUDIO_QUALITY

private val NETEASE_MOBILE_DATA_AUDIO_QUALITIES = setOf(
    "standard",
    "higher",
    "exhigh",
    "lossless",
    "hires",
    "jyeffect",
    "sky",
    "jymaster"
)

private val YOUTUBE_MOBILE_DATA_AUDIO_QUALITIES = setOf(
    "low",
    "medium",
    "high",
    "very_high"
)

private val BILI_MOBILE_DATA_AUDIO_QUALITIES = setOf(
    "low",
    "medium",
    "high",
    "lossless",
    "hires",
    "dolby"
)

fun normalizeMobileDataNeteaseAudioQuality(value: String?): String {
    val normalized = value?.trim()?.lowercase().orEmpty()
    return normalized.takeIf { it in NETEASE_MOBILE_DATA_AUDIO_QUALITIES }
        ?: DEFAULT_MOBILE_DATA_NETEASE_AUDIO_QUALITY
}

fun normalizeMobileDataYouTubeAudioQuality(value: String?): String {
    val normalized = value?.trim()?.lowercase().orEmpty()
    return normalized.takeIf { it in YOUTUBE_MOBILE_DATA_AUDIO_QUALITIES }
        ?: DEFAULT_MOBILE_DATA_YOUTUBE_AUDIO_QUALITY
}

fun normalizeMobileDataBiliAudioQuality(value: String?): String {
    val normalized = value?.trim()?.lowercase().orEmpty()
    return normalized.takeIf { it in BILI_MOBILE_DATA_AUDIO_QUALITIES }
        ?: DEFAULT_MOBILE_DATA_BILI_AUDIO_QUALITY
}

fun resolveLegacyMobileDataQualityPreset(
    value: String?,
    defaultQuality: String
): String {
    return when (value?.trim()?.lowercase()) {
        "low" -> "low"
        "medium" -> "medium"
        "high" -> "high"
        else -> defaultQuality
    }
}

private val LEGACY_MOBILE_DATA_FOLLOWS_DEFAULT_QUALITY = mapOf(
    "off" to true,
    "low" to false,
    "medium" to false,
    "high" to false
)

fun resolveLegacyMobileDataFollowDefaultAudioQuality(value: String?): Boolean? {
    return LEGACY_MOBILE_DATA_FOLLOWS_DEFAULT_QUALITY[value?.trim()?.lowercase()]
}

fun resolveLegacyMobileDataNeteaseAudioQuality(value: String?): String? {
    return when (value?.trim()?.lowercase()) {
        "low" -> DEFAULT_MOBILE_DATA_NETEASE_AUDIO_QUALITY
        "medium" -> "higher"
        "high" -> "exhigh"
        else -> null
    }
}

fun resolveLegacyMobileDataYouTubeAudioQuality(value: String?): String? {
    return when (value?.trim()?.lowercase()) {
        "low" -> DEFAULT_MOBILE_DATA_YOUTUBE_AUDIO_QUALITY
        "medium" -> "medium"
        "high" -> "high"
        else -> null
    }
}

fun resolveLegacyMobileDataBiliAudioQuality(value: String?): String? {
    return when (value?.trim()?.lowercase()) {
        "low" -> DEFAULT_MOBILE_DATA_BILI_AUDIO_QUALITY
        "medium" -> "medium"
        "high" -> "high"
        else -> null
    }
}
