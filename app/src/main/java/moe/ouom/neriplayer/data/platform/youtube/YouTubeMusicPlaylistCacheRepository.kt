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
import moe.ouom.neriplayer.core.logging.NPLogger
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
    val coverUrl: String,
    val trackCount: Int,
    val firstPageSignature: String,
    val tracks: List<CachedYouTubeMusicPlaylistTrack>,
    val savedAtMs: Long = System.currentTimeMillis()
)

class YouTubeMusicPlaylistCacheRepository(context: Context) {
    private val appContext = context.applicationContext
    private val gson = Gson()
    private val cacheDir = File(appContext.filesDir, CACHE_DIR_NAME)

    fun read(browseId: String): CachedYouTubeMusicPlaylistDetail? {
        val file = cacheFile(browseId)
        if (!file.exists()) return null
        return runCatching {
            gson.fromJson(file.readText(Charsets.UTF_8), CachedYouTubeMusicPlaylistDetail::class.java)
                ?.takeIf { it.browseId == browseId }
        }.onFailure { error ->
            NPLogger.w(TAG, "Failed to read YouTube Music playlist cache: browseId=$browseId", error)
        }.getOrNull()
    }

    fun save(cache: CachedYouTubeMusicPlaylistDetail) {
        runCatching {
            cacheDir.mkdirs()
            val file = cacheFile(cache.browseId)
            val tmp = File(cacheDir, "${file.name}.tmp")
            tmp.writeText(gson.toJson(cache), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(gson.toJson(cache), Charsets.UTF_8)
                tmp.delete()
            }
        }.onFailure { error ->
            NPLogger.w(TAG, "Failed to save YouTube Music playlist cache: browseId=${cache.browseId}", error)
        }
    }

    fun clear(browseId: String) {
        runCatching {
            cacheFile(browseId).delete()
        }.onFailure { error ->
            NPLogger.w(TAG, "Failed to clear YouTube Music playlist cache: browseId=$browseId", error)
        }
    }

    private fun cacheFile(browseId: String): File {
        return File(cacheDir, "${sha256(browseId)}.json")
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val TAG = "YouTubeMusicPlaylistCache"
        const val CACHE_DIR_NAME = "youtube_music_playlist_cache"
    }
}
