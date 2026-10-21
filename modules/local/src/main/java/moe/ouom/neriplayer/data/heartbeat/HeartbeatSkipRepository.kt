package moe.ouom.neriplayer.data.heartbeat

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import moe.ouom.neriplayer.common.logging.NPLogger

private const val TAG = "HeartbeatSkip"
private val SkippedKey = stringPreferencesKey("skipped_v1")
private val skipJson = Json { ignoreUnknownKeys = true }

private val Context.heartbeatSkipDataStore by preferencesDataStore(
    name = "heartbeat_skip",
    corruptionHandler = ReplaceFileCorruptionHandler {
        NPLogger.e(TAG, "heartbeat skip DataStore is corrupted", it)
        emptyPreferences()
    }
)

/**
 * 心动模式的「用户主动跳过」计数。
 *
 * 任务书 §七 第三阶段要求「记录用户跳过行为」，验收标准是
 * 「跳过不喜欢的歌曲后降低其权重」。跳过与「不喜欢」是两种强度的信号：
 * 不喜欢是用户**明确声明**不要这首（见 [DislikedSongRepository]），
 * 跳过只是**这一次**不想听——可能只是现在不想听慢歌，并不代表讨厌。
 * 因此这里只累计次数、参与重复惩罚衰减，不做硬性排除。
 *
 * 为什么单独一个 DataStore 而不是塞进不喜欢名单那个：两者生命周期与语义都不同，
 * 不喜欢名单会被 UI 频繁读来做菜单状态，跳过计数只被选歌链路读取。
 * 分开存可以避免每次切歌都去解析一份无关的名单。
 *
 * 为什么用 DataStore 不用 Room：与 [DislikedSongRepository] 同理——
 * 数据量级只有几百条、无 SQL 需求，而 Room 加表要升版本、登记迁移、
 * 导出 schema、补两套迁移测试，漏登会让老用户升级后打不开数据库。
 *
 * 键由调用方用 `HeartbeatScorer.identityKey(song)` 生成（`modules/local`
 * 不依赖 `playback/logic`，受域依赖门禁约束，不能在这里引用评分器）。
 */
class HeartbeatSkipRepository(context: Context) {
    private val appContext = context.applicationContext

    /**
     * 一次性读取跳过次数快照。
     *
     * 与不喜欢名单一样用 `first()`：DataStore 的 data 是热流，
     * 持续 `collect` 会一直挂着不返回。
     */
    suspend fun snapshot(): Map<String, Int> =
        appContext.heartbeatSkipDataStore.data
            .map { prefs -> decodeSkipCounts(prefs[SkippedKey]) }
            .first()

    /**
     * 给某首歌的跳过次数 +1。
     *
     * 到上限后不再累加而不是淘汰旧条目：跳过次数是「这首歌到底多不受待见」的
     * 累计证据，淘汰最旧条目会让长期积累的信号凭空消失；而计数封顶后
     * 对评分的影响已经稳定（重复惩罚有下限），继续加也没有额外意义。
     */
    suspend fun recordSkip(identityKey: String) {
        val key = identityKey.trim()
        if (key.isEmpty()) return
        appContext.heartbeatSkipDataStore.edit { prefs ->
            val current = decodeSkipCounts(prefs[SkippedKey])
            val next = current[key] ?: 0
            if (next >= MAX_SKIP_COUNT) return@edit
            val merged = current + (key to (next + 1))
            prefs[SkippedKey] = skipJson.encodeToString(trimToLimit(merged))
        }
    }

    suspend fun clear() {
        appContext.heartbeatSkipDataStore.edit { prefs ->
            prefs.remove(SkippedKey)
        }
    }
}

/**
 * 超出条目上限时丢掉**次数最少**的条目。
 *
 * 不按插入顺序淘汰：被跳过次数多的曲目信息价值更高，
 * 淘汰它们等于把最有用的负反馈先扔掉。
 */
internal fun trimToLimit(counts: Map<String, Int>): Map<String, Int> {
    if (counts.size <= DEFAULT_SKIP_ENTRY_LIMIT) return counts
    return counts.entries
        .sortedByDescending { it.value }
        .take(DEFAULT_SKIP_ENTRY_LIMIT)
        .associate { it.key to it.value }
}

/** 单曲跳过次数上限，超过后不再累加 */
internal const val MAX_SKIP_COUNT = 20

/** 最多记录的曲目条数，避免长期使用后无限膨胀 */
internal const val DEFAULT_SKIP_ENTRY_LIMIT = 500

internal fun decodeSkipCounts(raw: String?): Map<String, Int> {
    if (raw.isNullOrBlank()) return emptyMap()
    return runCatching {
        skipJson.decodeFromString<Map<String, Int>>(raw)
            .filterKeys { it.isNotBlank() }
            .filterValues { it > 0 }
    }.getOrElse { error ->
        NPLogger.w(TAG, "decode skip counts failed: ${error.message}")
        emptyMap()
    }
}
