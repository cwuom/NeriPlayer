package moe.ouom.neriplayer.core.player.queue.heartbeat

import moe.ouom.neriplayer.data.model.SongItem

/**
 * 心动模式的本地降级候选源。
 *
 * 层级优先级严格遵循任务书 §3.3：
 * ```
 * 0 官方接口（由 NeteasePlaymodeIntelligenceSource 负责，不在本类内）
 * 1 收藏
 * 2 历史
 * 3 同歌手 / 同专辑
 * 4 随机兜底
 * ```
 *
 * 本类只做「候选池构建 + 评分挑选」，不发起任何网络请求，
 * 数据来源通过 [HeartbeatCandidateProvider] 注入，避免 `playback/logic` 反向依赖 `local`。
 *
 * **合规约束（任务书 §1.3）**：本类的产出是「本地推荐」，
 * 上层 UI 文案不得表述为官方推荐算法。
 */
class LocalHeartbeatCandidateSource(
    private val provider: HeartbeatCandidateProvider = EmptyHeartbeatCandidateProvider,
    private val cache: HeartbeatCandidateCache = HeartbeatCandidateCache(),
) : HeartbeatNextTrackSelector {

    override suspend fun selectNext(currentSong: SongItem, currentQueue: List<SongItem>): SongItem? {
        val cached = cache.getOrBuild { buildPool() }
        val pool = withSkips(withDisliked(withSupplementalTiers(cached, currentSong)))
        if (pool.isEmpty) return null
        return HeartbeatScorer.pickBest(
            pool = pool,
            currentSong = currentSong,
            currentQueue = currentQueue,
            excludedIdentities = excludedIdentities,
        )
    }

    /**
     * 把用户主动跳过的次数打到候选上（任务书 §七 第三阶段）。
     *
     * 与 [withDisliked] 一样不放进缓存：候选池有 5 分钟 TTL，
     * 而「刚刚跳过这首」必须在下一次选歌就生效，否则用户会连着两次被推同一首。
     */
    private suspend fun withSkips(pool: HeartbeatCandidatePool): HeartbeatCandidatePool {
        if (pool.isEmpty) return pool
        val skips = runCatching { provider.skipCounts() }.getOrDefault(emptyMap())
        if (skips.isEmpty()) return pool
        val tiers = LinkedHashMap<Int, List<HeartbeatCandidate>>(pool.tiers.size)
        for ((tier, candidates) in pool.tiers) {
            tiers[tier] = candidates.map { candidate ->
                val count = skips[HeartbeatScorer.identityKey(candidate.song)] ?: 0
                if (count <= 0 || count == candidate.skipCount) candidate else candidate.copy(skipCount = count)
            }
        }
        return pool.copy(tiers = tiers)
    }

    /**
     * 把用户标记的「不喜欢」名单打到候选上。
     *
     * 刻意**不放进** [buildPool] 的缓存里：候选池有 5 分钟 TTL，
     * 而「标记不喜欢」是用户刚点完就要立刻生效的操作。
     * 每次选歌读一次名单（DataStore 的小文件），比等缓存过期或手动失效更可靠。
     *
     * 读取失败时按「没有标记」处理：名单读不出来不应该让心动模式失效。
     */
    private suspend fun withDisliked(pool: HeartbeatCandidatePool): HeartbeatCandidatePool {
        if (pool.isEmpty) return pool
        val disliked = runCatching { provider.dislikedIdentities() }.getOrDefault(emptySet())
        if (disliked.isEmpty()) return pool
        val tiers = LinkedHashMap<Int, List<HeartbeatCandidate>>(pool.tiers.size)
        for ((tier, candidates) in pool.tiers) {
            tiers[tier] = candidates.map { candidate ->
                if (candidate.notLiked) {
                    candidate
                } else if (HeartbeatScorer.identityKey(candidate.song) in disliked) {
                    candidate.copy(notLiked = true)
                } else {
                    candidate
                }
            }
        }
        return pool.copy(tiers = tiers)
    }

    /**
     * 本次会话内播放失败过的曲目身份键。
     *
     * 任务书 §八 要求「播放失败的歌曲应被排除」。这里只做会话级内存记录，
     * 不持久化：进程重启后重新开启心动模式即视为新会话，符合 §3.5 的语义。
     */
    private val failedIdentities = LinkedHashSet<String>()

    /** 由播放失败路径调用，把该曲加入本次会话的黑名单。 */
    fun markFailed(song: SongItem) {
        synchronized(failedIdentities) {
            failedIdentities.add(HeartbeatScorer.identityKey(song))
        }
    }

    /** 重新开启心动模式时清空失败记录。 */
    fun resetFailures() {
        synchronized(failedIdentities) { failedIdentities.clear() }
    }

    private val excludedIdentities: Set<String>
        get() = synchronized(failedIdentities) { failedIdentities.toSet() }

    /** 候选池因收藏/历史变化而失效时调用 */
    fun invalidate() = cache.invalidate()

    /**
     * 把缓存的层级 1/2 原料，按**当前歌曲**派生层级 3（同歌手/同专辑）与层级 4（随机兜底）。
     *
     * 为什么不在 [buildPool] 里做：候选池按 5 分钟 TTL 缓存，与当前歌曲无关；
     * 而层级 3/4 的定义依赖当前歌曲。放进缓存会导致「切歌后仍按上一首的歌手推荐」。
     */
    private fun withSupplementalTiers(
        cached: HeartbeatCandidatePool,
        currentSong: SongItem,
    ): HeartbeatCandidatePool {
        if (cached.supplemental.isEmpty()) return cached

        val knownKeys = HashSet<String>()
        cached.tiers.values.forEach { list -> list.forEach { knownKeys.add(HeartbeatScorer.identityKey(it.song)) } }

        val sameArtistOrAlbum = ArrayList<HeartbeatCandidate>()
        val randomPool = ArrayList<HeartbeatCandidate>()
        for (song in cached.supplemental) {
            val key = HeartbeatScorer.identityKey(song)
            if (key in knownKeys) continue
            if (HeartbeatScorer.sameArtistOrAlbum(song, currentSong)) {
                sameArtistOrAlbum.add(
                    HeartbeatCandidate(
                        song = song,
                        tier = HeartbeatCandidateTier.SAME_ARTIST_OR_ALBUM,
                        weight = 1.0,
                        recentPlayCount = playCounts[key] ?: 0,
                    )
                )
            } else {
                randomPool.add(
                    HeartbeatCandidate(
                        song = song,
                        tier = HeartbeatCandidateTier.RANDOM,
                        weight = 1.0,
                        recentPlayCount = playCounts[key] ?: 0,
                    )
                )
            }
        }

        val tiers = LinkedHashMap(cached.tiers)
        if (sameArtistOrAlbum.isNotEmpty()) {
            tiers[HeartbeatCandidateTier.SAME_ARTIST_OR_ALBUM] = sameArtistOrAlbum
        }
        if (randomPool.isNotEmpty()) {
            tiers[HeartbeatCandidateTier.RANDOM] = randomPool
        }
        return HeartbeatCandidatePool(tiers = tiers, supplemental = cached.supplemental)
    }

    /** 构建候选池时一并抓取的播放次数快照，供 [withSupplementalTiers] 复用。 */
    @Volatile
    private var playCounts: Map<String, Int> = emptyMap()

    /**
     * 构建候选池。
     *
     * 任何单一来源失败都不影响其它来源——用 `runCatching` 兜住，
     * 因为「收藏读不出来」不应该让整个心动模式失效。
     */
    private suspend fun buildPool(): HeartbeatCandidatePool {
        val favorites = runCatching { provider.favorites() }.getOrDefault(emptyList())
        val history = runCatching {
            provider.recentHistory(HeartbeatCandidateProvider.DEFAULT_HISTORY_LIMIT)
        }.getOrDefault(emptyList())
        val samples = runCatching {
            provider.librarySamples(HeartbeatCandidateProvider.DEFAULT_LIBRARY_SAMPLE_LIMIT)
        }.getOrDefault(emptyList())
        val counts = runCatching {
            provider.recentPlayCounts(HeartbeatCandidateProvider.DEFAULT_HISTORY_LIMIT)
        }.getOrDefault(emptyMap())
        playCounts = counts

        val historyKeys = history.mapTo(HashSet()) { HeartbeatScorer.identityKey(it) }
        val tiers = LinkedHashMap<Int, List<HeartbeatCandidate>>()

        if (favorites.isNotEmpty()) {
            tiers[HeartbeatCandidateTier.FAVORITE] = favorites.map { song ->
                HeartbeatCandidate(
                    song = song,
                    tier = HeartbeatCandidateTier.FAVORITE,
                    // 收藏本身已是强信号，权重统一给 1.0，由评分器按层级拉开差距
                    weight = 1.0,
                    recentlyPlayed = HeartbeatScorer.identityKey(song) in historyKeys,
                    recentPlayCount = counts[HeartbeatScorer.identityKey(song)] ?: 0,
                )
            }
        }

        if (history.isNotEmpty()) {
            tiers[HeartbeatCandidateTier.HISTORY] = history.mapIndexed { index, song ->
                HeartbeatCandidate(
                    song = song,
                    tier = HeartbeatCandidateTier.HISTORY,
                    // 越靠前（越近）的历史权重越高，线性衰减到 0.5 为止
                    weight = (1.0 - index * HISTORY_WEIGHT_STEP).coerceAtLeast(HISTORY_MIN_WEIGHT),
                    recentlyPlayed = true,
                    recentPlayCount = counts[HeartbeatScorer.identityKey(song)] ?: 0,
                )
            }
        }

        // 层级 3/4 的原料在这里只缓存，真正分层在 withSupplementalTiers 里按当前歌曲派生
        return HeartbeatCandidatePool(tiers = tiers, supplemental = samples)
    }

    private companion object {
        /** 历史权重随位置线性衰减的步长 */
        const val HISTORY_WEIGHT_STEP = 0.01

        /** 历史权重下限，防止第 50 条历史权重过低 */
        const val HISTORY_MIN_WEIGHT = 0.5
    }
}
