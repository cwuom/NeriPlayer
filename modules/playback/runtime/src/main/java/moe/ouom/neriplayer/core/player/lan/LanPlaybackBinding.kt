package moe.ouom.neriplayer.core.player.lan

import moe.ouom.neriplayer.core.player.queue.policy.PlayerQueueNavigationOwner
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.ltw.PlayerListenTogetherSongMapper
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource

/** Player-owned command boundary. The LAN library receives values, never PlayerManager internals. */
internal object LanPlaybackBinding {
    @Volatile var externalAudio: Boolean = false
    var command: ((String, ListenTogetherTrack?, Double) -> Unit)? = null
    var interruptAudio: (() -> Unit)? = null
    var stopped: (() -> Unit)? = null
    var isHost: () -> Boolean = { false }
    var offeredQueue: List<SongItem>? = null
    val active: Boolean get() = command != null
    fun interrupt(): Boolean {
        if (!externalAudio) return false
        interruptAudio?.invoke()
        return true
    }
    fun safetyPause(source: PlaybackCommandSource): Boolean =
        source == PlaybackCommandSource.LOCAL_SAFETY && interrupt()
    /** LAN 会话优先消费音频焦点丢失；否则回落到原音频路由处理。 */
    fun interruptOr(fallback: () -> Boolean): Boolean =
        interrupt() || fallback()
    /** 变速属房间级控制：LAN 会话中由主控统一下发，本机不单独改。 */
    fun blockSpeedChange(persist: Boolean): Boolean = persist && active
    /** 服务销毁回调转发，保持 LanPlaybackBinding 与服务生命周期的单向依赖。 */
    fun notifyServiceStopped() { stopped?.invoke() }
    fun toggle(): Boolean = externalAudio && route(
        if (PlayerManager.isPlayingFlow.value) "PAUSE" else "PLAY_PAUSE", PlaybackCommandSource.LOCAL)
    fun playExternal(song: SongItem, source: PlaybackCommandSource): Boolean {
        if (!externalAudio) return false
        route("NEXT", source, song, 0.0)
        return true
    }
    fun track(song: SongItem?): ListenTogetherTrack? = song?.let {
        LanLocalIdentity.track(it) ?: with(PlayerListenTogetherSongMapper) { it.toListenTogetherTrackOrNull(includeLocal = false) }
    }?.copy(streamUrl = null, streamUrls = emptyList(), mediaUri = null)
    fun route(intent: String, source: PlaybackCommandSource, song: SongItem? = PlayerManager.currentSongFlow.value, position: Double = PlayerManager.playbackPositionFlow.value / 1000.0): Boolean {
        if (source != PlaybackCommandSource.LOCAL) return false
        val callback = command ?: return false
        callback(intent, track(song), position)
        return true
    }
    fun playlist(songs: List<SongItem>, index: Int, source: PlaybackCommandSource): Boolean {
        if (!active || source != PlaybackCommandSource.LOCAL) return false
        offeredQueue = songs.toList()
        return route("NEXT", source, songs.getOrNull(index), 0.0)
    }
    fun step(direction: Int, source: PlaybackCommandSource, force: Boolean = false): Boolean {
        if (!active || source != PlaybackCommandSource.LOCAL) return false
        if (!isHost()) return route("STEP", source, position = direction.toDouble())
        val queue = PlayerManager.currentQueueFlow.value
        val index = if (direction > 0) PlayerQueueNavigationOwner.nextStep(queue.size, PlayerManager.currentIndex, PlayerManager.repeatModeSetting, force)?.index
            else PlayerQueueNavigationOwner.previousIndex(queue.size, PlayerManager.currentIndex, PlayerManager.repeatModeSetting)
        val next = index?.let(queue::getOrNull)
        if (next != null) route("NEXT", source, next, 0.0) else route("PAUSE", source)
        return true
    }
    fun ended(): Boolean {
        if (!active) return false
        if (isHost()) {
            val queue = PlayerManager.currentQueueFlow.value
            val plan = PlayerQueueNavigationOwner.listenTogetherFinishPlan(queue.size, PlayerManager.currentIndex, PlayerManager.repeatModeSetting)
            if (plan.shouldAdvance) route("NEXT", PlaybackCommandSource.LOCAL, queue.getOrNull(plan.nextIndex), 0.0)
            else route("PAUSE", PlaybackCommandSource.LOCAL)
        }
        return true
    }
    fun clear() { LanLocalIdentity.clear(); LanSharedPresentationStore.clear(); externalAudio = false; interruptAudio = null; command = null; stopped = null; isHost = { false }; offeredQueue = null }
}
