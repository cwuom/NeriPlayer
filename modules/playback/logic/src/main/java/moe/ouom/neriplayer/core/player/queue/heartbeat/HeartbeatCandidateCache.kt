package moe.ouom.neriplayer.core.player.queue.heartbeat

/**
 * 本地候选池的 TTL 缓存。
 *
 * 目的（任务书 §3.6 / §九 性能风险）：收藏歌曲可能有上千首，
 * 每次切歌都重建候选池会造成明显的卡顿，因此候选池在 5 分钟内复用。
 *
 * 约束：
 * - 纯内存实现，**不引入 Room 或任何新的持久化层**；
 * - 线程安全，构建过程在锁外执行，避免慢查询阻塞其它协程；
 * - 时钟可注入，便于单测验证 TTL 边界。
 */
class HeartbeatCandidateCache(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()

    @Volatile
    private var cachedPool: HeartbeatCandidatePool? = null

    @Volatile
    private var cachedAtMs: Long = 0L

    /** 构建代次：invalidate() 后递增，用于丢弃并发中产生的过期构建结果 */
    @Volatile
    private var generation: Long = 0L

    /**
     * 命中缓存则直接返回；否则调用 [rebuild] 重建并缓存。
     *
     * [rebuild] 允许是 suspend，以便直接调用仓库的挂起方法。
     * 若重建返回空池，**不会**被缓存，避免「一次网络抖动把候选池钉死 5 分钟」。
     */
    suspend fun getOrBuild(rebuild: suspend () -> HeartbeatCandidatePool): HeartbeatCandidatePool {
        val snapshot = synchronized(lock) {
            val pool = cachedPool
            if (pool != null && !isExpired(cachedAtMs, nowMs())) {
                return pool
            }
            generation
        }
        val built = rebuild()
        synchronized(lock) {
            // 构建期间若被 invalidate()（用户退出心动模式），丢弃本次结果
            if (generation == snapshot && !built.isEmpty) {
                cachedPool = built
                cachedAtMs = nowMs()
            }
        }
        return built
    }

    /** 主动失效：心动模式退出、收藏/历史发生变更时调用 */
    fun invalidate() {
        synchronized(lock) {
            cachedPool = null
            cachedAtMs = 0L
            generation++
        }
    }

    /** 仅供测试与诊断：当前是否持有未过期的缓存 */
    fun isCached(): Boolean = synchronized(lock) {
        cachedPool != null && !isExpired(cachedAtMs, nowMs())
    }

    private fun isExpired(cachedAt: Long, now: Long): Boolean {
        if (cachedAt <= 0L) return true
        // 时钟回拨（如用户手动改系统时间）时按过期处理，宁可重建也不返回陈旧数据
        if (now < cachedAt) return true
        return now - cachedAt >= ttlMs
    }

    companion object {
        /** 任务书 §3.6 规定的 5 分钟 TTL */
        const val DEFAULT_TTL_MS = 5 * 60 * 1000L
    }
}
