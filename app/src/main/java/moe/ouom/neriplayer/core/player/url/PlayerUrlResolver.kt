package moe.ouom.neriplayer.core.player.url

import android.net.Uri
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.player.model.PlaybackAudioInfo
import moe.ouom.neriplayer.core.player.model.PlaybackAudioSource
import moe.ouom.neriplayer.core.player.model.PlaybackQualityOption
import moe.ouom.neriplayer.core.player.model.SongUrlResult
import moe.ouom.neriplayer.core.player.model.deriveCodecLabel
import moe.ouom.neriplayer.core.player.model.estimateBitrateKbps
import moe.ouom.neriplayer.core.player.model.inferYouTubeQualityKeyFromBitrate
import moe.ouom.neriplayer.data.platform.bili.BiliAudioStreamInfo
import moe.ouom.neriplayer.core.player.NeteasePlaybackResponseParser

internal val NETEASE_QUALITY_FALLBACK_ORDER = listOf(
    "jymaster",
    "sky",
    "jyeffect",
    "hires",
    "lossless",
    "exhigh",
    "standard"
)

internal fun qualityLabelForNetease(key: String, getLocalizedString: (Int) -> String): String = when (key) {
    "standard" -> getLocalizedString(R.string.quality_standard)
    "higher" -> getLocalizedString(R.string.settings_audio_quality_higher)
    "exhigh" -> getLocalizedString(R.string.quality_very_high)
    "lossless" -> getLocalizedString(R.string.quality_lossless)
    "hires" -> getLocalizedString(R.string.quality_hires)
    "jyeffect" -> getLocalizedString(R.string.quality_hd_surround)
    "sky" -> getLocalizedString(R.string.quality_surround)
    "jymaster" -> getLocalizedString(R.string.settings_audio_quality_jymaster)
    else -> key
}

internal fun qualityLabelForBili(key: String, getLocalizedString: (Int) -> String): String = when (key) {
    "dolby" -> getLocalizedString(R.string.quality_dolby)
    "hires" -> getLocalizedString(R.string.quality_hires)
    "lossless" -> getLocalizedString(R.string.quality_lossless)
    "high" -> getLocalizedString(R.string.settings_audio_quality_high)
    "medium" -> getLocalizedString(R.string.settings_audio_quality_medium)
    "low" -> getLocalizedString(R.string.settings_audio_quality_low)
    else -> key
}

internal fun qualityLabelForYouTube(key: String, getLocalizedString: (Int) -> String): String = when (key) {
    "low" -> getLocalizedString(R.string.settings_audio_quality_low)
    "medium" -> getLocalizedString(R.string.settings_audio_quality_medium)
    "high" -> getLocalizedString(R.string.settings_audio_quality_high)
    "very_high" -> getLocalizedString(R.string.quality_very_high)
    else -> key
}

internal fun buildNeteaseQualityOptions(getLocalizedString: (Int) -> String): List<PlaybackQualityOption> = listOf(
    PlaybackQualityOption("standard", qualityLabelForNetease("standard", getLocalizedString)),
    PlaybackQualityOption("higher", qualityLabelForNetease("higher", getLocalizedString)),
    PlaybackQualityOption("exhigh", qualityLabelForNetease("exhigh", getLocalizedString)),
    PlaybackQualityOption("lossless", qualityLabelForNetease("lossless", getLocalizedString)),
    PlaybackQualityOption("hires", qualityLabelForNetease("hires", getLocalizedString)),
    PlaybackQualityOption("jyeffect", qualityLabelForNetease("jyeffect", getLocalizedString)),
    PlaybackQualityOption("sky", qualityLabelForNetease("sky", getLocalizedString)),
    PlaybackQualityOption("jymaster", qualityLabelForNetease("jymaster", getLocalizedString))
)

internal fun buildYouTubeQualityOptions(getLocalizedString: (Int) -> String): List<PlaybackQualityOption> = listOf(
    PlaybackQualityOption("low", qualityLabelForYouTube("low", getLocalizedString)),
    PlaybackQualityOption("medium", qualityLabelForYouTube("medium", getLocalizedString)),
    PlaybackQualityOption("high", qualityLabelForYouTube("high", getLocalizedString)),
    PlaybackQualityOption("very_high", qualityLabelForYouTube("very_high", getLocalizedString))
)

internal fun inferBiliQualityKey(biliAudioStream: BiliAudioStreamInfo): String {
    return when {
        biliAudioStream.qualityTag == "dolby" -> "dolby"
        biliAudioStream.qualityTag == "hires" -> "hires"
        biliAudioStream.bitrateKbps >= 180 -> "high"
        biliAudioStream.bitrateKbps >= 120 -> "medium"
        else -> "low"
    }
}

internal fun buildBiliQualityOptions(
    availableStreams: List<BiliAudioStreamInfo>,
    getLocalizedString: (Int) -> String
): List<PlaybackQualityOption> {
    val availableKeys = availableStreams
        .map(::inferBiliQualityKey)
        .distinct()
    val orderedKeys = listOf("dolby", "hires", "lossless", "high", "medium", "low")
    return orderedKeys
        .filter { it in availableKeys }
        .map { PlaybackQualityOption(it, qualityLabelForBili(it, getLocalizedString)) }
}

internal fun normalizeNeteaseMimeType(type: String?): String? {
    val normalizedType = type
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.isNotBlank() }
        ?: return null
    return when (normalizedType) {
        "flac" -> "audio/flac"
        "mp3" -> "audio/mpeg"
        "aac" -> "audio/aac"
        "m4a", "mp4" -> "audio/mp4"
        else -> if (normalizedType.contains('/')) normalizedType else "audio/$normalizedType"
    }
}

