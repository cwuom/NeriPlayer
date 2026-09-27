package moe.ouom.neriplayer.listentogether.session

import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.url.ShareableListenTogetherStreamResolution
import moe.ouom.neriplayer.core.player.url.hasUsableListenTogetherLocalDirectStream
import moe.ouom.neriplayer.core.player.url.resolveShareableListenTogetherStreamUrls
import moe.ouom.neriplayer.core.player.watchdog.currentPlaybackCandidate
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.listentogether.playback.normalizedDirectStreamUrl
import moe.ouom.neriplayer.listentogether.playback.sameTrackAs
import moe.ouom.neriplayer.listentogether.mapping.toSongItem
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherTrack

internal object PlayerManagerListenTogetherLinkPlaybackPort : ListenTogetherLinkPlaybackPort {
    override fun currentSong(): SongItem? = PlayerManager.currentSongFlow.value

    override fun playbackPositionMs(): Long = PlayerManager.playbackPositionFlow.value

    override fun playbackResolutionPending(): Boolean = PlayerManager.isPendingMediaLoadActive()

    override suspend fun resolveShareableStreamUrls(
        song: SongItem
    ): ShareableListenTogetherStreamResolution =
        PlayerManager.resolveShareableListenTogetherStreamUrls(song)

    override fun hasUsableLocalDirectStream(track: ListenTogetherTrack): Boolean {
        val song = currentSong() ?: return false
        return hasUsableListenTogetherLocalDirectStream(
            currentSongMatchesTarget = song.sameTrackAs(track.toSongItem()),
            currentSongHasDirectStream = hasDirectStream(song.streamUrl),
            currentMediaHasDirectStream = hasDirectStream(PlayerManager.currentMediaUrlFlow.value),
            currentPlaybackCandidateIsPreview = currentPlaybackIsPreview()
        )
    }

    private fun hasDirectStream(url: String?): Boolean = normalizedDirectStreamUrl(url) != null

    private fun currentPlaybackIsPreview(): Boolean {
        val candidate = PlayerManager.currentPlaybackCandidate() ?: return false
        return candidate.isPreviewClip
    }
}
