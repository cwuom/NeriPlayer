package moe.ouom.neriplayer.data.platform.bili.cache.favorite

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

import moe.ouom.neriplayer.data.model.bilibili.cache.favorite.BiliFavoriteFolderContentCache
import com.google.gson.Gson
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.logging.NPLogger

class BiliFavoriteFolderCacheRepository(
    private val store: BiliFavoriteFolderCacheStore,
    private val cacheDir: File
) {
    private val gson = Gson()

    fun read(mediaId: Long): BiliFavoriteFolderContentCache? {
        readStored(mediaId)?.let { return it }
        val file = cacheFile(mediaId)
        if (!file.exists()) return null
        return runCatching {
            readLegacyFile(file, mediaId)?.also(::saveStoredAndDeleteLegacy)
        }.onFailure { error ->
            NPLogger.w(TAG, "Failed to read Bili favorite cache: mediaId=$mediaId", error)
        }.getOrNull()
    }

    fun save(cache: BiliFavoriteFolderContentCache) {
        runCatching {
            saveStored(cache)
            deleteLegacyFile(cacheFile(cache.mediaId))
        }.onFailure { error ->
            NPLogger.w(TAG, "Failed to save Bili favorite cache: mediaId=${cache.mediaId}", error)
        }
    }

    fun clear(mediaId: Long) {
        runCatching {
            clearStored(mediaId)
            cacheFile(mediaId).delete()
        }.onFailure { error ->
            NPLogger.w(TAG, "Failed to clear Bili favorite cache: mediaId=$mediaId", error)
        }
    }

    fun importLegacyCaches() {
        val files = cacheDir.listFiles { file ->
            file.isFile && file.name.endsWith(".json")
        }.orEmpty()
        files.forEach { file ->
            runCatching {
                readLegacyFile(file, expectedMediaId = null)
                    ?.also { cache -> saveStoredIfNewerAndDeleteLegacy(cache, file) }
            }.onFailure { error ->
                NPLogger.w(TAG, "Failed to import Bili favorite cache: file=${file.name}", error)
            }
        }
    }

    private fun readStored(mediaId: Long): BiliFavoriteFolderContentCache? {
        return runBlocking(Dispatchers.IO) {
            store.read(mediaId)
        }
    }

    private fun saveStored(cache: BiliFavoriteFolderContentCache) {
        runBlocking(Dispatchers.IO) {
            store.replace(cache)
        }
    }

    private fun saveStoredIfNewer(cache: BiliFavoriteFolderContentCache) {
        runBlocking(Dispatchers.IO) {
            store.replaceIfNewer(cache)
        }
    }

    private fun clearStored(mediaId: Long) {
        runBlocking(Dispatchers.IO) {
            store.clear(mediaId)
        }
    }

    private fun saveStoredAndDeleteLegacy(cache: BiliFavoriteFolderContentCache) {
        saveStored(cache)
        deleteLegacyFile(cacheFile(cache.mediaId))
    }

    private fun saveStoredIfNewerAndDeleteLegacy(
        cache: BiliFavoriteFolderContentCache,
        file: File
    ) {
        saveStoredIfNewer(cache)
        deleteLegacyFile(file)
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

    private companion object {
        const val TAG = "BiliFavoriteCache"
    }
}
