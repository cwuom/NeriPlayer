package moe.ouom.neriplayer.platform.bilibili.binding

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import moe.ouom.neriplayer.data.local.database.dao.BiliCollectionBindingDao
import moe.ouom.neriplayer.data.local.database.entity.BiliCollectionBindingEntity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.bilibili.binding.BILI_BINDING_TARGET_PLAYLIST
import moe.ouom.neriplayer.data.model.bilibili.binding.BiliCollectionBinding
import moe.ouom.neriplayer.data.model.bilibili.binding.BiliCollectionBindingTrack
import moe.ouom.neriplayer.data.model.bilibili.binding.bindingKeysForSong

/** 一次绑定命中的结果：[binding] 是整条绑定，[track] 是这首歌对应的分 P */
data class BiliCollectionBindingHit(
    val binding: BiliCollectionBinding,
    val track: BiliCollectionBindingTrack
)

/**
 * 单曲 / 专辑 / 歌单 → B 站分 P 绑定的本地仓储。
 *
 * 播放热路径需要按目标键立刻拿到分 P，因此全部记录常驻内存；写操作同步更新缓存，
 * 同步（GitHub / WebDAV）落地后调用 [replaceAll] 刷新。
 */
class BiliCollectionBindingRepository(
    private val dao: BiliCollectionBindingDao,
    private val readSyncMutationVersion: () -> Long = { 0L },
    private val markMutation: () -> Unit = {},
    private val onChanged: () -> Unit = {}
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val mutex = Mutex()

    @Volatile
    private var cached: Map<String, BiliCollectionBinding>? = null

    private val _revision = MutableStateFlow(0L)

    /** 每次本地写入（保存 / 删除 / 同步落地）自增，UI 用它刷新依赖绑定的展示 */
    val revisionFlow: StateFlow<Long> = _revision.asStateFlow()

    /** 供 UI 展示的绑定列表；解绑墓碑只参与同步，不出现在这里 */
    suspend fun all(): List<BiliCollectionBinding> = snapshot().filterNot { it.isDeleted }

    suspend fun get(targetKey: String): BiliCollectionBinding? {
        val key = targetKey.trim()
        if (key.isEmpty()) return null
        return cachedOrLoad()[key]?.takeUnless { it.isDeleted }
    }

    /**
     * 按优先级查找这首歌的绑定：单曲绑定 → 当前播放来源歌单 → 所属专辑。
     *
     * [playlistBindingKey] 由播放侧在「从歌单页开始播放」时给出，用于区分同名歌曲在不同歌单里的不同绑定。
     */
    suspend fun resolve(
        song: SongItem,
        playlistBindingKey: String? = null
    ): BiliCollectionBindingHit? {
        return hitIn(cachedOrLoad(), song, playlistBindingKey)
    }

    /**
     * 同步查询缓存里这首歌的绑定，供 Compose 即时要来源角标。
     * 缓存没有加载过时返回 null；播放在跑时缓存必然是热的。
     */
    fun cachedHit(song: SongItem): BiliCollectionBindingHit? {
        val bindings = cached ?: return null
        return hitIn(bindings, song, playlistBindingKey = null)
    }

    private fun hitIn(
        bindings: Map<String, BiliCollectionBinding>,
        song: SongItem,
        playlistBindingKey: String?
    ): BiliCollectionBindingHit? {
        for (key in bindingKeysForSong(song, playlistBindingKey)) {
            val binding = bindings[key] ?: continue
            val track = binding.trackFor(song) ?: continue
            return BiliCollectionBindingHit(binding, track)
        }
        // 播放队列不携带歌单上下文：退一步扫描所有歌单绑定，命中包含这首歌的那一条。
        // 同一首歌被多个歌单绑定时取最近保存的那条，行为可预期。
        if (playlistBindingKey.isNullOrBlank()) {
            return bindings.values
                .asSequence()
                .filter { it.targetType == BILI_BINDING_TARGET_PLAYLIST }
                .sortedByDescending { it.updatedAt }
                .firstNotNullOfOrNull { binding ->
                    val track = binding.trackFor(song) ?: return@firstNotNullOfOrNull null
                    BiliCollectionBindingHit(binding, track)
                }
        }
        return null
    }

    suspend fun forSong(song: SongItem): BiliCollectionBindingHit? = resolve(song)

    suspend fun save(binding: BiliCollectionBinding): Boolean {
        val normalized = binding.normalized()
        if (!normalized.isValid()) return false
        mutex.withLock {
            // 缓存可能是冷的，先落一次全量，避免只把新记录当成全部
            val current = cached ?: loadFromDatabase().also { cached = it }
            dao.upsert(normalized.toEntity(json))
            cached = current + (normalized.targetKey to normalized)
            // 版本标记必须留在锁内：否则等锁的 replaceAll 会读到旧版本并覆盖刚写下的这行
            markMutation()
        }
        onChanged()
        bumpRevision()
        return true
    }

    suspend fun remove(targetKey: String) {
        val key = targetKey.trim()
        if (key.isEmpty()) return
        mutex.withLock {
            val current = cached ?: loadFromDatabase().also { cached = it }
            // 写墓碑而不是硬删：否则对面设备上的旧记录会在下次同步把绑定复活
            val existing = current[key]
            val tombstone = (existing ?: BiliCollectionBinding(targetKey = key)).copy(
                isDeleted = true,
                // 远端时钟可能领先本机，墓碑必须比被删的那条更新，否则合并时会被旧记录压回来
                updatedAt = maxOf(System.currentTimeMillis(), (existing?.updatedAt ?: 0L) + 1L)
            ).normalized()
            dao.upsert(tombstone.toEntity(json))
            cached = current + (key to tombstone)
            markMutation()
        }
        onChanged()
        bumpRevision()
    }

    /** 同步快照：包含解绑墓碑，删除才能跨设备传播 */
    suspend fun snapshot(): List<BiliCollectionBinding> {
        return cachedOrLoad().values.sortedByDescending { it.updatedAt }
    }

    /**
     * 同步落地：整表替换，空列表表示远端确实没有绑定；远端带来的墓碑一并落库，供后续继续压制复活。
     *
     * [expectedMutationVersion] 是快照发出时的本地写入版本，期间若本地又改过绑定则放弃本次落地并返回 false，
     * 避免远端旧快照把用户刚做的改动覆盖掉。
     */
    suspend fun replaceAll(
        bindings: List<BiliCollectionBinding>,
        expectedMutationVersion: Long
    ): Boolean {
        val valid = bindings.map { it.normalized() }
            .filter { it.isValid() }
            .distinctBy { it.targetKey }
        val applied = mutex.withLock {
            if (readSyncMutationVersion() != expectedMutationVersion) return@withLock false
            dao.replaceAll(valid.map { it.toEntity(json) })
            cached = valid.associateBy { it.targetKey }
            true
        }
        if (applied) bumpRevision()
        return applied
    }

    private fun bumpRevision() {
        _revision.value += 1L
    }

    private suspend fun cachedOrLoad(): Map<String, BiliCollectionBinding> {
        cached?.let { return it }
        return mutex.withLock {
            cached ?: loadFromDatabase().also { cached = it }
        }
    }

    private suspend fun loadFromDatabase(): Map<String, BiliCollectionBinding> {
        return dao.getAll()
            .mapNotNull { entity -> entity.toModel(json) }
            .filter { it.isValid() }
            .associateBy { it.targetKey }
    }
}

private fun BiliCollectionBinding.toEntity(json: Json): BiliCollectionBindingEntity {
    return BiliCollectionBindingEntity(
        targetKey = targetKey,
        targetType = targetType,
        targetId = targetId,
        targetName = targetName,
        targetSubtitle = targetSubtitle,
        bvid = bvid,
        avid = avid,
        videoTitle = videoTitle,
        tracksJson = json.encodeToString(tracks),
        updatedAt = updatedAt
    )
}

private fun BiliCollectionBindingEntity.toModel(json: Json): BiliCollectionBinding? {
    val tracks = runCatching {
        json.decodeFromString<List<BiliCollectionBindingTrack>>(tracksJson)
    }.getOrNull() ?: return null
    return BiliCollectionBinding(
        targetKey = targetKey,
        targetType = targetType,
        targetId = targetId,
        targetName = targetName,
        targetSubtitle = targetSubtitle,
        bvid = bvid,
        avid = avid,
        videoTitle = videoTitle,
        tracks = tracks,
        updatedAt = updatedAt,
        // 有效绑定必然带 bvid，空 bvid 的行只可能是解绑墓碑
        isDeleted = bvid.isBlank()
    )
}
