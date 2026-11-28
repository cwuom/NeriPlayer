package moe.ouom.neriplayer.core.player.queue.heartbeat

import moe.ouom.neriplayer.data.model.SongItem

/**
 * 心动模式选歌协调器：只走网易云官方推荐，并负责失败熔断。
 *
 * - 官方源返回的结果**原样返回**，不二次排序、不二次过滤；
 * - 官方源拿不到时返回 null / 空列表，由调用方按原队列继续播放。
 *
 * 失败重试约束：同一首歌连续失败 [MAX_CONSECUTIVE_FAILURES] 次后，
 * 本次心动模式会话内不再请求官方源，直到 [reset] 被调用（用户重新开启心动模式）。**禁止无限重试。**
 */
class HeartbeatNextTrackCoordinator(
    private val officialSource: HeartbeatNextTrackSelector? = null,
) : HeartbeatNextTrackSelector {

    private val lock = Any()

    /** 当前歌曲身份键 → 连续失败次数 */
    private val failureCounts = HashMap<String, Int>()

    /** 本次会话内官方源是否已被熔断 */
    @Volatile
    private var officialCircuitOpen: Boolean = false

    /**
     * 会话代次：每次 [reset] 递增。
     *
     * [reset] 只清状态、打断不了已经在途的选歌请求。那些请求回来后如果照旧写回
     * `failureCounts` / `officialCircuitOpen`，就会把「刚重置好的新会话」重新标成熔断。
     * 所以写回前一律比对代次，过期就丢弃。
     */
    @Volatile
    private var sessionGeneration: Int = 0

    override suspend fun selectNext(currentSong: SongItem, currentQueue: List<SongItem>): SongItem? {
        val generation = sessionGeneration
        val official = selectFromOfficial(currentSong, generation) { it.selectNext(currentSong, currentQueue) }
        recordOfficialOutcome(currentSong, generation, official != null)
        return official
    }

    /** 与 [selectNext] 同一套熔断规则：官方给出非空批次就原样返回。 */
    override suspend fun selectBatch(
        currentSong: SongItem,
        currentQueue: List<SongItem>,
        limit: Int,
    ): List<SongItem> {
        val generation = sessionGeneration
        val official = selectFromOfficial(currentSong, generation) { source ->
            source.selectBatch(currentSong, currentQueue, limit).takeIf { it.isNotEmpty() }
        }
        recordOfficialOutcome(currentSong, generation, official != null)
        return official.orEmpty()
    }

    private fun recordOfficialOutcome(currentSong: SongItem, generation: Int, succeeded: Boolean) {
        if (generation != sessionGeneration || !succeeded) return
        synchronized(lock) { failureCounts.remove(HeartbeatSongIdentity.identityKey(currentSong)) }
    }

    /** 用户重新开启心动模式时调用，重置熔断与失败计数 */
    fun reset() {
        synchronized(lock) {
            sessionGeneration++
            failureCounts.clear()
            officialCircuitOpen = false
        }
    }

    /** 仅供测试与诊断 */
    fun isOfficialCircuitOpen(): Boolean = officialCircuitOpen

    private suspend fun <T : Any> selectFromOfficial(
        currentSong: SongItem,
        generation: Int,
        fetch: suspend (HeartbeatNextTrackSelector) -> T?,
    ): T? {
        val source = officialSource ?: return null
        if (officialCircuitOpen) return null
        val key = HeartbeatSongIdentity.identityKey(currentSong)
        val alreadyFailed = synchronized(lock) { failureCounts[key] ?: 0 }
        if (alreadyFailed >= MAX_CONSECUTIVE_FAILURES) return null

        val result = try {
            fetch(source)
        } catch (error: Throwable) {
            // 官方源实现约定自行吞异常，这里再兜一层，防止实现违约把播放打断
            if (error is kotlinx.coroutines.CancellationException) throw error
            null
        }
        if (result == null) {
            synchronized(lock) {
                // 期间会话已重置：这次失败属于上一轮，不能算到新会话头上
                if (generation == sessionGeneration) {
                    val next = (failureCounts[key] ?: 0) + 1
                    failureCounts[key] = next
                    if (next >= MAX_CONSECUTIVE_FAILURES) officialCircuitOpen = true
                }
            }
        }
        return result
    }

    companion object {
        /** 同一首歌连续失败多少次后熔断官方源 */
        const val MAX_CONSECUTIVE_FAILURES = 3
    }
}
