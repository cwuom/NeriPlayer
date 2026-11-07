package moe.ouom.neriplayer.data.ltw.playback

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import moe.ouom.neriplayer.data.ltw.session.link.ListenTogetherLinkPlaybackPort
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.PlaybackCommand
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource

interface ListenTogetherPlaybackHost : ListenTogetherLinkPlaybackPort {
    /**
     * 会话身份真正切换（加入新房间 / 换房）时回调一次。
     *
     * 心动模式与一起听互斥：房间里的播放由房间控制，心动模式选出来的歌没人会听，
     * 所以加入房间时要把心动模式关掉。放在这个接口上而不是让一起听模块直接依赖
     * `playback/runtime`，是为了保住既有分层。
     *
     * 默认空实现，测试替身不必关心。
     */
    fun onListenTogetherSessionJoined() {}

    val currentSongFlow: StateFlow<SongItem?>
    val currentQueueFlow: StateFlow<List<SongItem>>
    val isPlayingFlow: StateFlow<Boolean>
    val playWhenReadyFlow: StateFlow<Boolean>
    val playerPlaybackStateFlow: StateFlow<Int>
    val playbackPositionFlow: StateFlow<Long>
    val currentMediaUrlFlow: StateFlow<String?>
    val repeatModeFlow: StateFlow<Int>
    val shuffleModeFlow: StateFlow<Boolean>
    val playbackCommandFlow: Flow<PlaybackCommand>
    val shuffleRestorePlaylistReference: List<SongItem>?
    val listenTogetherSyncPlaybackRate: Float
    fun pauseImpl(forcePersist: Boolean, commandSource: PlaybackCommandSource, allowFadeOut: Boolean, debugReason: String)
    fun play(commandSource: PlaybackCommandSource)
    fun seekTo(positionMs: Long, commandSource: PlaybackCommandSource)
    fun playPlaylist(queue: List<SongItem>, currentIndex: Int, commandSource: PlaybackCommandSource)
    fun applyRemoteQueueUpdate(queue: List<SongItem>, currentIndex: Int)
    fun applyListenTogetherPlaybackMode(repeatMode: Int?, shuffleEnabled: Boolean?)
    fun resetListenTogetherSyncPlaybackRate()
    fun setListenTogetherSyncPlaybackRate(rate: Float)
    fun retryListenTogetherSafetyPauseResume()
    fun completeListenTogetherSafetyPauseResume()
    fun clearListenTogetherSafetyPause()
    fun shouldHoldListenTogetherPlaybackForSafetyPause(causeType: String?): Boolean
    fun shouldWaitForListenTogetherAuthoritativeStream(song: SongItem): Boolean
    fun isListenTogetherLocalResolutionPendingFor(song: SongItem): Boolean
    fun isListenTogetherAuthoritativeStreamConfirmedUnavailable(song: SongItem): Boolean
    fun currentPlaybackRequiresListenTogetherAuthoritativeStream(): Boolean
    fun currentListenTogetherShareableStreamUrls(): List<String>
    fun isPendingMediaLoadActive(): Boolean
    fun isTransportActive(): Boolean
    fun isUsbExclusiveOutputEnabled(): Boolean
}