internal fun buildNeteasePlaybackAudioInfo(
    parsed: NeteasePlaybackResponseParser.PlaybackResult.Success,
    resolvedQualityKey: String,
    fallbackDurationMs: Long,
    getLocalizedString: (Int) -> String
): PlaybackAudioInfo {
    val mimeType = normalizeNeteaseMimeType(parsed.type)
    return PlaybackAudioInfo(
        source = PlaybackAudioSource.NETEASE,
        qualityKey = resolvedQualityKey,
        qualityLabel = qualityLabelForNetease(resolvedQualityKey, getLocalizedString),
        qualityOptions = buildNeteaseQualityOptions(getLocalizedString),
        codecLabel = deriveCodecLabel(mimeType) ?: parsed.type?.uppercase(),
        mimeType = mimeType,
        bitrateKbps = if (parsed.notice == NeteasePlaybackResponseParser.Notice.PREVIEW_CLIP) {
            null
        } else {
            estimateBitrateKbps(parsed.contentLength, fallbackDurationMs)
        }
    )
}

internal fun buildBiliPlaybackAudioInfo(
    selectedStream: BiliAudioStreamInfo,
    availableStreams: List<BiliAudioStreamInfo>,
    getLocalizedString: (Int) -> String
): PlaybackAudioInfo {
    val qualityKey = inferBiliQualityKey(selectedStream)
    return PlaybackAudioInfo(
        source = PlaybackAudioSource.BILIBILI,
        qualityKey = qualityKey,
        qualityLabel = qualityLabelForBili(qualityKey, getLocalizedString),
        qualityOptions = buildBiliQualityOptions(availableStreams, getLocalizedString),
        codecLabel = deriveCodecLabel(selectedStream.mimeType),
        mimeType = selectedStream.mimeType,
        bitrateKbps = selectedStream.bitrateKbps
    )
}

internal fun buildYouTubePlaybackAudioInfo(
    playableAudio: moe.ouom.neriplayer.core.api.youtube.YouTubePlayableAudio,
    getLocalizedString: (Int) -> String
): PlaybackAudioInfo {
    val qualityKey = inferYouTubeQualityKeyFromBitrate(playableAudio.bitrateKbps)
    return PlaybackAudioInfo(
        source = PlaybackAudioSource.YOUTUBE_MUSIC,
        qualityKey = qualityKey,
        qualityLabel = qualityLabelForYouTube(qualityKey, getLocalizedString),
        qualityOptions = buildYouTubeQualityOptions(getLocalizedString),
        codecLabel = deriveCodecLabel(playableAudio.mimeType),
        mimeType = playableAudio.mimeType,
        bitrateKbps = playableAudio.bitrateKbps,
        sampleRateHz = playableAudio.sampleRateHz
    )
}

internal fun buildYouTubeOfflineCacheAudioInfo(
    preferredQualityKey: String,
    getLocalizedString: (Int) -> String
): PlaybackAudioInfo {
    val qualityKey = preferredQualityKey
        .trim()
        .lowercase()
        .ifBlank { "very_high" }
    return PlaybackAudioInfo(
        source = PlaybackAudioSource.YOUTUBE_MUSIC,
        qualityKey = qualityKey,
        qualityLabel = qualityLabelForYouTube(qualityKey, getLocalizedString),
        qualityOptions = buildYouTubeQualityOptions(getLocalizedString)
    )
}

internal fun buildNeteaseQualityCandidates(preferredQuality: String): List<String> {
    val normalizedQuality = preferredQuality.trim().lowercase().ifBlank { "exhigh" }
    val preferredIndex = NETEASE_QUALITY_FALLBACK_ORDER.indexOf(normalizedQuality)
    return if (preferredIndex >= 0) {
        NETEASE_QUALITY_FALLBACK_ORDER.drop(preferredIndex)
    } else {
        listOf(normalizedQuality, "exhigh", "standard").distinct()
    }
}

internal fun shouldRetryNeteaseWithLowerQuality(
    reason: NeteasePlaybackResponseParser.FailureReason
): Boolean {
    return reason == NeteasePlaybackResponseParser.FailureReason.NO_PERMISSION ||
        reason == NeteasePlaybackResponseParser.FailureReason.NO_PLAY_URL
}

internal fun buildNeteaseSuccessResult(
    parsed: NeteasePlaybackResponseParser.PlaybackResult.Success,
    resolvedQualityKey: String,
    fallbackDurationMs: Long,
    getLocalizedString: (Int) -> String
): SongUrlResult.Success {
    val finalUrl = if (parsed.url.startsWith("http://")) {
        parsed.url.replaceFirst("http://", "https://")
    } else {
        parsed.url
    }
    val noticeMessage = when (parsed.notice) {
        NeteasePlaybackResponseParser.Notice.PREVIEW_CLIP ->
            getLocalizedString(R.string.player_netease_preview_only)
        null -> null
    }
    return SongUrlResult.Success(
        url = finalUrl,
        noticeMessage = noticeMessage,
        expectedContentLength = parsed.contentLength,
        audioInfo = buildNeteasePlaybackAudioInfo(
            parsed = parsed,
            resolvedQualityKey = resolvedQualityKey,
            fallbackDurationMs = fallbackDurationMs,
            getLocalizedString = getLocalizedString
        )
    )
}

internal fun shouldReplaceCachedPreviewResource(
    cachedContentLength: Long,
    expectedContentLength: Long
): Boolean {
    val contentLengthGap = expectedContentLength - cachedContentLength
    return cachedContentLength > 0L &&
        expectedContentLength > 0L &&
        contentLengthGap >= 512L * 1024L &&
        cachedContentLength * 100L < expectedContentLength * 85L
}
