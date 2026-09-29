package moe.ouom.neriplayer.data.platform.bili.cache

import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheRecord
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheRoomStore
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheTrackRecord
import moe.ouom.neriplayer.data.platform.bili.cache.archive.model.BiliArchiveContentCache
import moe.ouom.neriplayer.data.platform.bili.cache.archive.BiliArchiveCacheStore
import moe.ouom.neriplayer.data.platform.bili.cache.archive.model.CachedBiliArchiveVideo
import java.util.Locale

internal class BiliArchiveCacheRoomStore(
    database: NeriUserDataDatabase
) : BiliArchiveCacheStore {
    private val roomStore = PlatformPlaylistCacheRoomStore(database)

    override suspend fun read(mediaId: Long, kind: String): BiliArchiveContentCache? {
        return roomStore.read(PLATFORM, cacheKey(mediaId, kind))?.toBiliArchiveCache()
    }

    override suspend fun replace(cache: BiliArchiveContentCache) {
        roomStore.replace(cache.toRecord())
    }

    override suspend fun replaceIfNewer(cache: BiliArchiveContentCache) {
        roomStore.replaceIfNewer(cache.toRecord())
    }

    override suspend fun clear(mediaId: Long, kind: String) {
        roomStore.clear(PLATFORM, cacheKey(mediaId, kind))
    }

    private fun cacheKey(mediaId: Long, kind: String): String {
        return "${kind.lowercase(Locale.ROOT)}:$mediaId"
    }

    private fun BiliArchiveContentCache.toRecord(): PlatformPlaylistCacheRecord {
        return PlatformPlaylistCacheRecord(
            platform = PLATFORM,
            cacheKey = cacheKey(mediaId, kind),
            sourceId = mediaId,
            kind = kind,
            trackCount = videos.size,
            totalCount = totalCount,
            hasMore = hasMore,
            savedAtMs = savedAtMs,
            tracks = videos.map { video ->
                PlatformPlaylistCacheTrackRecord(
                    itemId = video.id,
                    itemKey = video.bvid,
                    name = video.title,
                    artist = video.uploader,
                    durationMs = video.durationSec.toLong() * MILLIS_PER_SECOND,
                    coverUrl = video.coverUrl,
                    uploaderMid = video.uploaderMid
                )
            }
        )
    }

    private fun PlatformPlaylistCacheRecord.toBiliArchiveCache(): BiliArchiveContentCache {
        return BiliArchiveContentCache(
            mediaId = sourceId ?: cacheKey.substringAfter(':').toLong(),
            kind = kind.orEmpty(),
            totalCount = totalCount,
            hasMore = hasMore == true,
            videos = tracks.map { track ->
                CachedBiliArchiveVideo(
                    id = track.itemId ?: 0L,
                    bvid = track.itemKey.orEmpty(),
                    title = track.name,
                    uploader = track.artist,
                    uploaderMid = track.uploaderMid ?: 0L,
                    coverUrl = track.coverUrl.orEmpty(),
                    durationSec = (track.durationMs / MILLIS_PER_SECOND).toInt()
                )
            },
            savedAtMs = savedAtMs
        )
    }

    private companion object {
        const val PLATFORM = "bili_archive"
        const val MILLIS_PER_SECOND = 1_000L
    }
}
