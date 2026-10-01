package moe.ouom.neriplayer.core.player.policy.audio

import moe.ouom.neriplayer.data.model.playback.PlaybackAudioInfo
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource

fun mergeLocalPlaybackAudioInfoWithRemoteQuality(
    localAudioInfo: PlaybackAudioInfo?,
    previousAudioInfo: PlaybackAudioInfo?
): PlaybackAudioInfo? {
    val resolvedLocalAudioInfo = localAudioInfo ?: return null
    if (resolvedLocalAudioInfo.source != PlaybackAudioSource.LOCAL) {
        return resolvedLocalAudioInfo
    }

    val remoteQualityInfo = previousAudioInfo ?: return resolvedLocalAudioInfo
    if (!hasRemoteQualityLabel(remoteQualityInfo)) return resolvedLocalAudioInfo

    // 本地缓存命中时，保留远端原先展示的音质标签，避免 UI 在 metadata 回填后跳变
    return resolvedLocalAudioInfo.copy(
        qualityKey = remoteQualityInfo.qualityKey,
        qualityLabel = remoteQualityInfo.qualityLabel,
        qualityOptions = emptyList()
    )
}

private fun hasRemoteQualityLabel(audioInfo: PlaybackAudioInfo): Boolean {
    if (audioInfo.source == PlaybackAudioSource.LOCAL) return false
    return !audioInfo.qualityLabel.isNullOrBlank() || !audioInfo.qualityKey.isNullOrBlank()
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
