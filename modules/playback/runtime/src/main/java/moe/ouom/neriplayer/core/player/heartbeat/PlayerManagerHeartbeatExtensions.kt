@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.heartbeat

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.persistence.scheduleStatePersist
import moe.ouom.neriplayer.core.player.queue.heartbeat.HeartbeatCandidateExhaustionTracker
import moe.ouom.neriplayer.core.player.queue.heartbeat.HeartbeatMode
import moe.ouom.neriplayer.core.player.queue.heartbeat.HeartbeatNextTrackCoordinator
import moe.ouom.neriplayer.core.player.queue.heartbeat.HeartbeatNextTrackSelector
import moe.ouom.neriplayer.core.player.queue.heartbeat.HeartbeatQueuePlan
import moe.ouom.neriplayer.core.player.queue.heartbeat.HeartbeatScorer
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import moe.ouom.neriplayer.data.model.playback.queue.PlayerQueueSnapshot
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

/** 开启心动模式时一次往队列里补多少首。 */
internal const val HEARTBEAT_BATCH_SIZE = 30

/** 当前歌后面连续的心动歌少于等于这个数时，再补一批，免得播到头才去请求。 */
internal const val HEARTBEAT_REFILL_THRESHOLD = 3

/**
 * 心动模式插进队列的歌（身份键）。
 *
 * 心动歌插在当前歌后面，原队列剩下的歌顺延；关闭心动时按这份记录把它们删掉，
 * 队列回到开启前的样子。随播放状态一起落盘，冷启动后关掉也能还原。
 */
private val heartbeatInsertedKeys = LinkedHashSet<String>()

/** 同一时间只补一批：补完之前切歌触发的补货请求直接跳过，补完后按新的当前歌再检查一次。 */
private val heartbeatFillInFlight = AtomicBoolean(false)

/**
 * 心动模式会话代次，每次开关状态变化就 +1。
 *
 * 光靠「当前歌」这个锚点挡不住一类过期结果：同一首歌上先关掉再打开时锚点没变，
 * 上一轮会话在途的请求回来后仍能通过锚点校验，被写进新一轮会话的待播槽。
 * 请求发起时记下代次，回来时代次变了就整份丢弃。
 */
private val heartbeatSessionGeneration = AtomicInteger(0)

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
internal fun PlayerManager.isHeartbeatSourceSupported(): Boolean =
    _currentSongFlow.value?.isNeteaseSource() == true

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
 * 只按「登录态还在且没有熔断」判断，请求失败时静默降级，不在这里反复横跳。
 * [honorLastResult] 为 true 时再叠加一次实际结果校验，用于选歌刚成功之后。
 */
private fun refreshHeartbeatOfficialAvailability(honorLastResult: Boolean = false) {
    val repository = heartbeatOfficialRepository
    val gateOpen = repository != null &&
        runCatching { repository.isLoggedIn() }.getOrDefault(false) &&
        !isHeartbeatOfficialCircuitOpen()
    // 登录态在、熔断也没开，但官方接口一直返回空：真正在出歌的是本地候选池。
    // 这时若还对外报「官方可用」，界面就会一直不说「本地推荐」，用户以为用着云端推荐。
    val producedByOfficial =
        (heartbeatSelectorHolder as? HeartbeatNextTrackCoordinator)?.lastResultFromOfficial == true
    heartbeatOfficialAvailableStateFlow.value =
        gateOpen && (!honorLastResult || producedByOfficial)
}

/** 熔断状态由选择器持有，这里只做读取，不在这里改。 */
private fun isHeartbeatOfficialCircuitOpen(): Boolean =
    (heartbeatSelectorHolder as? HeartbeatNextTrackCoordinator)?.isOfficialCircuitOpen() == true

/**
 * 开启或关闭心动模式。
 *
 * 开启时在当前歌后面一次补 [HEARTBEAT_BATCH_SIZE] 首心动歌，原队列顺延；
 * 关闭时复位选择器内部状态（失败计数/熔断），并把插进去的心动歌删掉，还原原队列。
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
        // 代次先 +1：上一轮会话在途的预取请求回来后会被判定为过期，不会串到新会话里
        heartbeatSessionGeneration.incrementAndGet()
        // 暂停状态下切开关不会有任何播放操作，不主动落盘就得等下一次播放才写，
        // 中途进程被杀会让重启后的开关回退到旧值。
        scheduleStatePersist()
        NPLogger.d(TAG, "setHeartbeatMode: $normalized, source=$commandSource")
        if (normalized == HeartbeatMode.OFF) {
            removeHeartbeatSongsFromQueue(restoreQueue = commandSource == PlaybackCommandSource.LOCAL)
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
            // 用户重新开启是新会话；状态恢复要沿用落盘的记录，否则冷启动后关不回原队列
            if (userInitiated) synchronized(heartbeatInsertedKeys) { heartbeatInsertedKeys.clear() }
            fillHeartbeatQueueAsync("enable")
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
internal fun PlayerManager.restoreHeartbeatModeFromState(mode: Int, insertedKeys: List<String> = emptyList()) {
    synchronized(heartbeatInsertedKeys) {
        heartbeatInsertedKeys.clear()
        heartbeatInsertedKeys.addAll(insertedKeys)
    }
    setHeartbeatMode(HeartbeatMode.normalized(mode), userInitiated = false)
}

/** 落盘用：当前队列里由心动模式插入的歌。 */
internal fun heartbeatInsertedKeysSnapshot(): List<String> =
    synchronized(heartbeatInsertedKeys) { heartbeatInsertedKeys.toList() }

/** 当前歌变化后检查一次：后面的心动歌快播完了就再补一批。由 [PlayerManager.publishCurrentSong] 调用。 */
internal fun PlayerManager.heartbeatOnCurrentSongChanged(song: SongItem?) {
    if (song == null) return
    fillHeartbeatQueueAsync("songChanged")
}

