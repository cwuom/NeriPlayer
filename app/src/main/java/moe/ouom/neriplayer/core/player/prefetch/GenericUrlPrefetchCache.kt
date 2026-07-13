package moe.ouom.neriplayer.core.player.prefetch

import java.util.concurrent.ConcurrentHashMap
import moe.ouom.neriplayer.core.player.model.SongUrlResult

internal const val GENERIC_URL_PREFETCH_TTL_MS = 90_000L
private const val GENERIC_URL_PREFETCH_MAX_ENTRIES = 16

internal class GenericUrlPrefetchCache(
    private val ttlMs: Long = GENERIC_URL_PREFETCH_TTL_MS,
    private val maxEntries: Int = GENERIC_URL_PREFETCH_MAX_ENTRIES
) {
    private data class Entry(
        val result: SongUrlResult.Success,
        val expiresAtMs: Long
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    init {
        require(ttlMs > 0L) { "ttlMs must be positive" }
        assert(ttlMs > 0L) { "ttlMs must be positive" }
        require(maxEntries > 0) { "maxEntries must be positive" }
        assert(maxEntries > 0) { "maxEntries must be positive" }
    }

    fun put(key: String, result: SongUrlResult.Success, nowMs: Long) {
        require(key.isNotBlank()) { "prefetch cache key must not be blank" }
        assert(key.isNotBlank()) { "prefetch cache key must not be blank" }
        entries.entries.removeIf { (_, entry) -> nowMs >= entry.expiresAtMs }
        if (!entries.containsKey(key) && entries.size >= maxEntries) {
            entries.entries.minByOrNull { it.value.expiresAtMs }?.let { oldest ->
                entries.remove(oldest.key, oldest.value)
            }
        }
        entries[key] = Entry(result, expiresAtMs = nowMs + ttlMs)
    }

    fun containsFresh(key: String, nowMs: Long): Boolean {
        val entry = entries[key] ?: return false
        if (nowMs < entry.expiresAtMs) return true
        entries.remove(key, entry)
        return false
    }

    fun consume(key: String, nowMs: Long): SongUrlResult.Success? {
        val entry = entries.remove(key) ?: return null
        return entry.result.takeIf { nowMs < entry.expiresAtMs }
    }

    fun clear() {
        entries.clear()
    }
}
