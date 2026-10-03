package moe.ouom.neriplayer.data.local.database.store.stats

import android.content.Context
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.sync.github.SyncPlaybackStatProjection

internal interface PlaybackStatsExportCapture {
    val state: PlaybackStatsRoomState
    suspend fun export(context: Context, tracks: suspend (List<SyncTrackStat>) -> Unit,
        buckets: suspend (List<SyncPlaybackStatBucket>) -> Unit, projection: SyncPlaybackStatProjection? = null)
    suspend fun release()
}

internal object PlaybackStatsRoomExportCapture {
    suspend fun open(store: PlaybackStatsRoomStore): PlaybackStatsExportCapture {
        PlaybackStatsWalReadCapture.openIfSupported(store)?.let { return it }
        val frozen = store.freezeSnapshot()
        return object : PlaybackStatsExportCapture {
            override val state = PlaybackStatsRoomState(frozen.revision, frozen.clearedAt, frozen.counterEpochStartedAt)
            override suspend fun export(context: Context, tracks: suspend (List<SyncTrackStat>) -> Unit,
                buckets: suspend (List<SyncPlaybackStatBucket>) -> Unit, projection: SyncPlaybackStatProjection?) {
                PlaybackStatsRoomSnapshotAccess(store).export(frozen.id, context, tracks, buckets, projection)
            }
            override suspend fun release() { store.releaseSnapshot(frozen.id) }
        }
    }
}
