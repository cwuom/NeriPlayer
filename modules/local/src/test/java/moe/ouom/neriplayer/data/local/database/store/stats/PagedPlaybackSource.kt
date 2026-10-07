package moe.ouom.neriplayer.data.local.database.store.stats

import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackBucketOrder
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackCursor
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackSource

/** Playback dataset served from fixed pages; every cursor reports its name once it is closed. */
internal class PagedPlaybackSource(
    private val trackPages: List<List<SyncTrackStat>>,
    private val bucketPages: Map<SyncPlaybackBucketOrder, List<List<SyncPlaybackStatBucket>>> = emptyMap()
) : SyncPlaybackSource {
    val closedCursors = mutableListOf<String>()

    override fun openTracks(): SyncPlaybackCursor<SyncTrackStat> = PagedCursor("tracks", trackPages)

    override fun openBuckets(order: SyncPlaybackBucketOrder): SyncPlaybackCursor<SyncPlaybackStatBucket> =
        PagedCursor("buckets:${order.name}", bucketPages[order].orEmpty())

    override fun close() = Unit

    private inner class PagedCursor<T>(private val name: String, pages: List<List<T>>) : SyncPlaybackCursor<T> {
        private val remaining = ArrayDeque(pages)

        override suspend fun nextPage(): List<T> = remaining.removeFirstOrNull().orEmpty()

        override fun close() {
            closedCursors += name
        }
    }

    companion object {
        /** Serves [tracks] and [buckets] in the key orders a merged dataset guarantees. */
        fun ordered(tracks: List<SyncTrackStat>, buckets: List<SyncPlaybackStatBucket>, pageSize: Int): PagedPlaybackSource =
            PagedPlaybackSource(
                tracks.sortedBy { it.identityKey }.chunked(pageSize),
                mapOf(
                    SyncPlaybackBucketOrder.DAY_IDENTITY to
                        buckets.sortedWith(compareBy({ it.dayStartAt }, { it.identityKey })).chunked(pageSize),
                    SyncPlaybackBucketOrder.IDENTITY_DAY to
                        buckets.sortedWith(compareBy({ it.identityKey }, { it.dayStartAt })).chunked(pageSize)
                )
            )
    }
}
