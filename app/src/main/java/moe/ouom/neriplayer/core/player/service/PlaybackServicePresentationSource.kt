package moe.ouom.neriplayer.core.player.service

import android.content.Context
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.metadata.ExternalBluetoothLyricPayload
import moe.ouom.neriplayer.core.player.timer.SleepTimerState
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.listentogether.mapping.toSongItem
import moe.ouom.neriplayer.listentogether.playback.currentTrack
import moe.ouom.neriplayer.listentogether.playback.expectedPositionMs
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomState
import moe.ouom.neriplayer.util.media.buildRemoteSongShareUrl

internal data class PlaybackServicePlaybackSnapshot(
    val song: SongItem?,
    val playerSongPresent: Boolean,
    val playerPositionMs: Long,
    val roomPositionMs: Long,
    val buffering: Boolean,
    val transportActive: Boolean,
    val roomPlaying: Boolean,
    val playbackControlPlaying: Boolean,
    val audioRouteMuted: Boolean,
    val playbackSpeed: Float,
)

internal data class PlaybackServiceMetadataInputs(
    val payload: ExternalBluetoothLyricPayload,
    val audioDeviceType: Int?,
    val forceSendLyrics: Boolean,
)

internal data class PlaybackServiceTimerInputs(
    val state: SleepTimerState,
    val remaining: String,
)

internal interface PlaybackServicePresentationSource {
    fun playback(): PlaybackServicePlaybackSnapshot
    fun metadata(): PlaybackServiceMetadataInputs
    fun timer(): PlaybackServiceTimerInputs
    fun favoriteSongKeys(): Set<String>
    fun localPlaylistsReady(): Boolean
    fun isLocalSong(song: SongItem): Boolean
    fun shareUrl(song: SongItem): String?
    suspend fun setFloatingLyricsEnabled(enabled: Boolean)
}

internal class AndroidPlaybackServicePresentationSource(
    private val context: Context,
) : PlaybackServicePresentationSource {
    override fun playback(): PlaybackServicePlaybackSnapshot = PlaybackServicePlaybackSnapshot(
        song = playbackSurfaceSong(),
        playerSongPresent = PlayerManager.currentSongFlow.value != null,
        playerPositionMs = PlayerManager.playbackPositionFlow.value,
        roomPositionMs = roomPositionMs(),
        buffering = PlayerManager.isTransportBuffering(),
        transportActive = PlayerManager.isTransportActive(),
        roomPlaying = roomPlaying(),
        playbackControlPlaying = PlayerManager.playbackControlPlayingFlow.value,
        audioRouteMuted = PlayerManager.audioRouteMuteSuppressedFlow.value,
        playbackSpeed = PlayerManager.playbackSoundStateFlow.value.speed,
    )

    override fun metadata(): PlaybackServiceMetadataInputs = PlaybackServiceMetadataInputs(
        payload = PlayerManager.externalBluetoothLyricPayloadFlow.value,
        audioDeviceType = PlayerManager.currentAudioDeviceFlow.value?.type,
        forceSendLyrics = PlayerManager.dynamicIslandLyricsEnabled,
    )

    override fun timer(): PlaybackServiceTimerInputs = PlaybackServiceTimerInputs(
        state = PlayerManager.sleepTimerManager.timerState.value,
        remaining = PlayerManager.sleepTimerManager.formatRemainingTimeForNotification(),
    )

    override fun favoriteSongKeys(): Set<String> {
        if (!PlayerManager.localPlaylistsReady) return emptySet()
        return favoritePlaylistSongs().mapTo(mutableSetOf()) { it.stableKey() }
    }

    override fun localPlaylistsReady(): Boolean = PlayerManager.localPlaylistsReady

    override fun isLocalSong(song: SongItem): Boolean = LocalSongSupport.isLocalSong(song, context)

    override fun shareUrl(song: SongItem): String? =
        buildRemoteSongShareUrl(song, PlayerManager.currentPlaylist)

    override suspend fun setFloatingLyricsEnabled(enabled: Boolean) {
        AppContainer.settingsRepo.setFloatingLyricsEnabled(enabled)
    }

    private fun favoritePlaylistSongs(): List<SongItem> {
        val playlist = FavoritesPlaylist.firstOrNull(PlayerManager.playlistsFlow.value, context)
            ?: return emptyList()
        return playlist.songs
    }

    private fun playbackSurfaceSong(): SongItem? = PlayerManager.currentSongFlow.value ?: roomSong()

    private fun roomSong(): SongItem? = roomTrack()?.toSongItem()

    private fun roomTrack() = currentRoom()?.currentTrack()

    private fun roomPlaying(): Boolean = roomPlaybackState() == "playing"

    private fun roomPlaybackState(): String? = currentRoom()?.let { it.playback.state }

    private fun roomPositionMs(): Long = currentRoom()?.let(::resolveListenTogetherMediaSessionPosition) ?: 0L

    private fun currentRoom(): ListenTogetherRoomState? =
        AppContainer.listenTogetherSessionManager.roomState.value
}

internal fun resolveListenTogetherMediaSessionPosition(
    roomState: ListenTogetherRoomState,
    nowMs: Long = System.currentTimeMillis(),
): Long {
    val activeTrack = roomState.currentTrack()
    return roomState.playback.expectedPositionMs(
        nowMs = nowMs,
        durationMs = activeTrack?.durationMs ?: 0L,
    )
}
