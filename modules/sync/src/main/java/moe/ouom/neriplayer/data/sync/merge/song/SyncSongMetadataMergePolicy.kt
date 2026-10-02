package moe.ouom.neriplayer.data.sync.merge.song

import moe.ouom.neriplayer.data.model.sync.CURRENT_SYNC_METADATA_VERSION
import moe.ouom.neriplayer.data.model.sync.SyncSong

internal object SyncSongMetadataMergePolicy {
    fun selectDeterministicPayload(candidates: List<SyncSong>): SyncSong {
        return candidates.maxWith(
            compareBy<SyncSong> { it.syncMetadataVersion }
                .thenBy(::canonicalPayloadKey)
        )
    }

    fun resolveSelectedPayload(
        selected: SyncSong,
        candidates: List<SyncSong>
    ): SyncSong {
        if (selected.syncMetadataVersion >= CURRENT_SYNC_METADATA_VERSION) {
            return resolveLyricState(selected, candidates)
        }

        val currentPayload = candidates
            .asSequence()
            .filter { it.syncMetadataVersion >= CURRENT_SYNC_METADATA_VERSION }
            .maxByOrNull(::canonicalPayloadKey)
        if (currentPayload != null) {
            return resolveLyricState(currentPayload, candidates)
        }

        return resolveLyricState(fillLegacySyncSongMetadata(selected, candidates), candidates)
    }

    private fun resolveLyricState(selected: SyncSong, candidates: List<SyncSong>): SyncSong {
        // 旧元数据补全仍返回旧结构，协议迁移集中在快照输出与独立歌词记录阶段
        if (selected.lyricSyncEdited == null && candidates.all { it.lyricSyncEdited == null }) return selected
        return SyncSongLyricMergePolicy.merge(selected, candidates)
    }

    fun resolveAddedAt(selectedAddedAt: Long, candidates: List<SyncSong>): Long {
        if (selectedAddedAt > 0L) return selectedAddedAt
        return candidates.maxOfOrNull(SyncSong::addedAt)?.coerceAtLeast(0L) ?: 0L
    }

    fun canonicalPayloadKey(song: SyncSong): String = syncSongPayloadKey(song)
}
