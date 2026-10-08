package moe.ouom.neriplayer.core.player.ltw

import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.playback.pauseImpl
import moe.ouom.neriplayer.core.player.url.currentListenTogetherShareableStreamUrls
import moe.ouom.neriplayer.core.player.url.currentPlaybackRequiresListenTogetherAuthoritativeStream
import moe.ouom.neriplayer.core.player.url.hasUsableListenTogetherLocalDirectStream
import moe.ouom.neriplayer.core.player.url.resolveShareableListenTogetherStreamUrls
import moe.ouom.neriplayer.core.player.watchdog.currentPlaybackCandidate
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.ltw.mapping.DefaultListenTogetherSongMapper
import moe.ouom.neriplayer.data.ltw.mapping.ListenTogetherSongMapper
import moe.ouom.neriplayer.data.ltw.playback.ListenTogetherPlaybackHost
import moe.ouom.neriplayer.data.ltw.playback.normalizedDirectStreamUrl
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherStreamResolution
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource

object PlayerListenTogetherSongMapper : ListenTogetherSongMapper by DefaultListenTogetherSongMapper(
    isLocalSong = { LocalSongSupport.isLocalSong(it, null) },
    localAlbumIdentity = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
    resolvedStreamUrls = { PlayerManager.currentListenTogetherShareableStreamUrls() }
)

object PlayerManagerListenTogetherHost : ListenTogetherPlaybackHost {
    override val currentSongFlow get() = PlayerManager.currentSongFlow
    override val currentQueueFlow get() = PlayerManager.currentQueueFlow
    override val isPlayingFlow get() = PlayerManager.isPlayingFlow
    override val playWhenReadyFlow get() = PlayerManager.playWhenReadyFlow
    override val playerPlaybackStateFlow get() = PlayerManager.playerPlaybackStateFlow
    override val playbackPositionFlow get() = PlayerManager.playbackPositionFlow
    override val currentMediaUrlFlow get() = PlayerManager.currentMediaUrlFlow
    override val repeatModeFlow get() = PlayerManager.repeatModeFlow
    override val shuffleModeFlow get() = PlayerManager.shuffleModeFlow
    override val playbackCommandFlow get() = PlayerManager.playbackCommandFlow
    override val shuffleRestorePlaylistReference get() = PlayerManager.shuffleRestorePlaylistReference
    override val listenTogetherSyncPlaybackRate get() = PlayerManager.listenTogetherSyncPlaybackRate
    override fun pauseImpl(forcePersist: Boolean, commandSource: PlaybackCommandSource, allowFadeOut: Boolean, debugReason: String) {
        PlayerManager.pauseImpl(forcePersist = forcePersist, commandSource = commandSource, allowFadeOut = allowFadeOut, debugReason = debugReason)
    }
    override fun play(commandSource: PlaybackCommandSource) { PlayerManager.play(commandSource) }
    override fun seekTo(positionMs: Long, commandSource: PlaybackCommandSource) { PlayerManager.seekTo(positionMs, commandSource) }
    override fun playPlaylist(queue: List<SongItem>, currentIndex: Int, commandSource: PlaybackCommandSource) { PlayerManager.playPlaylist(queue, currentIndex, commandSource = commandSource) }
    override fun applyRemoteQueueUpdate(queue: List<SongItem>, currentIndex: Int) { PlayerManager.applyRemoteQueueUpdate(queue, currentIndex) }
    override fun applyListenTogetherPlaybackMode(repeatMode: Int?, shuffleEnabled: Boolean?) { PlayerManager.applyListenTogetherPlaybackMode(repeatMode, shuffleEnabled) }
    override fun resetListenTogetherSyncPlaybackRate() = PlayerManager.resetListenTogetherSyncPlaybackRate()
    override fun setListenTogetherSyncPlaybackRate(rate: Float) = PlayerManager.setListenTogetherSyncPlaybackRate(rate)
    override fun retryListenTogetherSafetyPauseResume() = PlayerManager.retryListenTogetherSafetyPauseResume()
    override fun completeListenTogetherSafetyPauseResume() = PlayerManager.completeListenTogetherSafetyPauseResume()
    override fun clearListenTogetherSafetyPause() = PlayerManager.clearListenTogetherSafetyPause()
    override fun shouldHoldListenTogetherPlaybackForSafetyPause(causeType: String?) = PlayerManager.shouldHoldListenTogetherPlaybackForSafetyPause(causeType)
    override fun shouldWaitForListenTogetherAuthoritativeStream(song: SongItem) = PlayerManager.shouldWaitForListenTogetherAuthoritativeStream(song)
    override fun isListenTogetherLocalResolutionPendingFor(song: SongItem) = PlayerManager.isListenTogetherLocalResolutionPendingFor(song)
    override fun isListenTogetherAuthoritativeStreamConfirmedUnavailable(song: SongItem) = PlayerManager.isListenTogetherAuthoritativeStreamConfirmedUnavailable(song)
    override fun currentPlaybackRequiresListenTogetherAuthoritativeStream() = PlayerManager.currentPlaybackRequiresListenTogetherAuthoritativeStream()
    override fun currentListenTogetherShareableStreamUrls() = PlayerManager.currentListenTogetherShareableStreamUrls()
    override fun isPendingMediaLoadActive() = PlayerManager.isPendingMediaLoadActive()
    override fun isTransportActive() = PlayerManager.isTransportActive()
    override fun isUsbExclusiveOutputEnabled() = PlayerManager.usbExclusivePlaybackEnabled
    override fun currentSong(): SongItem? = currentSongFlow.value
    override fun stableKey(song: SongItem): String? = with(PlayerListenTogetherSongMapper) { song.toListenTogetherTrackOrNull()?.stableKey }
    override fun playbackPositionMs(): Long = playbackPositionFlow.value
    override fun playbackResolutionPending(): Boolean = isPendingMediaLoadActive()
    override suspend fun resolveShareableStreamUrls(song: SongItem): ListenTogetherStreamResolution {
        val resolution = PlayerManager.resolveShareableListenTogetherStreamUrls(song)
        return ListenTogetherStreamResolution(resolution.streamUrls, resolution.isPreviewOnly)
    }
    override fun hasUsableLocalDirectStream(track: ListenTogetherTrack): Boolean {
        val song = currentSong() ?: return false
        return hasUsableListenTogetherLocalDirectStream(
            currentSongMatchesTarget = with(PlayerListenTogetherSongMapper) { song.sameTrackAs(track.toSongItem()) },
            currentSongHasDirectStream = normalizedDirectStreamUrl(song.streamUrl) != null,
            currentMediaHasDirectStream = normalizedDirectStreamUrl(currentMediaUrlFlow.value) != null,
            currentPlaybackCandidateIsPreview = PlayerManager.currentPlaybackCandidate()?.isPreviewClip ?: false
        )
    }
}
