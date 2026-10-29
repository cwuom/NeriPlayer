package moe.ouom.neriplayer.core.player.queue.heartbeat

import moe.ouom.neriplayer.data.model.SongItem

/**
 * 心动模式「下一首」协调器：官方接口优先，本地候选池兜底。
 *
 * 这是任务书 §3.2 的分层装配点，也是合规红线（§1.3）的守门人：
 * - 官方源返回非 null 时，**原样返回**，不经过 [HeartbeatScorer]、不二次排序、不二次过滤；
 * - 官方源返回 null 时才调用本地源；
 * - 两层都无候选时返回 null，由调用方回退到原有 `nextStep` 逻辑。
 *
 * 失败重试约束（§3.5）：同一首歌连续失败 [MAX_CONSECUTIVE_FAILURES] 次后，
 * 本次心动模式会话内不再调用官方源，全部走本地降级；直到 [reset] 被调用
 * （用户重新开启心动模式）才恢复。**禁止无限重试。**
 */
class HeartbeatNextTrackCoordinator(
    private val officialSource: HeartbeatNextTrackSelector? = null,
    private val localSource: HeartbeatNextTrackSelector? = null,
) : HeartbeatNextTrackSelector {

    private val lock = Any()

    /** 当前歌曲 id → 连续失败次数 */
    private val failureCounts = HashMap<String, Int>()

    /** 本次会话内官方源是否已被熔断 */
    @Volatile
    private var officialCircuitOpen: Boolean = false

    /** 最近一次成功从官方源取到结果，用于 UI 区分「官方」与「本地推荐」文案 */
    @Volatile
    var lastResultFromOfficial: Boolean = false
        private set

    override suspend fun selectNext(currentSong: SongItem, currentQueue: List<SongItem>): SongItem? {
        val official = selectFromOfficial(currentSong, currentQueue)
        if (official != null) {
            lastResultFromOfficial = true
            synchronized(lock) { failureCounts.remove(HeartbeatScorer.identityKey(currentSong)) }
            return official
        }
        lastResultFromOfficial = false
        return localSource?.selectNext(currentSong, currentQueue)
    }

    /** 用户重新开启心动模式时调用，重置熔断与失败计数 */
    fun reset() {
        synchronized(lock) {
            failureCounts.clear()
            officialCircuitOpen = false
        }
        lastResultFromOfficial = false
        (localSource as? LocalHeartbeatCandidateSource)?.resetFailures()
    }

    /**
     * 记录一首播放失败的歌曲（任务书 §八）。
     *
     * 转交给本地源做会话级排除：失败的曲目不该被再次推荐，
     * 否则「播放失败 → 自动换下一首 → 又选中同一首」会形成死循环。
     */
    fun markFailed(song: SongItem) {
        (localSource as? LocalHeartbeatCandidateSource)?.markFailed(song)
    }

    /** 让本地候选池缓存失效（任务书 §3.6：退出心动模式 / 收藏变更时调用）。 */
    fun invalidateLocalCandidates() {
        (localSource as? LocalHeartbeatCandidateSource)?.invalidate()
    }

    /** 仅供测试与诊断 */
    fun isOfficialCircuitOpen(): Boolean = officialCircuitOpen

    private suspend fun selectFromOfficial(
        currentSong: SongItem,
        currentQueue: List<SongItem>,
    ): SongItem? {
        val source = officialSource ?: return null
        if (officialCircuitOpen) return null
        val key = HeartbeatScorer.identityKey(currentSong)
        val alreadyFailed = synchronized(lock) { failureCounts[key] ?: 0 }
        if (alreadyFailed >= MAX_CONSECUTIVE_FAILURES) return null

        val result = try {
            source.selectNext(currentSong, currentQueue)
        } catch (error: Throwable) {
            // 官方源实现约定自行吞异常，这里再兜一层，防止实现违约把播放打断
            if (error is kotlinx.coroutines.CancellationException) throw error
            null
        }
        if (result == null) {
            synchronized(lock) {
                val next = (failureCounts[key] ?: 0) + 1
                failureCounts[key] = next
                if (next >= MAX_CONSECUTIVE_FAILURES) officialCircuitOpen = true
            }
        }
        return result
    }

    companion object {
        /** 同一首歌连续失败多少次后熔断官方源（任务书 §3.5） */
        const val MAX_CONSECUTIVE_FAILURES = 3
    }
}
