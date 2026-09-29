package moe.ouom.neriplayer.data.platform.bili.cache.archive

import moe.ouom.neriplayer.data.platform.bili.cache.archive.model.BiliArchiveContentCache

interface BiliArchiveCacheStore {
    suspend fun read(mediaId: Long, kind: String): BiliArchiveContentCache?
    suspend fun replace(cache: BiliArchiveContentCache)
    suspend fun replaceIfNewer(cache: BiliArchiveContentCache)
    suspend fun clear(mediaId: Long, kind: String)
}
