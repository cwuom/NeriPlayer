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
 * File: moe.ouom.neriplayer.data.platform.bili/BiliFavoriteFolderCacheRepository
 * Created: 2026/7/2
 */

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheRecord
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheRoomStore
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheStore
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheTrackRecord
import java.io.File

data class CachedBiliFavoriteVideo(
    val id: Long,
    val bvid: String,
    val title: String,
    val uploader: String,
    val uploaderMid: Long = 0L,
    val coverUrl: String,
    val durationSec: Int
)

data class BiliFavoriteFolderContentCache(
    val mediaId: Long,
    val latestPageSignature: String,
    val totalCount: Int,
    val videos: List<CachedBiliFavoriteVideo>,
    val savedAtMs: Long = System.currentTimeMillis()
)

class BiliFavoriteFolderCacheRepository internal constructor(
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

    fun read(mediaId: Long): BiliFavoriteFolderContentCache? {
        val cacheKey = mediaId.toString()
        readRoom(cacheKey)?.let { return it }
        val file = cacheFile(mediaId)
        if (!file.exists()) return null
        return runCatching {
            readLegacyFile(file, mediaId)?.also(::saveRoomAndDeleteLegacy)
        }.onFailure { error ->
            NPLogger.w(TAG, "Failed to read Bili favorite cache: mediaId=$mediaId", error)
        }.getOrNull()
    }

    fun save(cache: BiliFavoriteFolderContentCache) {
        if (saveRoom(cache)) {
            deleteLegacyFile(cacheFile(cache.mediaId))
        }
    }

    fun clear(mediaId: Long) {
        clearRoom(mediaId.toString())
        deleteLegacyFile(cacheFile(mediaId))
    }

    fun importLegacyCaches() {
        val files = cacheDir.listFiles { file ->
            file.isFile && file.name.endsWith(".json")
        }.orEmpty()
        files.forEach { file ->
            runCatching {
                readLegacyFile(file, expectedMediaId = null)
                    ?.also { cache -> saveRoomIfNewerAndDeleteLegacy(cache, file) }
            }.onFailure { error ->
                NPLogger.w(TAG, "Failed to import Bili favorite cache: file=${file.name}", error)
            }
        }
    }

    private fun readRoom(cacheKey: String): BiliFavoriteFolderContentCache? {
        return runCatching {
            runBlocking(Dispatchers.IO) {
                roomStore.read(PLATFORM, cacheKey)
            }?.toBiliFavoriteCache()
        }.onFailure { error ->
            NPLogger.w(TAG, "Failed to read Bili favorite cache from Room: cacheKey=$cacheKey", error)
        }.getOrNull()
    }

    private fun saveRoom(cache: BiliFavoriteFolderContentCache): Boolean {
        return runCatching {
            runBlocking(Dispatchers.IO) {
                roomStore.replace(cache.toRecord())
            }
            true
        }.onFailure { error ->
            NPLogger.w(
                TAG,
                "Failed to save Bili favorite cache to Room: mediaId=${cache.mediaId}",
                error
            )
        }.getOrDefault(false)
    }

    private fun saveRoomIfNewer(cache: BiliFavoriteFolderContentCache): Boolean {
        return runCatching {
            runBlocking(Dispatchers.IO) {
                roomStore.replaceIfNewer(cache.toRecord())
            }
            true
        }.onFailure { error ->
            NPLogger.w(
                TAG,
                "Failed to import Bili favorite cache into Room: mediaId=${cache.mediaId}",
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
            NPLogger.w(TAG, "Failed to clear Bili favorite cache from Room: cacheKey=$cacheKey", error)
        }
    }

    private fun saveRoomAndDeleteLegacy(cache: BiliFavoriteFolderContentCache) {
        if (saveRoom(cache)) {
            deleteLegacyFile(cacheFile(cache.mediaId))
        }
    }

    private fun saveRoomIfNewerAndDeleteLegacy(
        cache: BiliFavoriteFolderContentCache,
        file: File
    ) {
        if (saveRoomIfNewer(cache)) {
            deleteLegacyFile(file)
        }
    }

    private fun readLegacyFile(
        file: File,
        expectedMediaId: Long?
    ): BiliFavoriteFolderContentCache? {
        return gson.fromJson(file.readText(Charsets.UTF_8), BiliFavoriteFolderContentCache::class.java)
            ?.takeIf { cache -> expectedMediaId == null || cache.mediaId == expectedMediaId }
    }

    private fun deleteLegacyFile(file: File) {
        if (file.exists() && !file.delete()) {
            NPLogger.w(TAG, "Failed to delete legacy Bili favorite cache: file=${file.name}")
        }
        deleteCacheDirIfEmpty()
    }

    private fun deleteCacheDirIfEmpty() {
        if (cacheDir.isDirectory && cacheDir.listFiles().orEmpty().isEmpty()) {
            cacheDir.delete()
        }
    }

    private fun cacheFile(mediaId: Long): File {
        return File(cacheDir, "media_$mediaId.json")
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
        const val TAG = "BiliFavoriteCache"
        const val CACHE_DIR_NAME = "bili_favorite_cache"
        const val MILLIS_PER_SECOND = 1_000L
    }
}
