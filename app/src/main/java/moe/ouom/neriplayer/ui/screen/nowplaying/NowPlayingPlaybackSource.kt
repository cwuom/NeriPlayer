package moe.ouom.neriplayer.ui.screen.nowplaying

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.StateFlow
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.heartbeat.cycleHeartbeatMode
import moe.ouom.neriplayer.core.player.heartbeat.heartbeatModeFlow
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioInfo
import moe.ouom.neriplayer.data.model.playback.PlaybackSoundState
import moe.ouom.neriplayer.data.model.playback.PreferredQualityKeys
import moe.ouom.neriplayer.data.model.playback.SleepTimerState
import moe.ouom.neriplayer.data.model.playback.queue.PlayerQueueDisplayState
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricSourcePreference
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.DefaultNowPlayingLyricsStages
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingLyricsStages

/**
 * 播放页和歌词页读写播放状态的入口
 * 正常使用时直接转发 PlayerManager, 首次引导的实机预览换成示例歌曲, 不碰真实播放和队列
 */
@Stable
internal interface NowPlayingPlaybackSource {
    val currentSongFlow: StateFlow<SongItem?>
    val isPlayingFlow: StateFlow<Boolean>
    val playbackControlPlayingFlow: StateFlow<Boolean>
    val audioRouteMuteSuppressedFlow: StateFlow<Boolean>
    val usbExclusivePlaybackPreparingFlow: StateFlow<Boolean>
    val shuffleModeFlow: StateFlow<Boolean>
    val repeatModeFlow: StateFlow<Int>
    val heartbeatModeFlow: StateFlow<Int>
    val playbackDurationFlow: StateFlow<Long>
    val playbackPositionFlow: StateFlow<Long>
    val currentPlaybackAudioInfoFlow: StateFlow<PlaybackAudioInfo?>
    val preferredQualityKeys: StateFlow<PreferredQualityKeys>
    val playbackSoundStateFlow: StateFlow<PlaybackSoundState>
    val lyricsPreferenceRevisionFlow: StateFlow<Long>
    val currentQueueFlow: StateFlow<List<SongItem>>
    val currentQueueDisplayRevisionFlow: StateFlow<Long>
    val currentMediaUrlFlow: StateFlow<String?>
    val playlistsFlow: StateFlow<List<LocalPlaylist>>
    val localPlaylistsReadyFlow: StateFlow<Boolean>
    val sleepTimerStateFlow: StateFlow<SleepTimerState>
    val preferWordTimedLyrics: Boolean
    val defaultLyricSource: LyricSourcePreference
    val lyricsStages: NowPlayingLyricsStages

    /** 队列, 定时, 音量, 加歌单, 评论和更多操作都会改动真实数据, 预览里整体关闭 */
    val secondaryActionsEnabled: Boolean

    fun currentQueueDisplaySnapshot(): PlayerQueueDisplayState
    fun isBiliCcSubtitleLyricsActive(song: SongItem?): Boolean
    fun togglePlayPause()
    fun next()
    fun previous()
    fun setShuffle(enabled: Boolean)
    fun cycleRepeatMode()
    fun cycleHeartbeatMode()
    fun seekTo(positionMs: Long)
    fun toggleCurrentFavorite()
    fun addCurrentToPlaylist(playlistId: Long)
    fun changeCurrentPlaybackQuality(optionKey: String)
}

internal object PlayerManagerNowPlayingPlaybackSource : NowPlayingPlaybackSource {
    override val currentSongFlow get() = PlayerManager.currentSongFlow
    override val isPlayingFlow get() = PlayerManager.isPlayingFlow
    override val playbackControlPlayingFlow get() = PlayerManager.playbackControlPlayingFlow
    override val audioRouteMuteSuppressedFlow get() = PlayerManager.audioRouteMuteSuppressedFlow
    override val usbExclusivePlaybackPreparingFlow get() = PlayerManager.usbExclusivePlaybackPreparingFlow
    override val shuffleModeFlow get() = PlayerManager.shuffleModeFlow
    override val repeatModeFlow get() = PlayerManager.repeatModeFlow
    override val heartbeatModeFlow get() = PlayerManager.heartbeatModeFlow
    override val playbackDurationFlow get() = PlayerManager.playbackDurationFlow
    override val playbackPositionFlow get() = PlayerManager.playbackPositionFlow
    override val currentPlaybackAudioInfoFlow get() = PlayerManager.currentPlaybackAudioInfoFlow
    override val preferredQualityKeys get() = PlayerManager.preferredQualityKeys
    override val playbackSoundStateFlow get() = PlayerManager.playbackSoundStateFlow
    override val lyricsPreferenceRevisionFlow get() = PlayerManager.lyricsPreferenceRevisionFlow
    override val currentQueueFlow get() = PlayerManager.currentQueueFlow
    override val currentQueueDisplayRevisionFlow get() = PlayerManager.currentQueueDisplayRevisionFlow
    override val currentMediaUrlFlow get() = PlayerManager.currentMediaUrlFlow
    override val playlistsFlow get() = PlayerManager.playlistsFlow
    override val localPlaylistsReadyFlow get() = PlayerManager.localPlaylistsReadyFlow

    // 定时器管理器会被整体替换, 每次都取当前实例的状态
    override val sleepTimerStateFlow get() = PlayerManager.sleepTimerManager.timerState
    override val preferWordTimedLyrics get() = PlayerManager.preferWordTimedLyrics
    override val defaultLyricSource get() = PlayerManager.defaultLyricSource
    override val lyricsStages get() = DefaultNowPlayingLyricsStages
    override val secondaryActionsEnabled get() = true

    override fun currentQueueDisplaySnapshot() = PlayerManager.currentQueueDisplaySnapshot()
    override fun isBiliCcSubtitleLyricsActive(song: SongItem?) = PlayerManager.isBiliCcSubtitleLyricsActive(song)
    override fun togglePlayPause() = PlayerManager.togglePlayPause()
    override fun next() = PlayerManager.next()
    override fun previous() = PlayerManager.previous()
    override fun setShuffle(enabled: Boolean) = PlayerManager.setShuffle(enabled)
    override fun cycleRepeatMode() = PlayerManager.cycleRepeatMode()
    override fun cycleHeartbeatMode() = PlayerManager.cycleHeartbeatMode()
    override fun seekTo(positionMs: Long) = PlayerManager.seekTo(positionMs)
    override fun toggleCurrentFavorite() = PlayerManager.toggleCurrentFavorite()
    override fun addCurrentToPlaylist(playlistId: Long) = PlayerManager.addCurrentToPlaylist(playlistId)
    override fun changeCurrentPlaybackQuality(optionKey: String) = PlayerManager.changeCurrentPlaybackQuality(optionKey)
}

internal val LocalNowPlayingPlaybackSource =
    staticCompositionLocalOf<NowPlayingPlaybackSource> { PlayerManagerNowPlayingPlaybackSource }
