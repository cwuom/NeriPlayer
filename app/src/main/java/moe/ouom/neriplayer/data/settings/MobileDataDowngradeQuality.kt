package moe.ouom.neriplayer.data.settings

const val DEFAULT_MOBILE_DATA_NETEASE_AUDIO_QUALITY = "standard"
const val DEFAULT_MOBILE_DATA_YOUTUBE_AUDIO_QUALITY = "low"
const val DEFAULT_MOBILE_DATA_BILI_AUDIO_QUALITY = "low"
const val DEFAULT_MOBILE_DATA_KUGOU_AUDIO_QUALITY = "128"

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

private val KUGOU_MOBILE_DATA_AUDIO_QUALITIES = setOf(
    "128",
    "320",
    "flac",
    "high",
    "viper_atmos",
    "viper_clear",
    "viper_tape",
    "super"
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

fun normalizeMobileDataKugouAudioQuality(value: String?): String {
    val normalized = value?.trim()?.lowercase().orEmpty()
    return normalized.takeIf { it in KUGOU_MOBILE_DATA_AUDIO_QUALITIES }
        ?: DEFAULT_MOBILE_DATA_KUGOU_AUDIO_QUALITY
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

fun resolveLegacyMobileDataFollowDefaultAudioQuality(value: String?): Boolean? {
    return when (value?.trim()?.lowercase()) {
        "off" -> true
        "low",
        "medium",
        "high" -> false
        else -> null
    }
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


fun resolveLegacyMobileDataKugouAudioQuality(value: String?): String? {
    return when (value?.trim()?.lowercase()) {
        "low" -> DEFAULT_MOBILE_DATA_KUGOU_AUDIO_QUALITY
        "medium" -> "320"
        "high" -> "flac"
        else -> null
    }
}
