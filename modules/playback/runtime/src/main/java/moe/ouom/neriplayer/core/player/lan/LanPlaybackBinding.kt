package moe.ouom.neriplayer.core.player.lan

import moe.ouom.neriplayer.core.player.lan.track.LanLocalIdentity
import moe.ouom.neriplayer.core.player.lan.track.lanServerTrack
import moe.ouom.neriplayer.core.player.playback.suppressPlaybackForAudioRouteLoss
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
    var interruptAudio: ((String) -> Unit)? = null
    var stopped: (() -> Unit)? = null
    var hostTrackEnded: ((ListenTogetherTrack?, Boolean) -> Unit)? = null
    var isHost: () -> Boolean = { false }
    val queueOffers = LanQueueOffers()
    @Volatile var guestPlayback: LanGuestPlaybackSnapshot? = null
    val active: Boolean get() = command != null
    fun interrupt(reason: String = "AUDIO_SUSPENDED"): Boolean {
        if (!externalAudio) return false
        interruptAudio?.invoke(reason)
        return true
    }
    fun safetyPause(source: PlaybackCommandSource): Boolean =
        source == PlaybackCommandSource.LOCAL_SAFETY && interrupt()
    /** LAN 会话优先消费音频焦点丢失；否则回落到原音频路由处理。 */
    fun interruptOr(fallback: () -> Boolean): Boolean =
        interrupt() || fallback()
    /** 变速属房间级控制：LAN 会话中由主控统一下发，本机不单独改。 */
    fun blockSpeedChange(persist: Boolean): Boolean = persist && active
    /** 一起听监听端音频路由失效：优先中断 LAN 外部音频，无 LAN 会话时回落为静音抑制。 */
    fun routeLossInterruptOrSuppress(shouldMute: Boolean, reason: String) {
        if (!shouldMute) return
        if (interrupt()) return
        PlayerManager.suppressPlaybackForAudioRouteLoss(reason)
    }
    /** 服务销毁回调转发，保持 LanPlaybackBinding 与服务生命周期的单向依赖。 */
    fun notifyServiceStopped() { stopped?.invoke() }
    fun toggle(): Boolean = externalAudio && route("TOGGLE", PlaybackCommandSource.LOCAL)
    fun playExternal(song: SongItem, source: PlaybackCommandSource): Boolean {
        if (!externalAudio) return false
        route("NEXT", source, song, 0.0)
        return true
    }
    fun track(song: SongItem?): ListenTogetherTrack? = song?.let {
        lanServerTrack(it) ?: LanLocalIdentity.track(it)
            ?: with(PlayerListenTogetherSongMapper) { it.toListenTogetherTrackOrNull(includeLocal = false) }
    }?.copy(streamUrl = null, streamUrls = emptyList(), mediaUri = null)
    fun route(intent: String, source: PlaybackCommandSource, song: SongItem? = PlayerManager.currentSongFlow.value, position: Double = PlayerManager.playbackPositionFlow.value / 1000.0): Boolean {
        if (source != PlaybackCommandSource.LOCAL) return false
        val callback = command ?: return false
        if (intent == "NEXT") queueOffers.clear()
        callback(intent, track(song), position)
        return true
    }
    fun playlist(songs: List<SongItem>, index: Int, source: PlaybackCommandSource, fromCurrentQueue: Boolean = false): Boolean {
        if (!active || source != PlaybackCommandSource.LOCAL) return false
        val selected = track(songs.getOrNull(index))
        if (selected != null && isHost()) queueOffers.offer(songs, index, selected, fromCurrentQueue)
        command?.invoke("NEXT", selected, 0.0)
        return true
    }
    fun step(direction: Int, source: PlaybackCommandSource, force: Boolean = false): Boolean {
        if (!active || source != PlaybackCommandSource.LOCAL) return false
        if (!isHost()) return route("STEP", source, position = direction.toDouble())
        val queue = PlayerManager.currentQueueFlow.value
        val index = if (direction > 0) PlayerQueueNavigationOwner.nextStep(queue.size, PlayerManager.currentIndex, PlayerManager.repeatModeSetting, force)?.index
            else PlayerQueueNavigationOwner.previousIndex(queue.size, PlayerManager.currentIndex, PlayerManager.repeatModeSetting)
        if (index != null && index in queue.indices) playlist(queue, index, source, fromCurrentQueue = true) else route("PAUSE", source)
        return true
    }
    fun ended(): Boolean {
        if (!active) return false
        if (isHost()) {
            val queue = PlayerManager.currentQueueFlow.value
            val lastInPlaylist = PlayerManager.currentIndex >= queue.lastIndex
            checkEndTimer(lastInPlaylist)
            val plan = queue.takeIf { it.isNotEmpty() }?.let {
                PlayerQueueNavigationOwner.listenTogetherFinishPlan(it.size, PlayerManager.currentIndex, PlayerManager.repeatModeSetting)
            }
            val index = plan?.takeIf { it.shouldAdvance }?.nextIndex
            val next = index?.let { nextIndex ->
                track(queue.getOrNull(nextIndex))?.also { queueOffers.offerNavigation(queue, nextIndex, it) }
            }
            hostTrackEnded?.invoke(next, lastInPlaylist)
        } else {
            // 成员只有临时单曲队列，整张列表的末尾由房主报告
            checkEndTimer(lastInPlaylist = false)
        }
        return true
    }

    fun checkEndTimer(lastInPlaylist: Boolean) {
        val timer = PlayerManager.sleepTimerManager
        if (timer.shouldStopOnTrackEnd(lastInPlaylist)) {
            interrupt("SLEEP_TIMER")
            timer.cancel()
        }
    }

    fun onQueueCleared() {
        if (!active) return
        queueOffers.clear()
        if (isHost()) pauseRoomForEmptyQueue() else interrupt()
    }

    private fun pauseRoomForEmptyQueue(): Boolean = route("PAUSE", PlaybackCommandSource.LOCAL, null, 0.0)

    fun clear() { LanLocalIdentity.clear(); LanSharedPresentationStore.clear(); externalAudio = false; interruptAudio = null; command = null; stopped = null; hostTrackEnded = null; isHost = { false }; queueOffers.clear(); guestPlayback = null }
}
