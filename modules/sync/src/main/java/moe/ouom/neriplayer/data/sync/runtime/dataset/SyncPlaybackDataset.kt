package moe.ouom.neriplayer.data.sync.runtime.dataset

import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncResult
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import java.io.Closeable

const val SYNC_PLAYBACK_PAGE_RECORDS = 256
internal const val SYNC_PLAYBACK_PAGE_BYTES = 1024 * 1024

enum class SyncPlaybackBucketOrder { DAY_IDENTITY, IDENTITY_DAY }

interface SyncPlaybackCursor<T> : Closeable {
    // 空页表示已经完整读取并校验文件尾部
    suspend fun nextPage(): List<T>
}

interface SyncPlaybackSource : Closeable {
    fun openTracks(): SyncPlaybackCursor<SyncTrackStat>
    fun openBuckets(order: SyncPlaybackBucketOrder = SyncPlaybackBucketOrder.DAY_IDENTITY): SyncPlaybackCursor<SyncPlaybackStatBucket>
}

interface SyncPlaybackSink : Closeable {
    suspend fun appendTracks(page: List<SyncTrackStat>)
    suspend fun appendBuckets(page: List<SyncPlaybackStatBucket>)
    suspend fun seal(): SyncPlaybackSource
}

class SyncDataset(
    val data: SyncData,
    val playback: SyncPlaybackSource,
    val capturedPlaybackRevision: Long? = null,
    val playbackMatchesCaptured: Boolean = false
) : Closeable {
    init {
        require(data.playbackStats.isEmpty()) { "Playback tracks must use the disk dataset" }
        require(data.playbackStatBuckets.isEmpty()) { "Playback buckets must use the disk dataset" }
    }

    override fun close() = playback.close()
}

data class SyncDatasetRemoteSnapshot<TVersion>(
    val dataset: SyncDataset?,
    val version: TVersion,
    val requiresMigrationUpload: Boolean = false
)

data class SyncDatasetMergeResult(val dataset: SyncDataset, val syncResult: SyncResult)

interface SyncPlaybackDatasetStore {
    fun newSink(): SyncPlaybackSink

    // 已知严格有序的数据可避免再次外排，通用实现保留原有写入方式
    fun newOrderedSink(): SyncPlaybackSink = newSink()

    suspend fun fromLegacy(data: SyncData): SyncDataset = newSink().use { sink ->
        for (page in data.playbackStats.asSequence().chunked(SYNC_PLAYBACK_PAGE_RECORDS)) sink.appendTracks(page)
        for (page in data.playbackStatBuckets.asSequence().chunked(SYNC_PLAYBACK_PAGE_RECORDS)) sink.appendBuckets(page)
        SyncDataset(data.copy(playbackStats = emptyList(), playbackStatBuckets = emptyList()), sink.seal())
    }
}
