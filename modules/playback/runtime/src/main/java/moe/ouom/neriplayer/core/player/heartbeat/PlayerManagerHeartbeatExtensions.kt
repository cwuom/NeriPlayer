@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.heartbeat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.persistence.scheduleStatePersist
import moe.ouom.neriplayer.core.player.playback.playAtIndex
import moe.ouom.neriplayer.core.player.queue.heartbeat.HeartbeatCandidateExhaustionTracker
import moe.ouom.neriplayer.core.player.queue.heartbeat.HeartbeatMode
import moe.ouom.neriplayer.core.player.queue.heartbeat.HeartbeatNextTrackCoordinator
import moe.ouom.neriplayer.core.player.queue.heartbeat.HeartbeatNextTrackSelector
import moe.ouom.neriplayer.core.player.queue.heartbeat.HeartbeatPendingNextStore
import moe.ouom.neriplayer.core.player.queue.heartbeat.HeartbeatScorer
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import moe.ouom.neriplayer.platform.netease.playmode.NeteasePlaymodeIntelligenceRepository

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

/**
 * 「因进入一起听房间而被自动关闭」的次数，只增不减。
 *
 * 用计数器而不是布尔值：连续两次进房间各被关一次，UI 要各提示一次。
 * UI 只观察它的变化，不读具体数值。
 */
private val heartbeatRoomAutoOffStateFlow = MutableStateFlow(0)

/**
 * 「在房间里想开启却被拒绝」的次数，只增不减。
 *
 * 与 [heartbeatRoomAutoOffStateFlow] 分开，是因为两件事的提示文案不同：
 * 一个是「已关闭」，一个是「不能开启」。
 */
private val heartbeatRoomRejectedStateFlow = MutableStateFlow(0)

/**
 * 「用户主动开启心动模式」的次数，只增不减。
 *
 * 只统计用户主动开启（含点按钮、设置页开关），不统计状态恢复——
 * 否则每次冷启动都会弹一条提示。UI 观察它的变化来提示「当前是本地推荐」。
 */
private val heartbeatUserEnabledStateFlow = MutableStateFlow(0)

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

/**
 * 当前歌的身份键，用作预取结果的锚点。
 *
 * 预取是异步的，用户可能在结果回来前就切到别的歌；没有锚点的话，
 * 为 A 预取的候选会被当成 B 的下一首，进而截断 B 的队列尾部。
 */
private fun PlayerManager.heartbeatPendingAnchor(): String? =
    _currentSongFlow.value?.let { HeartbeatScorer.identityKey(it) }

/** 心动模式只对网易云音源生效：外部音源（B 站 / YouTube）既不预取也不接管推进。 */
internal fun PlayerManager.isHeartbeatSourceSupported(): Boolean {
    val song = _currentSongFlow.value ?: return false
    return !isBiliTrack(song) && !isYouTubeMusicTrack(song)
}

/** 预取到的下一首；没有、或不是为当前歌预取的，则返回 null。仅供推进链路读取。 */
internal val PlayerManager.heartbeatReadyNextSong: SongItem?
    get() {
        val anchor = heartbeatPendingAnchor() ?: return null
        return heartbeatPending.current(anchor)
    }

/** 取走预取结果（一次性语义），避免同一首歌被连续播放两次。 */
internal fun PlayerManager.consumeHeartbeatReadyNextSong(): SongItem? {
    val anchor = heartbeatPendingAnchor() ?: return null
    return heartbeatPending.take(anchor)
}

/** 由 `app` 的 DI 容器在启动时安装一次。 */
fun installHeartbeatSelector(selector: HeartbeatNextTrackSelector) {
    heartbeatSelectorHolder = selector
}

/**
 * 跳过行为的落盘入口，由 `app` 的 DI 容器注入。
 *
 * 做成接口而不是直接依赖 `HeartbeatSkipRepository`：`playback/runtime`
 * 虽然可以依赖 `local`，但心跳的其余数据都走 [HeartbeatNextTrackSelector]
 * 抽象注入，这里保持一致，单测也好替换。
 */
