package moe.ouom.neriplayer.listentogether.mapping

import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherChannels
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherTrack
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem

fun SongItem.toListenTogetherTrackOrNull(includeLocal: Boolean = false): ListenTogetherTrack? {
    val channel = resolvedChannelId() ?: return null
    if (channel == ListenTogetherChannels.LOCAL && !includeLocal) {
        return null
    }

    val audio = resolvedAudioId() ?: return null
    val subAudio = resolvedSubAudioId()
    val playlistContext = resolvedPlaylistContextId()
    return ListenTogetherTrack(
        stableKey = buildStableTrackKey(channel, audio, subAudio, playlistContext),
        channelId = channel,
        audioId = audio,
        subAudioId = subAudio,
        playlistContextId = playlistContext,
        mediaUri = mediaUri,
        streamUrl = streamUrl,
        name = customName ?: name,
        artist = customArtist ?: artist,
        album = album,
        durationMs = durationMs,
        coverUrl = customCoverUrl ?: coverUrl
    )
}
