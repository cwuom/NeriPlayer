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
import moe.ouom.neriplayer.core.player.queue.heartbeat.HeartbeatSongIdentity
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
    _currentSongFlow.value?.let { HeartbeatSongIdentity.identityKey(it) }

/** 心动模式只对网易云音源生效：外部音源（B 站 / YouTube）既不预取也不接管推进。 */
internal fun PlayerManager.isHeartbeatSourceSupported(): Boolean =
    _currentSongFlow.value?.isNeteaseSource() == true

/** 由 `app` 的 DI 容器在启动时安装一次。 */
fun installHeartbeatSelector(selector: HeartbeatNextTrackSelector) {
    heartbeatSelectorHolder = selector
}

/** 由 `app` 的 DI 容器在启动时安装一次官方仓库引用，用来判断登录态。 */
fun installHeartbeatOfficialRepository(repository: NeteasePlaymodeIntelligenceRepository) {
    heartbeatOfficialRepository = repository
}

/**
 * 心动模式开关状态。UI 只读此 Flow；写只能经由 [PlayerManager.setHeartbeatMode]。
 */
val PlayerManager.heartbeatModeFlow: StateFlow<Int>
    get() = heartbeatModeStateFlow

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
) = applyHeartbeatMode(mode, commandSource, userInitiated, restoring = false)

/**
 * [restoring] 为 true 时是冷启动恢复：只恢复开关和插入记录，不落盘也不补歌。
 * 此时播放还没开始，落盘会把存档里「下次自动续播」的标记写成 false；
 * 补歌等开始播放、当前歌第一次变化时再做。
 */
private fun PlayerManager.applyHeartbeatMode(
    mode: Int,
    commandSource: PlaybackCommandSource,
    userInitiated: Boolean,
    restoring: Boolean,
) {
    val normalized = HeartbeatMode.normalized(mode)
    // 一起听房间里不允许开启：房间里的播放由房间控制，心动模式选出来的歌没人会听。
    // 只拦「开启」方向，关闭永远放行——否则用户进房间后就再也关不掉了。
    if (normalized == HeartbeatMode.ON && isListenTogetherActive()) {
        heartbeatRoomRejectedStateFlow.value += 1
        NPLogger.d(TAG, "setHeartbeatMode: 一起听房间内不允许开启，已拒绝（source=$commandSource）")
        return
    }
    // 只有网易云官方推荐，没登录就没有歌可补；界面在未登录时不提供心动档，这里只是兜底
    if (normalized == HeartbeatMode.ON && !isHeartbeatOfficialLoggedIn()) {
        NPLogger.d(TAG, "setHeartbeatMode: 未登录网易云，不开启（source=$commandSource）")
        return
    }
    val changed = heartbeatModeState != normalized
    heartbeatModeState = normalized
    if (changed) {
        // 代次先 +1：上一轮会话在途的预取请求回来后会被判定为过期，不会串到新会话里
        heartbeatSessionGeneration.incrementAndGet()
        // 暂停状态下切开关不会有任何播放操作，不主动落盘就得等下一次播放才写，
        // 中途进程被杀会让重启后的开关回退到旧值。
        if (!restoring) scheduleStatePersist()
        NPLogger.d(TAG, "setHeartbeatMode: $normalized, source=$commandSource, restoring=$restoring")
        if (normalized == HeartbeatMode.OFF) {
            removeHeartbeatSongsFromQueue(
                restoreQueue = !restoring && commandSource == PlaybackCommandSource.LOCAL
            )
            heartbeatCandidateExhaustion.reset()
            (heartbeatSelectorHolder as? HeartbeatNextTrackCoordinator)?.reset()
        } else {
            heartbeatCandidateExhaustion.reset()
            // 重新开启视为新会话：清空上一次会话的失败计数与熔断
            (heartbeatSelectorHolder as? HeartbeatNextTrackCoordinator)?.reset()
            // 用户重新开启是新会话；状态恢复要沿用落盘的记录，否则冷启动后关不回原队列
            if (userInitiated) synchronized(heartbeatInsertedKeys) { heartbeatInsertedKeys.clear() }
            if (!restoring) fillHeartbeatQueueAsync("enable")
        }
    }
    heartbeatModeStateFlow.value = normalized
}

