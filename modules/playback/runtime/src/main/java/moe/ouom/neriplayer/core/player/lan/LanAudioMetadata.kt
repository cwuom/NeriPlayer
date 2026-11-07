package moe.ouom.neriplayer.core.player.lan

import java.io.File
import moe.ouom.neriplayer.core.player.url.listenTogetherQualityKeyFromStreamUrl
import moe.ouom.neriplayer.core.player.url.buildListenTogetherFallbackAudioInfo
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioInfo
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import moe.ouom.neriplayer.data.model.playback.deriveCodecLabel

internal data class LanAudioFile(
    val file: File?, val format: LanAudioFormat,
    val source: moe.ouom.neriplayer.lanltw.media.LanReadableMedia = moe.ouom.neriplayer.lanltw.media.LanReadableMedia.file(checkNotNull(file)),
    val originalUri: android.net.Uri = android.net.Uri.fromFile(checkNotNull(file))
)
internal data class LanAudioFormat(
    val mime: String?, val bitrateKbps: Int?, val sampleRateHz: Int?,
    val channels: Int?, val bitDepth: Int?
)

/** Source is authoritative room metadata; quality comes from the selected host candidate. */
internal fun lanAudioSource(channelId: String): PlaybackAudioSource = when (channelId) {
    "local" -> PlaybackAudioSource.LOCAL
    "netease" -> PlaybackAudioSource.NETEASE
    "bilibili" -> PlaybackAudioSource.BILIBILI
    "youtubeMusic" -> PlaybackAudioSource.YOUTUBE_MUSIC
    else -> error("SOURCE")
}

internal fun lanAudioMetadata(
    channelId: String, selectedUrl: String, format: LanAudioFormat, localized: (Int) -> String
): PlaybackAudioInfo {
    val source = lanAudioSource(channelId)
    val quality = listenTogetherQualityKeyFromStreamUrl(selectedUrl, source)
    val base = quality?.let { buildListenTogetherFallbackAudioInfo(source, it, localized) }
        ?: PlaybackAudioInfo(source)
    return base.copy(qualityOptions = emptyList(),
        codecLabel = deriveCodecLabel(format.mime), mimeType = format.mime,
        bitrateKbps = format.bitrateKbps, sampleRateHz = format.sampleRateHz,
        channelCount = format.channels, bitDepth = format.bitDepth)
}
