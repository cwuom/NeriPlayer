package moe.ouom.neriplayer.data.heartbeat

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import moe.ouom.neriplayer.common.logging.NPLogger

private const val TAG = "DislikedSong"
private val DislikedKey = stringPreferencesKey("disliked_v1")
private val dislikedJson = Json { ignoreUnknownKeys = true }

private val Context.dislikedSongDataStore by preferencesDataStore(
    name = "disliked_songs",
    corruptionHandler = ReplaceFileCorruptionHandler {
        NPLogger.e(TAG, "disliked song DataStore is corrupted", it)
        emptyPreferences()
    }
)

/**
 * 心动模式「不喜欢」名单。
 *
 * 为什么是本地数据而不是官方数据：网易云没有「给某首歌打不喜欢」的通用接口。
 * 实际探测到的两个相近接口语义都不对——`/api/v2/discovery/recommend/dislike`
 * 是「每日推荐不感兴趣」、`/api/radio/trash/add` 是「私人FM垃圾桶」，
 * 都不能表达「心动模式不要推这首」。所以名单只能自己存。
 *
 * 为什么用 DataStore 而不是 Room：名单量级只有几十到几百条，没有 SQL 查询需求；
 * 而 Room 加表要把数据库版本从 20 升到 21、登记迁移、导出 schema、补两套迁移测试，
 * 一旦漏登会让老用户升级后打不开数据库（见 `NeriUserDataDatabase` 的注释警告）。
 * 用 DataStore 存一个 JSON 数组是零迁移风险的做法，项目里的搜索历史也是这么做的。
 *
 * 键的生成**不在这里**：本类只存不透明字符串，由调用方统一用
 * `HeartbeatScorer.identityKey(song)` 生成，保证「标记时」与「选歌时」算的是同一个键。
 * `modules/local` 不依赖 `playback/logic`（域依赖门禁），所以不能在这里引用评分器。
 */
class DislikedSongRepository(context: Context) {
    private val appContext = context.applicationContext

    /** 当前名单，供 UI 观察（用于把菜单项显示成「已标记」状态）。 */
    val dislikedKeysFlow: Flow<Set<String>> = appContext.dislikedSongDataStore.data
        .map { prefs -> decodeDislikedKeys(prefs[DislikedKey]) }

    /**
     * 一次性快照，供选歌链路同步取用。
     *
     * 用 `first()` 只取第一帧而不是 `collect`：DataStore 的 data 是热流，
     * 持续收集会一直挂着不返回。
     */
    suspend fun snapshot(): Set<String> =
        appContext.dislikedSongDataStore.data
            .map { prefs -> decodeDislikedKeys(prefs[DislikedKey]) }
            .first()

    /**
     * 切换一首歌的标记状态，返回切换后是否处于「不喜欢」。
     *
     * 做成 toggle 而不是分开的 add/remove：UI 只有一个菜单项，
     * 两次点击之间名单可能被别处改过，用一次性读-改-写更不容易出现状态打架。
     */
    suspend fun toggle(identityKey: String): Boolean {
        val key = identityKey.trim()
        if (key.isEmpty()) return false
        var disliked = false
        appContext.dislikedSongDataStore.edit { prefs ->
            val current = decodeDislikedKeys(prefs[DislikedKey])
            // 每次进入 lambda 都重新判定：DataStore 在并发写入时会重试这个块，
            // 标志位必须跟着这次实际走的分支重置，不能沿用上一次的结果。
            if (key in current) {
                disliked = false
                prefs[DislikedKey] = dislikedJson.encodeToString((current - key).toList())
            } else {
                disliked = true
                // 超上限时淘汰最旧的，而不是 `take(n)`：`current + key` 把新标记放在末尾，
                // `take(n)` 会先把刚点的这首丢掉，等于点了没反应。
                val merged = (current + key).toList()
                val trimmed = if (merged.size > DEFAULT_DISLIKED_LIMIT) {
                    merged.drop(merged.size - DEFAULT_DISLIKED_LIMIT)
                } else {
                    merged
                }
                prefs[DislikedKey] = dislikedJson.encodeToString(trimmed)
            }
        }
        return disliked
    }

    suspend fun clear() {
        appContext.dislikedSongDataStore.edit { prefs ->
            prefs.remove(DislikedKey)
        }
    }
}

internal const val DEFAULT_DISLIKED_LIMIT = 500

internal fun decodeDislikedKeys(raw: String?): Set<String> {
    if (raw.isNullOrBlank()) return emptySet()
    return runCatching {
        dislikedJson.decodeFromString<List<String>>(raw)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(DEFAULT_DISLIKED_LIMIT)
            .toSet()
    }.getOrElse { error ->
        NPLogger.w(TAG, "decode disliked keys failed: ${error.message}")
        emptySet()
    }
}