fun interface HeartbeatSkipRecorder {
    /** 给该曲的跳过次数 +1；实现方负责自己的异常处理与并发安全。 */
    suspend fun recordSkip(identityKey: String)
}

@Volatile
private var heartbeatSkipRecorder: HeartbeatSkipRecorder? = null

/** 由 `app` 的 DI 容器在启动时安装一次；未安装时跳过行为不落盘。 */
fun installHeartbeatSkipRecorder(recorder: HeartbeatSkipRecorder) {
    heartbeatSkipRecorder = recorder
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
 * 「进房间时心动模式被自动关掉」的计数，UI 观察它来弹一句提示。
 *
 * 一起听房间里的播放由房间控制，心动模式选出来的歌没人会听，
 * 所以加入房间时直接关掉（任务书 §八「优先房间控制」）。
 */
val PlayerManager.heartbeatRoomAutoOffFlow: StateFlow<Int>
    get() = heartbeatRoomAutoOffStateFlow

/**
 * 「在房间里试图开启心动模式被拒绝」的计数，UI 观察它来弹一句提示。
 */
val PlayerManager.heartbeatRoomRejectedFlow: StateFlow<Int>
    get() = heartbeatRoomRejectedStateFlow

/**
 * 「用户主动开启心动模式」的计数，UI 观察它来决定要不要提示当前推荐来源。
 *
 * 与 [heartbeatModeFlow] 的区别：状态恢复也会让 [heartbeatModeFlow] 变成 ON，
 * 但用户并没有做任何操作，不该被打扰。
 */
val PlayerManager.heartbeatUserEnabledFlow: StateFlow<Int>
    get() = heartbeatUserEnabledStateFlow

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
 *
 * [userInitiated] 标记这次调用是不是用户点出来的。恢复上次会话的状态也会走本函数，
 * 那种情况不该被当成「用户刚开启」，否则冷启动会凭空弹提示。
 */
fun PlayerManager.setHeartbeatMode(
    mode: Int,
    commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL,
    userInitiated: Boolean = commandSource == PlaybackCommandSource.LOCAL,
) {
    val normalized = HeartbeatMode.normalized(mode)
    // 一起听房间里不允许开启：房间里的播放由房间控制，心动模式选出来的歌没人会听。
    // 只拦「开启」方向，关闭永远放行——否则用户进房间后就再也关不掉了。
    if (normalized == HeartbeatMode.ON && isListenTogetherActive()) {
        heartbeatRoomRejectedStateFlow.value += 1
        NPLogger.d(TAG, "setHeartbeatMode: 一起听房间内不允许开启，已拒绝（source=$commandSource）")
        return
    }
    val changed = heartbeatModeState != normalized
    heartbeatModeState = normalized
    if (changed) {
        NPLogger.d(TAG, "setHeartbeatMode: $normalized, source=$commandSource")
        if (normalized == HeartbeatMode.OFF) {
            heartbeatPending.clear()
            heartbeatCandidateExhaustion.reset()
            (heartbeatSelectorHolder as? HeartbeatNextTrackCoordinator)?.reset()
            // 任务书 §3.6：退出心动模式时让候选池缓存失效，
            // 避免下次开启还沿用上次会话的收藏/历史快照。
            invalidateHeartbeatCandidateCache()
        } else {
            heartbeatCandidateExhaustion.reset()
            // 重新开启视为新会话：清空上一次会话的失败曲目黑名单（任务书 §3.5）
            (heartbeatSelectorHolder as? HeartbeatNextTrackCoordinator)?.reset()
            refreshHeartbeatOfficialAvailability()
            requestHeartbeatSelectionAsync("enable")
            // 只有用户主动开启才计数：状态恢复走的是同一个函数，但那是静默的。
            if (userInitiated) heartbeatUserEnabledStateFlow.value += 1
        }
    }
    // 开关状态最后才广播：UI 收到「已开启」时，官方可用性必须已经是刷新后的值，
    // 否则「登录后可启用云端推荐」这条提示会拿到上一轮的结果（例如上次熔断过就误报本地推荐）。
    heartbeatModeStateFlow.value = normalized
}

/**
 * 让本地候选池缓存失效（任务书 §3.6）。
 *
 * 退出心动模式、收藏/历史发生变更时调用；未安装本地源时静默无操作。
 */
internal fun PlayerManager.invalidateHeartbeatCandidateCache() {
    val coordinator = heartbeatSelectorHolder as? HeartbeatNextTrackCoordinator ?: return
    coordinator.invalidateLocalCandidates()
}

/**
 * 记录一首播放失败的歌曲，使其在本次心动会话内不再被推荐（任务书 §八）。
 *
 * 只影响本地降级候选的挑选；官方接口返回什么就播什么，不做客户端过滤（§1.3 红线）。
 */
internal fun PlayerManager.markHeartbeatSongFailed(song: SongItem) {
    if (heartbeatModeState != HeartbeatMode.ON) return
    (heartbeatSelectorHolder as? HeartbeatNextTrackCoordinator)?.markFailed(song)
}

/**
 * 记录一次「用户在心动模式下主动跳过」（任务书 §七 第三阶段）。
 *
 * 与 [markHeartbeatSongFailed] 的区别：跳过不是失败，只是这一次不想听，
 * 因此不排除该曲，只把次数交给候选源并入重复惩罚衰减。
 * 只在心动模式开启时记录——关着的时候用户点下一首是普通操作，不该被算作负反馈。
 */
internal fun PlayerManager.recordHeartbeatSkip(song: SongItem) {
    if (heartbeatModeState != HeartbeatMode.ON) return
    val recorder = heartbeatSkipRecorder ?: return
    val key = HeartbeatScorer.identityKey(song)
    PlayerDependencies.launchBackgroundIo {
        runCatching { recorder.recordSkip(key) }
            .onFailure { NPLogger.w(TAG, "记录跳过失败：$key", it) }
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
 * 加入一起听房间时把心动模式关掉（任务书 §八「优先房间控制」）。
 *
 * 「直接关」而不是「先记住、退出房间再恢复」：用户离开房间后多半会换歌单继续听，
 * 自动恢复成心动模式反而会在不知情的情况下改变选歌行为，不如让他自己再开一次。
 *
 * 本来就没开就什么都不做，也不会弹提示——没发生的事不该打扰用户。
 */
fun PlayerManager.disableHeartbeatForListenTogetherRoom() {
    if (heartbeatModeState != HeartbeatMode.ON) return
    setHeartbeatMode(HeartbeatMode.OFF, PlaybackCommandSource.REMOTE_SYNC)
    heartbeatRoomAutoOffStateFlow.value += 1
    NPLogger.d(TAG, "disableHeartbeatForListenTogetherRoom: 进入一起听房间，心动模式已关闭")
}

/**
 * 恢复上次退出时的心动模式开关。
 *
 * 走 [setHeartbeatMode] 而不是直接写 `heartbeatModeState`：开启时会顺带刷新官方可用性、
 * 清理预取、重置选择器，恢复路径与用户手动开开关保持同一套不变量。
 * `userInitiated = false`：这是恢复上次状态，不是用户刚点出来的，不该弹提示。
 */
internal fun PlayerManager.restoreHeartbeatModeFromState(mode: Int) {
    setHeartbeatMode(HeartbeatMode.normalized(mode), userInitiated = false)
}

/**
 * 异步预取一次心动候选。
 *
 * 只由心跳自身触发，不阻塞任何调用方；异常全部吞掉并记录，
 * 因为「预取失败」是预期情况，静默降级到原有模式即可。
 */
internal fun PlayerManager.requestHeartbeatSelectionAsync(reason: String) {
    if (heartbeatModeState != HeartbeatMode.ON) return
    if (!isHeartbeatSourceSupported()) {
        // 外部音源（B 站 / YouTube）没有网易云 id 可问，预取只会白跑一次网络
        heartbeatPending.clear()
        return
    }
    val selector = heartbeatSelectorHolder ?: return
    val currentSong = _currentSongFlow.value ?: return
    val anchor = HeartbeatScorer.identityKey(currentSong)
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
        // 请求期间用户可能已经切走：锚点变了就整份作废，免得把 A 的候选当成 B 的下一首。
        if (heartbeatPendingAnchor() != anchor) {
            NPLogger.d(TAG, "预取结果已过期（reason=$reason），丢弃")
            return@launchBackgroundIo
        }
        if (next == null) {
            heartbeatPending.publish(anchor, null)
            heartbeatCandidateExhaustion.onNoCandidate(HeartbeatScorer.identityKey(currentSong))
            refreshHeartbeatOfficialAvailability()
            NPLogger.d(TAG, "预取无候选（reason=$reason）")
        } else {
            heartbeatPending.publish(anchor, next)
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
    if (!isHeartbeatSourceSupported()) {
        heartbeatPending.clear()
        return false
    }
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
    appendHeartbeatSongAndPlay(next, commandSource)
    return true
}

/**
 * 把心动模式选中的歌曲接到「心动流」末尾并播放。
 *
 * 为什么不再插播：心动模式的语义是「从当前这首歌往后长出一条持续更新的歌流」。
 * 插到 NEXT 位置时，配合 `PlayerQueueEditOwner.insertNext` 的「已存在则先移除」
 * 策略，队列长度始终不变，用户听到的就是「十几首歌反复循环、偶尔混进一首新歌」。
 *
 * 做法是把当前歌之后的歌整段换成心动候选：先截断到当前歌，再追加候选，
 * 由 `PlayerQueueEditOwner.appendReplacingTail` 在**同一次**队列更新里完成。
 * 这样队列里的歌永远等于「已播过的 + 心动流」，不会留下「排在后面却永远轮不到」的孤儿歌。
 *
 * 截断只发生在真正切歌时（本函数被调用时），开启心动模式本身不动队列——
 * 用户开了开关又关掉，队列不会少任何一首歌。
 *
 * 复用既有的 `queueEdits.insert(...)` 与 `playAtIndex`，
 * 不复制第二份插入逻辑，保证队列修订号、持久化、命令上报与其他插入路径完全一致。
 */
internal fun PlayerManager.appendHeartbeatSongAndPlay(
    song: SongItem,
    commandSource: PlaybackCommandSource,
    resetFailureCount: Boolean = true,
) {
    if (currentPlaylist.isEmpty()) {
        playPlaylist(listOf(song), 0, commandSource)
        return
    }
    var appendedIndex = -1
    val updatedQueue = updateCurrentQueue(bumpDisplayRevision = true) { snapshot ->
        // `appendReplacingTail` 已把当前下标指向新接上的候选，
        // 不需要再 selecting 一次，否则会把候选又指回原歌。
        val planned = queueEdits.appendReplacingTail(snapshot, song, _currentSongFlow.value)
            ?: return@updateCurrentQueue null
        appendedIndex = planned.insertedIndex
        planned.queue
    } ?: return

    // 播放失败后的接管不能清零，否则「连续失败」永远攒不到上限，退化成无限重试。
    if (resetFailureCount) consecutivePlayFailures = 0
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
        "appendHeartbeatSongAndPlay: song=${song.name}/${song.id}, appendIndex=$appendedIndex, queueSize=${updatedQueue.playlist.size}",
    )
    // 立刻为「下一次切歌」预取，否则用户连续点两次下一首时第二次会命中空池
    // 而回退到原有队列（真机表现为「第一次正确、第二次又播原队列下一首」）。
    requestHeartbeatSelectionAsync("afterAppend")
}
