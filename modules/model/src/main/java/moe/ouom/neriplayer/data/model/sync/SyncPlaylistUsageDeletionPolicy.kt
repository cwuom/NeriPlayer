package moe.ouom.neriplayer.data.model.sync

import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import java.util.TreeMap

object SyncPlaylistUsageDeletionPolicy {
    fun merge(deletions: Iterable<SyncPlaylistUsageDeletion>): List<SyncPlaylistUsageDeletion> {
        val byKey = TreeMap<String, SyncPlaylistUsageDeletion>()
        deletions.forEach { deletion ->
            val key = deletion.playlistKey.trim()
            if (key.isEmpty()) return@forEach
            val tokens = deletion.deletionTokens.normalizedSyncCausalTokens().ifEmpty {
                if (deletion.deletedAt <= 0L) return@forEach
                listOf(legacyToken(key, deletion.deletedAt))
            }
            val previous = byKey[key]
            byKey[key] = SyncPlaylistUsageDeletion(
                playlistKey = key,
                deletionTokens = (previous?.deletionTokens.orEmpty() + tokens).normalizedSyncCausalTokens(),
                deletedAt = maxOf(previous?.deletedAt ?: 0L, deletion.deletedAt.coerceAtLeast(0L))
            )
        }
        return byKey.values.toList()
    }

    fun observes(observed: Iterable<SyncCausalToken>?, required: Collection<SyncCausalToken>): Boolean {
        if (required.isEmpty()) return true
        return observed.normalizedSyncCausalTokens().toHashSet().containsAll(required)
    }

    fun fromLegacy(deletions: Map<String, Long>): List<SyncPlaylistUsageDeletion> = merge(
        deletions.map { (key, timestamp) ->
            SyncPlaylistUsageDeletion(key, listOf(legacyToken(key.trim(), timestamp)), timestamp)
        }
    )

    private fun legacyToken(key: String, timestamp: Long): SyncCausalToken {
        // 固定宽度 UTF-16 编码保留完整 key，旧时间戳重读时不会产生新的删除事件
        val encodedKey = key.map { it.code.toString(16).padStart(4, '0') }.joinToString("")
        return SyncCausalToken("usage-legacy:$encodedKey", timestamp.coerceAtLeast(1L))
    }
}