private fun isHeartbeatOfficialLoggedIn(): Boolean {
    val repository = heartbeatOfficialRepository ?: return false
    return runCatching { repository.isLoggedIn() }.getOrDefault(false)
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
 * 与用户手动切换走同一套状态重置，但不落盘、不补歌，见 [applyHeartbeatMode]。
 */
internal fun PlayerManager.restoreHeartbeatModeFromState(mode: Int, insertedKeys: List<String> = emptyList()) {
    synchronized(heartbeatInsertedKeys) {
        heartbeatInsertedKeys.clear()
        heartbeatInsertedKeys.addAll(insertedKeys)
    }
    applyHeartbeatMode(mode, PlaybackCommandSource.LOCAL, userInitiated = false, restoring = true)
}

/**
 * 随机与心动互斥：任何入口（平板迷你播放器、车机、歌单「随机播放」）开随机前都先关心动。
 * 必须在随机记下「还原队列」之前关：先关才能把插进来的心动歌删掉，还原队列才是原队列。
 */
internal fun PlayerManager.turnOffHeartbeatBeforeShuffle(enabled: Boolean, commandSource: PlaybackCommandSource) {
    if (!enabled || heartbeatModeState != HeartbeatMode.ON) return
    setHeartbeatMode(HeartbeatMode.OFF, commandSource)
}

/** 用户自己把这首歌加进队列（下一首播放 / 添加到末尾）后，它就不再算心动插入的，关心动时不能删。 */
internal fun releaseHeartbeatOwnership(song: SongItem) {
    synchronized(heartbeatInsertedKeys) { heartbeatInsertedKeys.remove(heartbeatKeyOf(song)) }
}

/** 外部整体替换队列（换歌单播放）时调用：此后队列里的歌都不归心动模式所有。 */
internal fun clearHeartbeatInsertedSongs() {
    synchronized(heartbeatInsertedKeys) { heartbeatInsertedKeys.clear() }
}

/** 落盘用：当前队列里由心动模式插入的歌。 */
internal fun heartbeatInsertedKeysSnapshot(): List<String> =
    synchronized(heartbeatInsertedKeys) { heartbeatInsertedKeys.toList() }

/** 当前歌变化后检查一次：后面的心动歌快播完了就再补一批。由 [PlayerManager.publishCurrentSong] 调用。 */
internal fun PlayerManager.heartbeatOnCurrentSongChanged(song: SongItem?) {
    if (song == null) return
    fillHeartbeatQueueAsync("songChanged")
}

private fun heartbeatKeyOf(song: SongItem): String = HeartbeatSongIdentity.identityKey(song)

private fun heartbeatInsertedSnapshot(): Set<String> =
    synchronized(heartbeatInsertedKeys) { heartbeatInsertedKeys.toSet() }

private fun PlayerManager.shouldFillHeartbeatQueue(): Boolean {
    if (heartbeatModeState != HeartbeatMode.ON) return false
    // 外部音源（B 站 / YouTube）没有网易云 id 可问；一起听房间的队列由房间控制
    if (!isHeartbeatSourceSupported() || isListenTogetherActive()) return false
    // 开着心动时退出登录：不再请求，也就不会弹「拿不到推荐」
    if (!isHeartbeatOfficialLoggedIn()) return false
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
            throw error
        } catch (error: Throwable) {
            NPLogger.w(TAG, "补充心动歌失败（reason=$reason），按原队列继续", error)
            emptyList()
        } finally {
            // 在这里就释放补货名额：播放器释放时 mainScope 会被取消，下面的协程可能根本不执行
            heartbeatFillInFlight.set(false)
        }
        mainScope.launch { applyHeartbeatBatch(reason, anchor, generation, currentSong, batch) }
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
        // 旧请求占着补货名额时，新会话开启时的那次检查被跳过了，这里替它补一次
        fillHeartbeatQueueAsync("sessionChanged")
        return
    }
    if (heartbeatPendingAnchor() != anchor) {
        NPLogger.d(TAG, "补货期间当前歌已变（reason=$reason），按新歌重新检查")
        fillHeartbeatQueueAsync("anchorMoved")
        return
    }
    // 请求期间进了一起听（含局域网）房间：队列由房间控制，结果不能插进去
    if (isListenTogetherActive()) {
        NPLogger.d(TAG, "补货期间进入一起听房间（reason=$reason），丢弃")
        return
    }
    if (batch.isEmpty()) {
        heartbeatCandidateExhaustion.onNoCandidate(heartbeatKeyOf(seedSong))
        NPLogger.d(TAG, "没有可补的心动歌（reason=$reason）")
        return
    }
    heartbeatCandidateExhaustion.onCandidate()
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
