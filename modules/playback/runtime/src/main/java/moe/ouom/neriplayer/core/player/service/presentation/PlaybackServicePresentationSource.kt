package moe.ouom.neriplayer.core.player.service.presentation

import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.sync.mapping.toSongItem

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import moe.ouom.neriplayer.data.model.settings.lyrics.BluetoothMetadataMode
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.metadata.ExternalBluetoothLyricPayload
import moe.ouom.neriplayer.data.model.playback.SleepTimerState
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.media.displayAlbum
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.core.player.ltw.toSongItem
import moe.ouom.neriplayer.data.ltw.playback.currentTrack
import moe.ouom.neriplayer.data.ltw.playback.expectedPositionMs
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.util.media.buildRemoteSongShareUrl

internal data class PlaybackServicePlaybackSnapshot(
    val song: SongItem?,
    val playerSongPresent: Boolean,
    val playerPositionMs: Long,
    val roomPositionMs: Long,
    val buffering: Boolean,
    val transportActive: Boolean,
    val enginePlaying: Boolean,
    val roomPlaying: Boolean,
    val playbackControlPlaying: Boolean,
    val audioRouteMuted: Boolean,
    val playbackSpeed: Float,
    val queue: List<SongItem> = emptyList(),
    val queueIndex: Int = -1,
)

internal data class PlaybackServiceMetadataInputs(
    val payload: ExternalBluetoothLyricPayload,
    val audioDeviceType: Int?,
    val forceSendLyrics: Boolean,
    val album: String? = null,
)

internal data class PlaybackServiceTimerInputs(
    val state: SleepTimerState,
    val remaining: String,
)

internal interface PlaybackServicePresentationSource {
    fun playback(): PlaybackServicePlaybackSnapshot
    fun metadata(): PlaybackServiceMetadataInputs
    fun bluetoothMetadataModes(): Flow<BluetoothMetadataMode> = emptyFlow()
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
    override fun playback(): PlaybackServicePlaybackSnapshot {
        val queue = PlayerManager.currentQueueSnapshot()
        return PlaybackServicePlaybackSnapshot(
            song = playbackSurfaceSong(),
            playerSongPresent = PlayerManager.currentSongFlow.value != null,
            playerPositionMs = PlayerManager.playbackPositionFlow.value,
            roomPositionMs = roomPositionMs(),
            buffering = PlayerManager.isTransportBuffering(),
            transportActive = PlayerManager.isTransportActive(),
            enginePlaying = PlayerManager.isPlayingFlow.value,
            roomPlaying = roomPlaying(),
            playbackControlPlaying = PlayerManager.playbackControlPlayingFlow.value,
            audioRouteMuted = PlayerManager.audioRouteMuteSuppressedFlow.value,
            playbackSpeed = PlayerManager.playbackSoundStateFlow.value.speed,
            queue = queue.playlist,
            queueIndex = queue.currentIndex,
        )
    }

    override fun bluetoothMetadataModes(): Flow<BluetoothMetadataMode> =
        PlayerDependencies.repositories.settingsRepo.bluetoothMetadataModeFlow

    override fun metadata(): PlaybackServiceMetadataInputs = PlaybackServiceMetadataInputs(
        payload = PlayerManager.externalBluetoothLyricPayloadFlow.value,
        audioDeviceType = PlayerManager.currentAudioDeviceFlow.value?.type,
        forceSendLyrics = PlayerManager.dynamicIslandLyricsEnabled,
        album = metadataAlbum(),
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
        PlayerDependencies.repositories.settingsRepo.setFloatingLyricsEnabled(enabled)
    }

    private fun favoritePlaylistSongs(): List<SongItem> {
        val playlist = FavoritesPlaylist.firstOrNull(PlayerManager.playlistsFlow.value, context)
            ?: return emptyList()
        return playlist.songs
    }

    private fun playbackSurfaceSong(): SongItem? = PlayerManager.currentSongFlow.value ?: roomSong()

    private fun metadataAlbum(): String? = playbackSurfaceSong()?.displayAlbum(context)

    private fun roomSong(): SongItem? = roomTrack()?.toSongItem()

    private fun roomTrack() = currentRoom()?.currentTrack()

    private fun roomPlaying(): Boolean = roomPlaybackState() == "playing"

    private fun roomPlaybackState(): String? = currentRoom()?.let { it.playback.state }

    private fun roomPositionMs(): Long = currentRoom()?.let(::resolveListenTogetherMediaSessionPosition) ?: 0L

    private fun currentRoom(): ListenTogetherRoomState? =
        PlayerDependencies.listenTogether.roomState.value
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
