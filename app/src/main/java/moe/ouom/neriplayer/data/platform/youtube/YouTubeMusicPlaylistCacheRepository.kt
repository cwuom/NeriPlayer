package moe.ouom.neriplayer.data.platform.youtube

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
 * File: moe.ouom.neriplayer.data.platform.youtube/YouTubeMusicPlaylistCacheRepository
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
import java.security.MessageDigest

data class CachedYouTubeMusicPlaylistTrack(
    val videoId: String,
    val name: String,
    val artist: String,
    val albumName: String,
    val durationMs: Long,
    val coverUrl: String
)

data class CachedYouTubeMusicPlaylistDetail(
    val browseId: String,
    val playlistId: String,
    val title: String,
    val subtitle: String,
    val creatorName: String? = null,
    val coverUrl: String,
    val trackCount: Int,
    val firstPageSignature: String,
    val tracks: List<CachedYouTubeMusicPlaylistTrack>,
    val savedAtMs: Long = System.currentTimeMillis()
)

class YouTubeMusicPlaylistCacheRepository internal constructor(
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

    fun read(browseId: String): CachedYouTubeMusicPlaylistDetail? {
        val roomResult = readRoom(browseId)
        if (roomResult.isFailure) return null
        roomResult.getOrNull()?.let { return it }
        val file = cacheFile(browseId)
        if (!file.exists()) return null
        return runCatching {
            readLegacyFile(file, browseId)?.also(::saveRoomAndDeleteLegacy)
        }.onFailure { error ->
            NPLogger.w(TAG, "Failed to read YouTube Music playlist cache: browseId=$browseId", error)
        }.getOrNull()
    }

    fun save(cache: CachedYouTubeMusicPlaylistDetail) {
        if (saveRoom(cache)) {
            deleteLegacyFile(cacheFile(cache.browseId))
        }
    }

    fun clear(browseId: String) {
        clearRoom(browseId)
        deleteLegacyFile(cacheFile(browseId))
    }

    fun importLegacyCaches() {
        val files = cacheDir.listFiles { file ->
            file.isFile && file.name.endsWith(".json")
        }.orEmpty()
        files.forEach { file ->
            runCatching {
                readLegacyFile(file, expectedBrowseId = null)
                    ?.also { cache -> saveRoomIfNewerAndDeleteLegacy(cache, file) }
            }.onFailure { error ->
                NPLogger.w(TAG, "Failed to import YouTube Music playlist cache: file=${file.name}", error)
            }
        }
    }

    private fun readRoom(browseId: String): Result<CachedYouTubeMusicPlaylistDetail?> {
        return runCatching {
            runBlocking(Dispatchers.IO) {
                roomStore.read(PLATFORM, browseId)
            }?.toYouTubeMusicPlaylistDetail()
        }.onFailure { error ->
            NPLogger.w(TAG, "Failed to read YouTube Music playlist cache from Room: browseId=$browseId", error)
        }
    }

    private fun saveRoom(cache: CachedYouTubeMusicPlaylistDetail): Boolean {
        return runCatching {
            runBlocking(Dispatchers.IO) {
                roomStore.replace(cache.toRecord())
            }
            true
        }.onFailure { error ->
            NPLogger.w(
                TAG,
                "Failed to save YouTube Music playlist cache to Room: browseId=${cache.browseId}",
                error
            )
        }.getOrDefault(false)
    }

    private fun saveRoomIfNewer(cache: CachedYouTubeMusicPlaylistDetail): Boolean {
        return runCatching {
            runBlocking(Dispatchers.IO) {
                roomStore.replaceIfNewer(cache.toRecord())
            }
            true
        }.onFailure { error ->
            NPLogger.w(
                TAG,
                "Failed to import YouTube Music playlist cache into Room: browseId=${cache.browseId}",
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
            NPLogger.w(
                TAG,
                "Failed to clear YouTube Music playlist cache from Room: browseId=$cacheKey",
                error
            )
        }
    }

    private fun saveRoomAndDeleteLegacy(cache: CachedYouTubeMusicPlaylistDetail) {
        if (saveRoom(cache)) {
            deleteLegacyFile(cacheFile(cache.browseId))
        }
    }

    private fun saveRoomIfNewerAndDeleteLegacy(
        cache: CachedYouTubeMusicPlaylistDetail,
        file: File
    ) {
        if (saveRoomIfNewer(cache)) {
            deleteLegacyFile(file)
        }
    }

    private fun readLegacyFile(
        file: File,
        expectedBrowseId: String?
    ): CachedYouTubeMusicPlaylistDetail? {
        return gson.fromJson(file.readText(Charsets.UTF_8), CachedYouTubeMusicPlaylistDetail::class.java)
            ?.takeIf { cache -> expectedBrowseId == null || cache.browseId == expectedBrowseId }
    }

    private fun deleteLegacyFile(file: File) {
        if (file.exists() && !file.delete()) {
            NPLogger.w(TAG, "Failed to delete legacy YouTube Music playlist cache: file=${file.name}")
        }
        deleteCacheDirIfEmpty()
    }

    private fun deleteCacheDirIfEmpty() {
        if (cacheDir.isDirectory && cacheDir.listFiles().orEmpty().isEmpty()) {
            cacheDir.delete()
        }
    }

    private fun cacheFile(browseId: String): File {
        return File(cacheDir, "${sha256(browseId)}.json")
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun CachedYouTubeMusicPlaylistDetail.toRecord(): PlatformPlaylistCacheRecord {
        return PlatformPlaylistCacheRecord(
            platform = PLATFORM,
            cacheKey = browseId,
            alternateKey = playlistId,
            title = title,
            subtitle = subtitle,
            creatorName = creatorName,
            coverUrl = coverUrl,
            trackCount = trackCount,
            totalCount = tracks.size,
            signaturePrimary = firstPageSignature,
            savedAtMs = savedAtMs,
            tracks = tracks.map { track ->
                PlatformPlaylistCacheTrackRecord(
                    itemKey = track.videoId,
                    name = track.name,
                    artist = track.artist,
                    album = track.albumName,
                    durationMs = track.durationMs,
                    coverUrl = track.coverUrl
                )
            }
        )
    }

    private fun PlatformPlaylistCacheRecord.toYouTubeMusicPlaylistDetail(): CachedYouTubeMusicPlaylistDetail {
        return CachedYouTubeMusicPlaylistDetail(
            browseId = cacheKey,
            playlistId = alternateKey.orEmpty(),
            title = title.orEmpty(),
            subtitle = subtitle.orEmpty(),
            creatorName = creatorName,
            coverUrl = coverUrl.orEmpty(),
            trackCount = trackCount,
            firstPageSignature = signaturePrimary.orEmpty(),
            tracks = tracks.map { track ->
                CachedYouTubeMusicPlaylistTrack(
                    videoId = track.itemKey.orEmpty(),
                    name = track.name,
                    artist = track.artist,
                    albumName = track.album,
                    durationMs = track.durationMs,
                    coverUrl = track.coverUrl.orEmpty()
                )
            },
            savedAtMs = savedAtMs
        )
    }

    private companion object {
        const val PLATFORM = "youtube_music"
        const val TAG = "YouTubeMusicPlaylistCache"
        const val CACHE_DIR_NAME = "youtube_music_playlist_cache"
    }
}
