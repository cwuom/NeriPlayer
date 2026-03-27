package moe.ouom.neriplayer.core.player.model

import java.util.Locale

enum class PlaybackAudioSource {
    LOCAL,
    NETEASE,
    BILIBILI,
    YOUTUBE_MUSIC
}

data class PlaybackQualityOption(
    val key: String,
    val label: String
)

data class PlaybackAudioInfo(
    val source: PlaybackAudioSource,
    val qualityKey: String? = null,
    val qualityLabel: String? = null,
    val qualityOptions: List<PlaybackQualityOption> = emptyList(),
    val codecLabel: String? = null,
    val mimeType: String? = null,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val channelCount: Int? = null
) {
    val specLabel: String?
        get() = buildPlaybackSpecLabel(
            sampleRateHz = sampleRateHz,
            bitDepth = bitDepth,
            bitrateKbps = bitrateKbps
        )
}

fun buildPlaybackSpecLabel(
    sampleRateHz: Int?,
    bitDepth: Int?,
    bitrateKbps: Int?
): String? {
    val parts = mutableListOf<String>()
    sampleRateHz?.takeIf { it > 0 }?.let { parts += formatPlaybackSampleRate(it) }
    bitDepth?.takeIf { it > 0 }?.let { parts += "$it bit" }
    bitrateKbps?.takeIf { it > 0 }?.let { parts += "$it kbps" }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(separator = " | ")
}

fun formatPlaybackSampleRate(sampleRateHz: Int): String {
    if (sampleRateHz <= 0) return "0 Hz"
    if (sampleRateHz < 1000) return "$sampleRateHz Hz"
    val sampleRateKhz = sampleRateHz / 1000.0
    return if (sampleRateHz % 1000 == 0) {
        String.format(Locale.US, "%.0f kHz", sampleRateKhz)
    } else {
        String.format(Locale.US, "%.1f kHz", sampleRateKhz)
    }
}

fun deriveCodecLabel(mimeType: String?): String? {
    val normalizedMimeType = mimeType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.US)
        ?.takeIf { it.isNotBlank() }
        ?: return null

    return when (normalizedMimeType) {
        "audio/flac" -> "FLAC"
        "audio/eac3",
        "audio/e-ac-3" -> "E-AC-3"
        "audio/mp4",
        "audio/m4a",
        "audio/aac" -> "AAC"
        "audio/mpeg",
        "audio/mp3" -> "MP3"
        "audio/webm" -> "OPUS"
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl",
        "audio/mpegurl" -> "HLS"
        else -> normalizedMimeType.substringAfter('/', missingDelimiterValue = normalizedMimeType)
            .ifBlank { normalizedMimeType }
            .uppercase(Locale.US)
    }
}

fun estimateBitrateKbps(
    contentLength: Long?,
    durationMs: Long?
): Int? {
    if (contentLength == null || contentLength <= 0L) return null
    if (durationMs == null || durationMs <= 0L) return null
    return ((contentLength * 8L) / durationMs)
        .toInt()
        .takeIf { it > 0 }
}

fun mergeLocalPlaybackAudioInfoWithRemoteQuality(
    localAudioInfo: PlaybackAudioInfo?,
    previousAudioInfo: PlaybackAudioInfo?
): PlaybackAudioInfo? {
    val resolvedLocalAudioInfo = localAudioInfo ?: return null
    if (resolvedLocalAudioInfo.source != PlaybackAudioSource.LOCAL) {
        return resolvedLocalAudioInfo
    }

    val remoteQualityInfo = previousAudioInfo
        ?.takeIf { it.source != PlaybackAudioSource.LOCAL }
        ?.takeIf { !it.qualityLabel.isNullOrBlank() || !it.qualityKey.isNullOrBlank() }
        ?: return resolvedLocalAudioInfo

    // 本地缓存命中时，保留远端原先展示的音质标签，避免 UI 在 metadata 回填后跳变
    return resolvedLocalAudioInfo.copy(
        qualityKey = remoteQualityInfo.qualityKey,
        qualityLabel = remoteQualityInfo.qualityLabel,
        qualityOptions = emptyList()
    )
}

fun inferYouTubeQualityKeyFromBitrate(bitrateKbps: Int?): String {
    val safeBitrate = bitrateKbps ?: return "low"
    return when {
        safeBitrate >= 160 -> "very_high"
        safeBitrate >= 128 -> "high"
        safeBitrate >= 96 -> "medium"
        else -> "low"
    }
}
