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
            return selected
        }

        val currentPayload = candidates
            .asSequence()
            .filter { it.syncMetadataVersion >= CURRENT_SYNC_METADATA_VERSION }
            .maxByOrNull(::canonicalPayloadKey)
        if (currentPayload != null) {
            return currentPayload
        }

        return fillLegacySyncSongMetadata(selected, candidates)
    }

    fun resolveAddedAt(selectedAddedAt: Long, candidates: List<SyncSong>): Long {
        if (selectedAddedAt > 0L) return selectedAddedAt
        return candidates.maxOfOrNull(SyncSong::addedAt)?.coerceAtLeast(0L) ?: 0L
    }

    fun canonicalPayloadKey(song: SyncSong): String = syncSongPayloadKey(song)
}
