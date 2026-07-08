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
import java.io.File
import moe.ouom.neriplayer.util.NPLogger

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

class NeteasePlaylistCacheRepository(context: Context) {
    private val appContext = context.applicationContext
    private val gson = Gson()
    private val cacheDir = File(appContext.filesDir, CACHE_DIR_NAME)

    fun read(playlistId: Long): CachedNeteasePlaylistDetail? {
        val file = cacheFile(playlistId)
        if (!file.exists()) return null
        return runCatching {
            gson.fromJson(file.readText(Charsets.UTF_8), CachedNeteasePlaylistDetail::class.java)
                ?.takeIf { it.playlistId == playlistId }
        }.onFailure { error ->
            NPLogger.w(TAG, "Failed to read NetEase playlist cache: playlistId=$playlistId", error)
        }.getOrNull()
    }

    fun save(cache: CachedNeteasePlaylistDetail) {
        runCatching {
            cacheDir.mkdirs()
            val file = cacheFile(cache.playlistId)
            val tmp = File(cacheDir, "${file.name}.tmp")
            tmp.writeText(gson.toJson(cache), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(gson.toJson(cache), Charsets.UTF_8)
                tmp.delete()
            }
        }.onFailure { error ->
            NPLogger.w(TAG, "Failed to save NetEase playlist cache: playlistId=${cache.playlistId}", error)
        }
    }

    fun clear(playlistId: Long) {
        runCatching {
            cacheFile(playlistId).delete()
        }.onFailure { error ->
            NPLogger.w(TAG, "Failed to clear NetEase playlist cache: playlistId=$playlistId", error)
        }
    }

    private fun cacheFile(playlistId: Long): File {
        return File(cacheDir, "playlist_$playlistId.json")
    }

    private companion object {
        const val TAG = "NeteasePlaylistCache"
        const val CACHE_DIR_NAME = "netease_playlist_cache"
    }
}
