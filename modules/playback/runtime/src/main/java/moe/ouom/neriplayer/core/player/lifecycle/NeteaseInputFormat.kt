@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.lifecycle

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.CodecSpecificDataUtil
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.analytics.AnalyticsListener
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.dlna.protocol.FlacHeaderInspector
import moe.ouom.neriplayer.core.player.dlna.session.DlnaCastManager
import moe.ouom.neriplayer.core.player.url.stripListenTogetherStreamQualityMetadata
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioInfo
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource

internal fun PlayerManager.updateNeteaseInputFormat(event: AnalyticsListener.EventTime, format: Format) {
    val info = _currentPlaybackAudioInfo.value ?: return
    if (info.source != PlaybackAudioSource.NETEASE || DlnaCastManager.isCastingActive()) return
    if (loadedMediaRequestToken != playbackRequestToken) return

    // 切歌或切音质时，旧音轨的回调可能在新播放地址生效后才到达。
    val period = event.mediaPeriodId ?: return
    val timeline = player.currentTimeline
    if (period != event.currentMediaPeriodId || timeline.isEmpty) return
    if (timeline.getUidOfPeriod(player.currentPeriodIndex) != period.periodUid) return
    val url = _currentMediaUrl.value ?: return
    val playingUri = player.currentMediaItem?.localConfiguration?.uri ?: return
    if (playingUri.toString() != stripListenTogetherStreamQualityMetadata(url)) return

    _currentPlaybackAudioInfo.compareAndSet(info, info.withNeteaseInputFormat(format))
}

internal fun PlaybackAudioInfo.withNeteaseInputFormat(format: Format): PlaybackAudioInfo {
    val mime = format.sampleMimeType
    val config = format.initializationData.firstOrNull()
    val flac = if (mime == MimeTypes.AUDIO_FLAC && config != null) {
        FlacHeaderInspector.inspectBytes(config).takeIf { it.format == FlacHeaderInspector.ContainerFormat.FLAC }
    } else null
    val alac = if (mime == MimeTypes.AUDIO_ALAC && config?.size == 24) {
        CodecSpecificDataUtil.parseAlacAudioSpecificConfig(config)
    } else null
    val pcmDepth = when (mime) {
        MimeTypes.AUDIO_RAW, MimeTypes.AUDIO_FLAC, MimeTypes.AUDIO_ALAC ->
            if (Util.isEncodingLinearPcm(format.pcmEncoding)) Util.getByteDepth(format.pcmEncoding) * 8 else null
        else -> null
    }
    val alacDepth = when (val depth = alac?.get(2)) {
        16, 20, 24, 32 -> depth
        else -> null
    }
    return copy(
        sampleRateHz = format.sampleRate.takeIf { it > 0 } ?: flac?.sampleRateHz ?: alac?.get(0)?.takeIf { it > 0 },
        bitDepth = flac?.bitDepth?.takeIf { it in 4..32 } ?: alacDepth ?: pcmDepth
    )
}
