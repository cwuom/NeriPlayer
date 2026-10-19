@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.queue.heartbeat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.playback.playAtIndex
import moe.ouom.neriplayer.core.player.persistence.scheduleStatePersist
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import moe.ouom.neriplayer.data.model.playback.queue.QueueInsertPlacement
import moe.ouom.neriplayer.platform.netease.playmode.NeteasePlaymodeIntelligenceRepository
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.core.player.queue.heartbeat.HeartbeatCandidateExhaustionTracker
import moe.ouom.neriplayer.core.player.queue.heartbeat.HeartbeatScorer

private const val TAG = "NERI-Heartbeat"

/** 本次心跳是否开启；进程内有效，跨进程恢复由持久化层负责。 */
@Volatile
internal var heartbeatModeState: Int = HeartbeatMode.OFF

/**
 * 心动模式开关的对外可观察状态，与 [PlayerManager.repeatModeFlow] 保持同构。
 *
 * UI 只能通过本 Flow 观察开关，不允许直接写 [heartbeatModeState]，
 * 以免出现「状态已变但界面未刷新」的分叉。
 */
private val heartbeatModeStateFlow = MutableStateFlow(HeartbeatMode.OFF)

/**
 * 官方接口是否可用的可观察状态。
 *
 * 语义是「官方接口本次可用」，**不是**「已登录」：已登录但接口临时失败时本值为 false，
 * UI 据此走「本地推荐」文案。
 */
private val heartbeatOfficialAvailableStateFlow = MutableStateFlow(false)

/** 由 `app` 的 DI 容器在启动时安装一次的官方仓库引用；未安装时官方能力视为不可用。 */
@Volatile
private var heartbeatOfficialRepository: NeteasePlaymodeIntelligenceRepository? = null

/** 心跳选择器由 DI 容器注入；未注入时心动模式自动降级为「无操作」。 */
@Volatile
private var heartbeatSelectorHolder: HeartbeatNextTrackSelector? = null

/** 预取的下一首就绪快照，用于把挂起的官方接口桥接到同步推进链路。 */
private val heartbeatPending = HeartbeatPendingNextStore()

/**
 * 候选耗尽判定：开着心动模式却连续 N 次拿不到候选时置位，
 * UI 据此提示「心动模式候选歌曲不足，已回退到原有模式」（任务书 §四）。
 */
private val heartbeatCandidateExhaustion = HeartbeatCandidateExhaustionTracker()

internal val PlayerManager.isHeartbeatEnabled: Boolean
    get() = heartbeatModeState == HeartbeatMode.ON

internal val PlayerManager.heartbeatMode: Int
    get() = heartbeatModeState

/** 预取到的下一首；没有则返回 null。仅供推进链路读取。 */
internal val PlayerManager.heartbeatReadyNextSong: SongItem?
    get() = heartbeatPending.current()

/** 取走预取结果（一次性语义），避免同一首歌被连续播放两次。 */
internal fun PlayerManager.consumeHeartbeatReadyNextSong(): SongItem? = heartbeatPending.take()

/** 由 `app` 的 DI 容器在启动时安装一次。 */
fun installHeartbeatSelector(selector: HeartbeatNextTrackSelector) {
    heartbeatSelectorHolder = selector
}

/**
 * 由 `app` 的 DI 容器在启动时安装一次官方仓库引用。
 *
 * 只有装上了官方仓库，[heartbeatOfficialAvailableFlow] 才有可能为 true；
 * 未安装时心动模式仍可开启，但只能走本地降级。
 */
fun installHeartbeatOfficialRepository(repository: NeteasePlaymodeIntelligenceRepository) {
    heartbeatOfficialRepository = repository
    refreshHeartbeatOfficialAvailability()
}

/**
 * 心动模式开关状态。UI 只读此 Flow；写只能经由 [PlayerManager.setHeartbeatMode]。
 */
val PlayerManager.heartbeatModeFlow: StateFlow<Int>
    get() = heartbeatModeStateFlow

/**
 * 官方接口可用性状态。
 *
 * 语义是「官方接口本次可用」而非「已登录」：未登录、没有红心歌单、
 * 连续失败达到熔断上限都会让它变成 false，UI 据此切到「本地推荐」文案。
 */
val PlayerManager.heartbeatOfficialAvailableFlow: StateFlow<Boolean>
    get() = heartbeatOfficialAvailableStateFlow

/**
 * 心动模式候选是否已耗尽。
 *
 * true 表示「开着心动模式但连续多次拿不到候选，已回退原有播放模式」，
 * UI 据此提示「心动模式候选歌曲不足，已回退到原有模式」（任务书 §四）。
 * 拿到一次候选即自动复位为 false，不需要手动清理。
 */
val PlayerManager.heartbeatCandidateExhaustedFlow: StateFlow<Boolean>
    get() = heartbeatCandidateExhaustion.isExhaustedFlow

/**
 * 重新判定官方接口可用性。
 *
 * 判定规则刻意保持宽松：只要登录态还在且没有熔断，就认为官方可用，
 * 真正能否拿到推荐由请求结果决定——失败时静默降级，不在这里反复横跳。
 */
private fun refreshHeartbeatOfficialAvailability() {
    val repository = heartbeatOfficialRepository
    val available = repository != null &&
        runCatching { repository.isLoggedIn() }.getOrDefault(false) &&
        !isHeartbeatOfficialCircuitOpen()
    heartbeatOfficialAvailableStateFlow.value = available
}

/** 熔断状态由选择器持有，这里只做读取，不在这里改。 */
private fun isHeartbeatOfficialCircuitOpen(): Boolean =
    (heartbeatSelectorHolder as? HeartbeatNextTrackCoordinator)?.isOfficialCircuitOpen() == true

