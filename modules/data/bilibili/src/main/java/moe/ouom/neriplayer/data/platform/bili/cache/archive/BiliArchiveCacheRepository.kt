package moe.ouom.neriplayer.data.platform.bili.cache.archive

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

import moe.ouom.neriplayer.data.model.bilibili.cache.archive.BiliArchiveContentCache
import com.google.gson.Gson
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.logging.NPLogger

internal fun biliArchiveCacheFileName(mediaId: Long, kind: String): String {
    return "${kind.lowercase(Locale.ROOT)}_$mediaId.json"
}

class BiliArchiveCacheRepository(
    private val store: BiliArchiveCacheStore,
    private val cacheDir: File
) {
    private val gson = Gson()

    fun read(mediaId: Long, kind: String): BiliArchiveContentCache? {
        readStored(mediaId, kind)?.let { return it }
        val file = cacheFile(mediaId, kind)
        if (!file.exists()) return null
        return runCatching {
            readLegacyFile(file, mediaId, kind)?.also(::saveStoredAndDeleteLegacy)
        }.onFailure { error ->
            NPLogger.w(TAG, "Failed to read Bili archive cache: mediaId=$mediaId, kind=$kind", error)
        }.getOrNull()
    }

    fun save(cache: BiliArchiveContentCache) {
        runCatching {
            saveStored(cache)
            deleteLegacyFile(cacheFile(cache.mediaId, cache.kind))
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
            clearStored(mediaId, kind)
            cacheFile(mediaId, kind).delete()
        }.onFailure { error ->
            NPLogger.w(TAG, "Failed to clear Bili archive cache: mediaId=$mediaId, kind=$kind", error)
        }
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
                )?.also { cache -> saveStoredIfNewerAndDeleteLegacy(cache, file) }
            }.onFailure { error ->
                NPLogger.w(TAG, "Failed to import Bili archive cache: file=${file.name}", error)
            }
        }
    }

    private fun readStored(mediaId: Long, kind: String): BiliArchiveContentCache? {
        return runBlocking(Dispatchers.IO) {
            store.read(mediaId, kind)
        }
    }

    private fun saveStored(cache: BiliArchiveContentCache) {
        runBlocking(Dispatchers.IO) {
            store.replace(cache)
        }
    }

    private fun saveStoredIfNewer(cache: BiliArchiveContentCache) {
        runBlocking(Dispatchers.IO) {
            store.replaceIfNewer(cache)
        }
    }

    private fun clearStored(mediaId: Long, kind: String) {
        runBlocking(Dispatchers.IO) {
            store.clear(mediaId, kind)
        }
    }

    private fun saveStoredAndDeleteLegacy(cache: BiliArchiveContentCache) {
        saveStored(cache)
        deleteLegacyFile(cacheFile(cache.mediaId, cache.kind))
    }

    private fun saveStoredIfNewerAndDeleteLegacy(
        cache: BiliArchiveContentCache,
        file: File
    ) {
        saveStoredIfNewer(cache)
        deleteLegacyFile(file)
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

    private companion object {
        const val TAG = "BiliArchiveCache"
    }
}
