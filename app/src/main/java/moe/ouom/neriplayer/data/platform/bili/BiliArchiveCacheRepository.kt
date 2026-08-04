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
import java.io.File
import java.util.Locale
import moe.ouom.neriplayer.core.logging.NPLogger

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

class BiliArchiveCacheRepository(context: Context) {
    private val appContext = context.applicationContext
    private val gson = Gson()
    private val cacheDir = File(appContext.filesDir, CACHE_DIR_NAME)

    fun read(mediaId: Long, kind: String): BiliArchiveContentCache? {
        val file = cacheFile(mediaId, kind)
        if (!file.exists()) return null
        return runCatching {
            gson.fromJson(file.readText(Charsets.UTF_8), BiliArchiveContentCache::class.java)
                ?.takeIf { it.mediaId == mediaId && it.kind == kind }
        }.onFailure { error ->
            NPLogger.w(TAG, "Failed to read Bili archive cache: mediaId=$mediaId, kind=$kind", error)
        }.getOrNull()
    }

    fun save(cache: BiliArchiveContentCache) {
        runCatching {
            cacheDir.mkdirs()
            val file = cacheFile(cache.mediaId, cache.kind)
            val tmp = File(cacheDir, "${file.name}.tmp")
            tmp.writeText(gson.toJson(cache), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(gson.toJson(cache), Charsets.UTF_8)
                tmp.delete()
            }
        }.onFailure { error ->
            NPLogger.w(
                TAG,
                "Failed to save Bili archive cache: mediaId=${cache.mediaId}, kind=${cache.kind}",
                error
            )
        }
    }

    fun clear(mediaId: Long, kind: String) {
        runCatching {
            cacheFile(mediaId, kind).delete()
        }.onFailure { error ->
            NPLogger.w(TAG, "Failed to clear Bili archive cache: mediaId=$mediaId, kind=$kind", error)
        }
    }

    private fun cacheFile(mediaId: Long, kind: String): File {
        return File(cacheDir, biliArchiveCacheFileName(mediaId, kind))
    }

    private companion object {
        const val TAG = "BiliArchiveCache"
        const val CACHE_DIR_NAME = "bili_archive_cache"
    }
}