/**
 * 开启或关闭心动模式。
 *
 * 开启时会异步预取一次下一首（任务书 §3.5 步骤 2），避免播完才请求导致卡顿；
 * 关闭时复位选择器内部状态（失败计数/熔断）与预取快照，下次开启重新计数。
 */
fun PlayerManager.setHeartbeatMode(
    mode: Int,
    commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL,
) {
    val normalized = HeartbeatMode.normalized(mode)
    val changed = heartbeatModeState != normalized
    heartbeatModeState = normalized
    heartbeatModeStateFlow.value = normalized
    if (!changed) return
    NPLogger.d(TAG, "setHeartbeatMode: $normalized, source=$commandSource")
    if (normalized == HeartbeatMode.OFF) {
        heartbeatPending.clear()
        heartbeatCandidateExhaustion.reset()
        (heartbeatSelectorHolder as? HeartbeatNextTrackCoordinator)?.reset()
    } else {
        heartbeatCandidateExhaustion.reset()
        refreshHeartbeatOfficialAvailability()
        requestHeartbeatSelectionAsync("enable")
    }
}

fun PlayerManager.cycleHeartbeatMode(
    commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL,
) {
    setHeartbeatMode(
        if (heartbeatModeState == HeartbeatMode.ON) HeartbeatMode.OFF else HeartbeatMode.ON,
        commandSource,
    )
}

/**
 * 异步预取一次心动候选。
 *
 * 只由心跳自身触发，不阻塞任何调用方；异常全部吞掉并记录，
 * 因为「预取失败」是预期情况，静默降级到原有模式即可。
 */
internal fun PlayerManager.requestHeartbeatSelectionAsync(reason: String) {
    if (heartbeatModeState != HeartbeatMode.ON) return
    val selector = heartbeatSelectorHolder ?: return
    val currentSong = _currentSongFlow.value ?: return
    val queue = currentPlaylist.toList()
    PlayerDependencies.launchBackgroundIo {
        val next = try {
            selector.selectNext(currentSong, queue)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            NPLogger.w(TAG, "预取失败（reason=$reason），本次沿用原有模式", error)
            null
        }
        if (next == null) {
            heartbeatPending.publish(null)
            heartbeatCandidateExhaustion.onNoCandidate(HeartbeatScorer.identityKey(currentSong))
            refreshHeartbeatOfficialAvailability()
            NPLogger.d(TAG, "预取无候选（reason=$reason）")
        } else {
            heartbeatPending.publish(next)
            heartbeatCandidateExhaustion.onCandidate()
            refreshHeartbeatOfficialAvailability()
            NPLogger.d(TAG, "预取成功（reason=$reason）：${next.name}/${next.id}")
        }
    }
}

/**
 * 挂起版「下一首」入口：直接选一首并播放。
 *
 * 供 UI（设置项/更多操作）等本身就在协程里的调用方使用，
 * 播放推进链路请改用同步的 [heartbeatAdvanceOrFallback]，避免在主线程等待 IO。
 */
internal suspend fun PlayerManager.tryAdvanceWithHeartbeat(
    reason: String,
    commandSource: PlaybackCommandSource = activePlaybackCommandSource,
): Boolean {
    if (heartbeatModeState != HeartbeatMode.ON) return false
    val selector = heartbeatSelectorHolder ?: return false
    if (!initialized) return false
    val currentSong = _currentSongFlow.value ?: return false

    val next = try {
        selector.selectNext(currentSong, currentPlaylist.toList())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        NPLogger.w(TAG, "selectNext 失败（reason=$reason），回退原有模式", error)
        null
    }
    if (next == null) {
        heartbeatCandidateExhaustion.onNoCandidate(HeartbeatScorer.identityKey(currentSong))
        NPLogger.d(TAG, "心动模式无候选（reason=$reason），回退原有模式")
        return false
    }
    heartbeatCandidateExhaustion.onCandidate()
    NPLogger.d(TAG, "心动模式选中下一首（reason=$reason）：${next.name}/${next.id}")
    insertHeartbeatSongAndPlay(next, commandSource)
    return true
}

/**
 * 把心动模式选中的歌曲插到 NEXT 位置并播放。
 *
 * 复用既有的 `queueEdits.insert(...)` 与 `playAtIndex`，
 * 不复制第二份插入逻辑，保证队列修订号、持久化、命令上报与「插播」完全一致。
 */
internal fun PlayerManager.insertHeartbeatSongAndPlay(
    song: SongItem,
    commandSource: PlaybackCommandSource,
) {
    if (currentPlaylist.isEmpty()) {
        playPlaylist(listOf(song), 0, commandSource)
        return
    }
    var insertedIndex = -1
    val updatedQueue = updateCurrentQueue(bumpDisplayRevision = true) { snapshot ->
        val planned = queueEdits.insert(snapshot, song, _currentSongFlow.value, QueueInsertPlacement.NEXT)
            ?: return@updateCurrentQueue null
        insertedIndex = planned.insertedIndex
        planned.queue
    } ?: return

    consecutivePlayFailures = 0
    playAtIndex(index = updatedQueue.currentIndex, commandSource = commandSource)
    emitPlaybackCommand(
        type = "PLAY_FROM_QUEUE",
        source = commandSource,
        queue = updatedQueue.playlist,
        currentIndex = updatedQueue.currentIndex,
        positionMs = _playbackPositionMs.value,
    )
    scheduleStatePersist(debounceMs = 0L)
    NPLogger.d(
        TAG,
        "insertHeartbeatSongAndPlay: song=${song.name}/${song.id}, insertIndex=$insertedIndex, queueSize=${updatedQueue.playlist.size}",
    )
}
