package moe.ouom.neriplayer.core.player.url

import android.content.Context
import android.net.Uri
import moe.ouom.neriplayer.core.player.model.PlaybackAudioInfo
import moe.ouom.neriplayer.core.player.model.PlaybackAudioSource
import moe.ouom.neriplayer.core.player.model.deriveCodecLabel
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.data.local.media.localMediaUri

internal fun buildLocalPlaybackAudioInfo(song: SongItem, context: Context): PlaybackAudioInfo? {
    val localUri = song.localMediaUri() ?: return null
    return buildLocalPlaybackAudioInfo(localUri, context)
}

internal fun buildLocalPlaybackAudioInfo(localUri: Uri, context: Context): PlaybackAudioInfo? {
    return runCatching {
        LocalMediaSupport.inspectQuick(
            context = context,
            uri = localUri,
            includeAudioTrackInfo = true
        )
    }.getOrNull()?.let { details ->
        PlaybackAudioInfo(
            source = PlaybackAudioSource.LOCAL,
            codecLabel = deriveCodecLabel(details.audioMimeType ?: details.mimeType),
            mimeType = details.audioMimeType ?: details.mimeType,
            bitrateKbps = details.bitrateKbps,
            sampleRateHz = details.sampleRateHz,
            bitDepth = details.bitsPerSample,
            channelCount = details.channelCount
        )
    }
}
