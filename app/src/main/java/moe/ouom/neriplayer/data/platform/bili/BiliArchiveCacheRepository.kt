package moe.ouom.neriplayer.data.platform.bili

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.data.platform.bili/BiliArchiveCacheRepository
 * Created: 2026/8/4
 */

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheRecord
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheRoomStore
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheStore
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheTrackRecord

data class CachedBiliArchiveVideo(
    val id: Long,
    val bvid: String,
    val title: String,
    val uploader: String,
    val uploaderMid: Long,
    val coverUrl: String,
    val durationSec: Int
)

data class BiliArchiveContentCache(
    val mediaId: Long,
    val kind: String,
    val totalCount: Int,
    val hasMore: Boolean,
    val videos: List<CachedBiliArchiveVideo>,
    val savedAtMs: Long = System.currentTimeMillis()
)

internal fun biliArchiveCacheFileName(mediaId: Long, kind: String): String {
    return "${kind.lowercase(Locale.ROOT)}_$mediaId.json"
}

class BiliArchiveCacheRepository internal constructor(
    private val roomStore: PlatformPlaylistCacheStore,
    private val cacheDir: File
) {
    private val gson = Gson()

    constructor(context: Context) : this(
        roomStore = PlatformPlaylistCacheRoomStore(
            NeriUserDataDatabase.getInstance(context.applicationContext)
        ),
        cacheDir = File(context.applicationContext.filesDir, CACHE_DIR_NAME)
    )

    internal constructor(
        context: Context,
        database: NeriUserDataDatabase,
        cacheDir: File = File(context.applicationContext.filesDir, CACHE_DIR_NAME)
    ) : this(
        roomStore = PlatformPlaylistCacheRoomStore(database),
        cacheDir = cacheDir
    )

    fun read(mediaId: Long, kind: String): BiliArchiveContentCache? {
        val cacheKey = cacheKey(mediaId, kind)
        readRoom(cacheKey)?.let { return it }
        val file = cacheFile(mediaId, kind)
        if (!file.exists()) return null
        return runCatching {
            readLegacyFile(file, mediaId, kind)?.also(::saveRoomAndDeleteLegacy)
        }.onFailure { error ->
            NPLogger.w(TAG, "Failed to read Bili archive cache: mediaId=$mediaId, kind=$kind", error)
        }.getOrNull()
    }

    fun save(cache: BiliArchiveContentCache) {
        if (saveRoom(cache)) {
            deleteLegacyFile(cacheFile(cache.mediaId, cache.kind))
        }
    }

    fun clear(mediaId: Long, kind: String) {
        clearRoom(cacheKey(mediaId, kind))
        deleteLegacyFile(cacheFile(mediaId, kind))
    }

    fun importLegacyCaches() {
        val files = cacheDir.listFiles { file ->
            file.isFile && file.name.endsWith(".json")
        }.orEmpty()
        files.forEach { file ->
            runCatching {
                readLegacyFile(
                    file = file,
                    expectedMediaId = null,
                    expectedKind = null
                )?.also { cache -> saveRoomIfNewerAndDeleteLegacy(cache, file) }
            }.onFailure { error ->
                NPLogger.w(TAG, "Failed to import Bili archive cache: file=${file.name}", error)
            }
        }
    }

    private fun readRoom(cacheKey: String): BiliArchiveContentCache? {
        return runCatching {
            runBlocking(Dispatchers.IO) {
                roomStore.read(PLATFORM, cacheKey)
            }?.toBiliArchiveCache()
        }.onFailure { error ->
            NPLogger.w(TAG, "Failed to read Bili archive cache from Room: cacheKey=$cacheKey", error)
        }.getOrNull()
    }

    private fun saveRoom(cache: BiliArchiveContentCache): Boolean {
        return runCatching {
            runBlocking(Dispatchers.IO) {
                roomStore.replace(cache.toRecord())
            }
            true
        }.onFailure { error ->
            NPLogger.w(
                TAG,
                "Failed to save Bili archive cache to Room: mediaId=${cache.mediaId}, kind=${cache.kind}",
                error
            )
        }.getOrDefault(false)
    }

    private fun saveRoomIfNewer(cache: BiliArchiveContentCache): Boolean {
        return runCatching {
            runBlocking(Dispatchers.IO) {
                roomStore.replaceIfNewer(cache.toRecord())
            }
            true
        }.onFailure { error ->
            NPLogger.w(
                TAG,
                "Failed to import Bili archive cache into Room: mediaId=${cache.mediaId}, kind=${cache.kind}",
                error
            )
        }.getOrDefault(false)
    }

    private fun clearRoom(cacheKey: String) {
        runCatching {
            runBlocking(Dispatchers.IO) {
                roomStore.clear(PLATFORM, cacheKey)
            }
        }.onFailure { error ->
            NPLogger.w(TAG, "Failed to clear Bili archive cache from Room: cacheKey=$cacheKey", error)
        }
    }

    private fun saveRoomAndDeleteLegacy(cache: BiliArchiveContentCache) {
        if (saveRoom(cache)) {
            deleteLegacyFile(cacheFile(cache.mediaId, cache.kind))
        }
    }

    private fun saveRoomIfNewerAndDeleteLegacy(
        cache: BiliArchiveContentCache,
        file: File
    ) {
        if (saveRoomIfNewer(cache)) {
            deleteLegacyFile(file)
        }
    }

    private fun readLegacyFile(
        file: File,
        expectedMediaId: Long?,
        expectedKind: String?
    ): BiliArchiveContentCache? {
        return gson.fromJson(file.readText(Charsets.UTF_8), BiliArchiveContentCache::class.java)
            ?.takeIf { cache ->
                (expectedMediaId == null || cache.mediaId == expectedMediaId) &&
                    (expectedKind == null || cache.kind == expectedKind)
            }
    }

    private fun deleteLegacyFile(file: File) {
        if (file.exists() && !file.delete()) {
            NPLogger.w(TAG, "Failed to delete legacy Bili archive cache: file=${file.name}")
        }
        deleteCacheDirIfEmpty()
    }

    private fun deleteCacheDirIfEmpty() {
        if (cacheDir.isDirectory && cacheDir.listFiles().orEmpty().isEmpty()) {
            cacheDir.delete()
        }
    }

    private fun cacheFile(mediaId: Long, kind: String): File {
        return File(cacheDir, biliArchiveCacheFileName(mediaId, kind))
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
        const val TAG = "BiliArchiveCache"
        const val CACHE_DIR_NAME = "bili_archive_cache"
        const val MILLIS_PER_SECOND = 1_000L
    }
}
