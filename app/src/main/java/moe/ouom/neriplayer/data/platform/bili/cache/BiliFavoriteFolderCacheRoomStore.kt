package moe.ouom.neriplayer.data.platform.bili.cache

import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheRecord
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheRoomStore
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheTrackRecord
import moe.ouom.neriplayer.data.platform.bili.cache.favorite.model.BiliFavoriteFolderContentCache
import moe.ouom.neriplayer.data.platform.bili.cache.favorite.BiliFavoriteFolderCacheStore
import moe.ouom.neriplayer.data.platform.bili.cache.favorite.model.CachedBiliFavoriteVideo

internal class BiliFavoriteFolderCacheRoomStore(
    database: NeriUserDataDatabase
) : BiliFavoriteFolderCacheStore {
    private val roomStore = PlatformPlaylistCacheRoomStore(database)

    override suspend fun read(mediaId: Long): BiliFavoriteFolderContentCache? {
        return roomStore.read(PLATFORM, mediaId.toString())?.toBiliFavoriteCache()
    }

    override suspend fun replace(cache: BiliFavoriteFolderContentCache) {
        roomStore.replace(cache.toRecord())
    }

    override suspend fun replaceIfNewer(cache: BiliFavoriteFolderContentCache) {
        roomStore.replaceIfNewer(cache.toRecord())
    }

    override suspend fun clear(mediaId: Long) {
        roomStore.clear(PLATFORM, mediaId.toString())
    }

    private fun BiliFavoriteFolderContentCache.toRecord(): PlatformPlaylistCacheRecord {
        return PlatformPlaylistCacheRecord(
            platform = PLATFORM,
            cacheKey = mediaId.toString(),
            sourceId = mediaId,
            trackCount = videos.size,
            totalCount = totalCount,
            signaturePrimary = latestPageSignature,
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

    private fun PlatformPlaylistCacheRecord.toBiliFavoriteCache(): BiliFavoriteFolderContentCache {
        return BiliFavoriteFolderContentCache(
            mediaId = sourceId ?: cacheKey.toLong(),
            latestPageSignature = signaturePrimary.orEmpty(),
            totalCount = totalCount,
            videos = tracks.map { track ->
                CachedBiliFavoriteVideo(
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
        const val PLATFORM = "bili_favorite"
        const val MILLIS_PER_SECOND = 1_000L
    }
}
