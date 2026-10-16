package moe.ouom.neriplayer.platform.netease.encyclopedia

import moe.ouom.neriplayer.data.model.encyclopedia.SongEncyclopedia

private const val DEFAULT_TTL_MS = 10 * 60 * 1000L
private const val MAX_ENTRIES = 32

/**
 * 歌曲百科的内存缓存。
 *
 * 进程内共享，按歌曲稳定标识隔离，使用十分钟 TTL 与 LRU 上限，
 * 避免在歌曲信息页反复进出时重复请求。
 */
class SongEncyclopediaMemoryCache(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxEntries: Int = MAX_ENTRIES
) {
    private data class Entry(val data: SongEncyclopedia, val savedAtMs: Long)

    private val lock = Any()

    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean {
            return size > maxEntries
        }
    }

    fun get(songKey: String): SongEncyclopedia? {
        val now = nowMs()
        synchronized(lock) {
            val entry = entries[songKey] ?: return null
            if (now - entry.savedAtMs > ttlMs) {
                entries.remove(songKey)
                return null
            }
            return entry.data
        }
    }

    fun put(data: SongEncyclopedia) {
        val now = nowMs()
        synchronized(lock) {
            entries[data.songKey] = Entry(data, now)
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
        }
    }
}
