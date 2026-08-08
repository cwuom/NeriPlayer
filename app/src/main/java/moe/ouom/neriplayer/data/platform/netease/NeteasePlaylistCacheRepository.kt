package moe.ouom.neriplayer.data.platform.netease

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
 * File: moe.ouom.neriplayer.data.platform.netease/NeteasePlaylistCacheRepository
 * Created: 2026/7/9
 */

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheArtistRecord
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheRecord
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheRoomStore
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheStore
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheTrackRecord

data class CachedNeteasePlaylistHeader(
    val id: Long,
    val name: String,
    val coverUrl: String,
    val playCount: Long,
    val trackCount: Int
)

data class CachedNeteaseArtist(
    val id: Long,
    val name: String
)

data class CachedNeteasePlaylistTrack(
    val id: Long,
    val name: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val coverUrl: String?,
    val audioId: String?,
    val artists: List<CachedNeteaseArtist> = emptyList(),
    val addedAt: Long = 0L
)

data class CachedNeteasePlaylistDetail(
    val playlistId: Long,
    val header: CachedNeteasePlaylistHeader,
    val recentTrackSignature: String,
    val tracks: List<CachedNeteasePlaylistTrack>,
    val savedAtMs: Long = System.currentTimeMillis()
)

class NeteasePlaylistCacheRepository internal constructor(
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

    fun read(playlistId: Long): CachedNeteasePlaylistDetail? {
        val cacheKey = playlistId.toString()
        readRoom(cacheKey)?.let { return it }
        val file = cacheFile(playlistId)
        if (!file.exists()) return null
        return runCatching {
            readLegacyFile(file, playlistId)?.also(::saveRoomAndDeleteLegacy)
        }.onFailure { error ->
            NPLogger.w(TAG, "Failed to read NetEase playlist cache: playlistId=$playlistId", error)
        }.getOrNull()
    }

    fun save(cache: CachedNeteasePlaylistDetail) {
        if (saveRoom(cache)) {
            deleteLegacyFile(cacheFile(cache.playlistId))
        }
    }

    fun clear(playlistId: Long) {
        clearRoom(playlistId.toString())
        deleteLegacyFile(cacheFile(playlistId))
    }

    fun importLegacyCaches() {
        val files = cacheDir.listFiles { file ->
            file.isFile && file.name.endsWith(".json")
        }.orEmpty()
        files.forEach { file ->
            runCatching {
                readLegacyFile(file, expectedPlaylistId = null)
                    ?.also { cache -> saveRoomIfNewerAndDeleteLegacy(cache, file) }
            }.onFailure { error ->
                NPLogger.w(TAG, "Failed to import NetEase playlist cache: file=${file.name}", error)
            }
        }
    }

    private fun readRoom(cacheKey: String): CachedNeteasePlaylistDetail? {
        return runCatching {
            runBlocking(Dispatchers.IO) {
                roomStore.read(PLATFORM, cacheKey)
            }?.toNeteasePlaylistDetail()
        }.onFailure { error ->
            NPLogger.w(TAG, "Failed to read NetEase playlist cache from Room: cacheKey=$cacheKey", error)
        }.getOrNull()
    }

    private fun saveRoom(cache: CachedNeteasePlaylistDetail): Boolean {
        return runCatching {
            runBlocking(Dispatchers.IO) {
                roomStore.replace(cache.toRecord())
            }
            true
        }.onFailure { error ->
            NPLogger.w(
                TAG,
                "Failed to save NetEase playlist cache to Room: playlistId=${cache.playlistId}",
                error
            )
        }.getOrDefault(false)
    }

    private fun saveRoomIfNewer(cache: CachedNeteasePlaylistDetail): Boolean {
        return runCatching {
            runBlocking(Dispatchers.IO) {
                roomStore.replaceIfNewer(cache.toRecord())
            }
            true
        }.onFailure { error ->
            NPLogger.w(
                TAG,
                "Failed to import NetEase playlist cache into Room: playlistId=${cache.playlistId}",
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
            NPLogger.w(TAG, "Failed to clear NetEase playlist cache from Room: cacheKey=$cacheKey", error)
        }
    }

    private fun saveRoomAndDeleteLegacy(cache: CachedNeteasePlaylistDetail) {
        if (saveRoom(cache)) {
            deleteLegacyFile(cacheFile(cache.playlistId))
        }
    }

    private fun saveRoomIfNewerAndDeleteLegacy(
        cache: CachedNeteasePlaylistDetail,
        file: File
    ) {
        if (saveRoomIfNewer(cache)) {
            deleteLegacyFile(file)
        }
    }

    private fun readLegacyFile(
        file: File,
        expectedPlaylistId: Long?
    ): CachedNeteasePlaylistDetail? {
        return gson.fromJson(file.readText(Charsets.UTF_8), CachedNeteasePlaylistDetail::class.java)
            ?.takeIf { cache -> expectedPlaylistId == null || cache.playlistId == expectedPlaylistId }
    }

    private fun deleteLegacyFile(file: File) {
        if (file.exists() && !file.delete()) {
            NPLogger.w(TAG, "Failed to delete legacy NetEase playlist cache: file=${file.name}")
        }
        deleteCacheDirIfEmpty()
    }

    private fun deleteCacheDirIfEmpty() {
        if (cacheDir.isDirectory && cacheDir.listFiles().orEmpty().isEmpty()) {
            cacheDir.delete()
        }
    }

    private fun cacheFile(playlistId: Long): File {
        return File(cacheDir, "playlist_$playlistId.json")
    }

    private fun CachedNeteasePlaylistDetail.toRecord(): PlatformPlaylistCacheRecord {
        return PlatformPlaylistCacheRecord(
            platform = PLATFORM,
            cacheKey = playlistId.toString(),
            sourceId = playlistId,
            title = header.name,
            coverUrl = header.coverUrl,
            playCount = header.playCount,
            trackCount = header.trackCount,
            totalCount = tracks.size,
            signaturePrimary = recentTrackSignature,
            savedAtMs = savedAtMs,
            tracks = tracks.map { track ->
                PlatformPlaylistCacheTrackRecord(
                    itemId = track.id,
                    name = track.name,
                    artist = track.artist,
                    album = track.album,
                    albumId = track.albumId,
                    durationMs = track.durationMs,
                    coverUrl = track.coverUrl,
                    audioId = track.audioId,
                    addedAt = track.addedAt,
                    artists = track.artists.map { artist ->
                        PlatformPlaylistCacheArtistRecord(
                            id = artist.id,
                            name = artist.name
                        )
                    }
                )
            }
        )
    }

    private fun PlatformPlaylistCacheRecord.toNeteasePlaylistDetail(): CachedNeteasePlaylistDetail {
        val playlistId = sourceId ?: cacheKey.toLong()
        return CachedNeteasePlaylistDetail(
            playlistId = playlistId,
            header = CachedNeteasePlaylistHeader(
                id = playlistId,
                name = title.orEmpty(),
                coverUrl = coverUrl.orEmpty(),
                playCount = playCount ?: 0L,
                trackCount = trackCount
            ),
            recentTrackSignature = signaturePrimary.orEmpty(),
            tracks = tracks.map { track ->
                CachedNeteasePlaylistTrack(
                    id = track.itemId ?: 0L,
                    name = track.name,
                    artist = track.artist,
                    album = track.album,
                    albumId = track.albumId ?: 0L,
                    durationMs = track.durationMs,
                    coverUrl = track.coverUrl,
                    audioId = track.audioId,
                    artists = track.artists.map { artist ->
                        CachedNeteaseArtist(
                            id = artist.id,
                            name = artist.name
                        )
                    },
                    addedAt = track.addedAt
                )
            },
            savedAtMs = savedAtMs
        )
    }

    private companion object {
        const val PLATFORM = "netease"
        const val TAG = "NeteasePlaylistCache"
        const val CACHE_DIR_NAME = "netease_playlist_cache"
    }
}