private fun heartbeatKeyOf(song: SongItem): String = HeartbeatScorer.identityKey(song)

private fun heartbeatInsertedSnapshot(): Set<String> =
    synchronized(heartbeatInsertedKeys) { heartbeatInsertedKeys.toSet() }

private fun PlayerManager.shouldFillHeartbeatQueue(): Boolean {
    if (heartbeatModeState != HeartbeatMode.ON) return false
    // 外部音源（B 站 / YouTube）没有网易云 id 可问；一起听房间的队列由房间控制
    if (!isHeartbeatSourceSupported() || isListenTogetherActive()) return false
    if (currentIndex !in currentPlaylist.indices) return false
    return HeartbeatQueuePlan.upcomingCount(currentPlaylist, currentIndex, heartbeatInsertedSnapshot()) <=
        HEARTBEAT_REFILL_THRESHOLD
}

/**
 * 在当前歌后面补一批心动歌。
 *
 * 选歌在后台线程，改队列回到主线程；期间开关被切过（代次变了）或用户切走了歌，结果整份丢弃，
 * 切走的情况再按新的当前歌检查一次。拿不到歌按原有顺序继续播，不打断播放。
 */
internal fun PlayerManager.fillHeartbeatQueueAsync(reason: String) {
    if (!shouldFillHeartbeatQueue()) return
    val selector = heartbeatSelectorHolder ?: return
    val currentSong = _currentSongFlow.value ?: return
    if (!heartbeatFillInFlight.compareAndSet(false, true)) return
    val anchor = heartbeatKeyOf(currentSong)
    val generation = heartbeatSessionGeneration.get()
    val queue = currentPlaylist.toList()
    PlayerDependencies.launchBackgroundIo {
        val batch = try {
            selector.selectBatch(currentSong, queue, HEARTBEAT_BATCH_SIZE)
        } catch (error: CancellationException) {
            heartbeatFillInFlight.set(false)
            throw error
        } catch (error: Throwable) {
            NPLogger.w(TAG, "补充心动歌失败（reason=$reason），按原队列继续", error)
            emptyList()
        }
        mainScope.launch {
            heartbeatFillInFlight.set(false)
            applyHeartbeatBatch(reason, anchor, generation, currentSong, batch)
        }
    }
}

private fun PlayerManager.applyHeartbeatBatch(
    reason: String,
    anchor: String,
    generation: Int,
    seedSong: SongItem,
    batch: List<SongItem>,
) {
    if (heartbeatSessionGeneration.get() != generation || heartbeatModeState != HeartbeatMode.ON) {
        NPLogger.d(TAG, "补货结果属于已结束的心动会话（reason=$reason），丢弃")
        return
    }
    if (heartbeatPendingAnchor() != anchor) {
        NPLogger.d(TAG, "补货期间当前歌已变（reason=$reason），按新歌重新检查")
        fillHeartbeatQueueAsync("anchorMoved")
        return
    }
    if (batch.isEmpty()) {
        heartbeatCandidateExhaustion.onNoCandidate(heartbeatKeyOf(seedSong))
        refreshHeartbeatOfficialAvailability()
        NPLogger.d(TAG, "没有可补的心动歌（reason=$reason）")
        return
    }
    heartbeatCandidateExhaustion.onCandidate()
    refreshHeartbeatOfficialAvailability(honorLastResult = true)
    insertHeartbeatBatch(batch)
}

private fun PlayerManager.insertHeartbeatBatch(batch: List<SongItem>) {
    val inserted = heartbeatInsertedSnapshot()
    var added = emptyList<SongItem>()
    updateCurrentQueue(bumpDisplayRevision = true) { snapshot ->
        val plan = HeartbeatQueuePlan.insertion(snapshot.playlist, snapshot.currentIndex, inserted, batch)
            ?: return@updateCurrentQueue null
        added = plan.songs
        PlayerQueueSnapshot.from(plan.playlist, snapshot.currentIndex)
    } ?: return
    synchronized(heartbeatInsertedKeys) { added.forEach { heartbeatInsertedKeys.add(heartbeatKeyOf(it)) } }
    emitHeartbeatQueueUpdate()
    NPLogger.d(TAG, "补充心动歌 ${added.size} 首，queueSize=${currentPlaylist.size}")
}

/**
 * 关闭心动模式时删掉插进来的心动歌；正在播的那首保留，不打断播放，播完接回原队列。
 *
 * 一起听房间自动关闭时 [restoreQueue] 为 false：房间队列由房间同步，不能在本地改。
 */
private fun PlayerManager.removeHeartbeatSongsFromQueue(restoreQueue: Boolean) {
    val keys = synchronized(heartbeatInsertedKeys) {
        heartbeatInsertedKeys.toSet().also { heartbeatInsertedKeys.clear() }
    }
    if (!restoreQueue || keys.isEmpty()) return
    updateCurrentQueue(bumpDisplayRevision = true) { snapshot ->
        queueEdits.remove(snapshot, HeartbeatQueuePlan.removalIndices(snapshot.playlist, snapshot.currentIndex, keys))
    } ?: return
    emitHeartbeatQueueUpdate()
    NPLogger.d(TAG, "关闭心动模式，已还原原队列，queueSize=${currentPlaylist.size}")
}

private fun PlayerManager.emitHeartbeatQueueUpdate() {
    emitPlaybackCommand(
        type = "SET_QUEUE",
        source = PlaybackCommandSource.LOCAL,
        queue = currentPlaylist.toList(),
        currentIndex = currentIndex,
        positionMs = _playbackPositionMs.value,
    )
    scheduleStatePersist(debounceMs = 0L)
}
