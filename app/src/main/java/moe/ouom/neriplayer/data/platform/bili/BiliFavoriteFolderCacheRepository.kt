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
import moe.ouom.neriplayer.util.NPLogger
import java.io.File

data class CachedBiliFavoriteVideo(
    val id: Long,
    val bvid: String,
    val title: String,
    val uploader: String,
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

class BiliFavoriteFolderCacheRepository(context: Context) {
    private val appContext = context.applicationContext
    private val gson = Gson()
    private val cacheDir = File(appContext.filesDir, CACHE_DIR_NAME)

    fun read(mediaId: Long): BiliFavoriteFolderContentCache? {
        val file = cacheFile(mediaId)
        if (!file.exists()) return null
        return runCatching {
            gson.fromJson(file.readText(Charsets.UTF_8), BiliFavoriteFolderContentCache::class.java)
                ?.takeIf { it.mediaId == mediaId }
        }.onFailure { error ->
            NPLogger.w(TAG, "Failed to read Bili favorite cache: mediaId=$mediaId", error)
        }.getOrNull()
    }

    fun save(cache: BiliFavoriteFolderContentCache) {
        runCatching {
            cacheDir.mkdirs()
            val file = cacheFile(cache.mediaId)
            val tmp = File(cacheDir, "${file.name}.tmp")
            tmp.writeText(gson.toJson(cache), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(gson.toJson(cache), Charsets.UTF_8)
                tmp.delete()
            }
        }.onFailure { error ->
            NPLogger.w(TAG, "Failed to save Bili favorite cache: mediaId=${cache.mediaId}", error)
        }
    }

    fun clear(mediaId: Long) {
        runCatching {
            cacheFile(mediaId).delete()
        }.onFailure { error ->
            NPLogger.w(TAG, "Failed to clear Bili favorite cache: mediaId=$mediaId", error)
        }
    }

    private fun cacheFile(mediaId: Long): File {
        return File(cacheDir, "media_$mediaId.json")
    }

    private companion object {
        const val TAG = "BiliFavoriteCache"
        const val CACHE_DIR_NAME = "bili_favorite_cache"
    }
}
