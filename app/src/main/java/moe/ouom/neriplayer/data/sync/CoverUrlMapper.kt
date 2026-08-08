package moe.ouom.neriplayer.data.sync

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
 * File: moe.ouom.neriplayer.data.sync/CoverUrlMapper
 * Created: 2025/1/13
 */

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.startup.LegacyJsonCleanupScheduler
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.store.CoverUrlMappingRoomStore
import moe.ouom.neriplayer.data.local.media.LocalSongSupport

private const val COVER_URL_MAPPER_TAG = "CoverUrlMapper"

/**
 * 封面地址映射管理器
 * 维护本地地址和网络地址的映射关系
 * 用于同步时将本地地址转换为网络地址
 */
class CoverUrlMapper private constructor(
    private val store: CoverUrlMappingStore
) {
    private val mapping = ConcurrentHashMap<String, String>()

    init {
        mapping.putAll(store.load())
        NPLogger.d(COVER_URL_MAPPER_TAG, "Loaded ${mapping.size} cover URL mappings")
    }

    /**
     * 保存封面地址映射
     * @param localUrl 本地地址
     * @param networkUrl 网络地址
     */
    fun saveCoverMapping(localUrl: String?, networkUrl: String?) {
        if (localUrl.isNullOrBlank() || networkUrl.isNullOrBlank()) return
        if (!isLocalUrl(localUrl)) return

        mapping[localUrl] = networkUrl
        store.save(localUrl, networkUrl)
        NPLogger.d(COVER_URL_MAPPER_TAG, "Saved cover mapping: $localUrl -> $networkUrl")
    }

    /**
     * 获取网络地址
     * @param url 可能是本地地址或网络地址
     * @return 如果是本地地址且有映射, 返回网络地址; 否则返回原地址
     */
    fun getNetworkUrl(url: String?): String? {
        if (url.isNullOrBlank()) return url
        if (!isLocalUrl(url)) return url

        return mapping[url] ?: url
    }

    fun getSyncableNetworkUrl(url: String?): String? {
        val normalizedUrl = url?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (!LocalSongSupport.isLocalMediaUri(normalizedUrl)) {
            return normalizedUrl
        }
        return mapping[normalizedUrl]
            ?.trim()
            ?.takeIf { it.isNotBlank() && !LocalSongSupport.isLocalMediaUri(it) }
    }

    /**
     * 判断是否是本地地址
     */
    private fun isLocalUrl(url: String): Boolean {
        return LocalSongSupport.isLocalMediaUri(url) ||
               url.contains("/data/") ||
               url.contains("/storage/")
    }

    /**
     * 清理无效的映射 (本地文件已不存在)
     */
    @Suppress("unused")
    fun cleanupInvalidMappings() {
        val toRemove = mutableListOf<String>()
        for ((localUrl, _) in mapping) {
            val file = localUrl.toLocalFileOrNull()
            if (file != null && !file.exists()) {
                toRemove.add(localUrl)
            }
        }

        if (toRemove.isNotEmpty()) {
            toRemove.forEach { mapping.remove(it) }
            store.delete(toRemove)
            NPLogger.d(COVER_URL_MAPPER_TAG, "Cleaned up ${toRemove.size} invalid mappings")
        }
    }

    private fun String.toLocalFileOrNull(): File? {
        if (startsWith("/")) return File(this)
        if (!startsWith("file:", ignoreCase = true)) return null
        return runCatching { File(java.net.URI(this)) }.getOrNull()
    }

    companion object {
        private const val FILE_NAME = "cover_url_mapping.json"

        @Volatile
        private var instance: CoverUrlMapper? = null

        fun getInstance(context: Context): CoverUrlMapper {
            val appContext = context.applicationContext ?: context
            return instance ?: synchronized(this) {
                instance ?: CoverUrlMapper(
                    RoomCoverUrlMappingStore(appContext, FILE_NAME)
                ).also { instance = it }
            }
        }

        internal fun createForTest(
            initialMappings: Map<String, String> = emptyMap()
        ): CoverUrlMapper {
            return CoverUrlMapper(InMemoryCoverUrlMappingStore(initialMappings))
        }

        internal fun installForTest(mapper: CoverUrlMapper?) {
            synchronized(this) {
                instance = mapper
            }
        }
    }
}

internal interface CoverUrlMappingStore {
    fun load(): Map<String, String>

    fun save(localUrl: String, networkUrl: String)

    fun delete(localUrls: Collection<String>)
}

private class RoomCoverUrlMappingStore(
    context: Context,
    fileName: String
) : CoverUrlMappingStore {
    private val appContext = context.applicationContext ?: context
    private val roomStore = CoverUrlMappingRoomStore(
        NeriUserDataDatabase.getInstance(appContext)
    )
    private val gson = Gson()
    private val legacyFile = File(appContext.filesDir, fileName)
    private val legacyType = object : TypeToken<Map<String, String?>>() {}.type

    @Volatile
    private var cleanupEligible = true

    override fun load(): Map<String, String> {
        return runBlocking(Dispatchers.IO) {
            val roomRead = runCatching { roomStore.readIfRoomPrimary() }
                .onFailure { error ->
                    cleanupEligible = false
                    NPLogger.e(
                        COVER_URL_MAPPER_TAG,
                        "Failed to read cover URL mappings from Room",
                        error
                    )
                }
            if (roomRead.isFailure) {
                return@runBlocking emptyMap()
            }
            roomRead.getOrNull()?.also {
                LegacyJsonCleanupScheduler.schedule(appContext, "cover-url-mapping-room-load")
                return@runBlocking it
            }

            when (val legacy = readLegacyMappings()) {
                is LegacyMappingLoadResult.Missing -> emptyMap()
                is LegacyMappingLoadResult.Loaded -> {
                    cleanupEligible = true
                    roomStore.importLegacyAndPromote(
                        mappings = legacy.mappings,
                        cleanupEligible = true
                    )
                    LegacyJsonCleanupScheduler.schedule(appContext, "cover-url-mapping-import")
                    legacy.mappings
                }
                is LegacyMappingLoadResult.Failed -> {
                    cleanupEligible = false
                    NPLogger.e(
                        COVER_URL_MAPPER_TAG,
                        "Failed to import cover URL mappings",
                        legacy.error
                    )
                    emptyMap()
                }
            }
        }
    }

    override fun save(localUrl: String, networkUrl: String) {
        runCatching {
            runBlocking(Dispatchers.IO) {
                roomStore.upsert(
                    localUrl = localUrl,
                    networkUrl = networkUrl,
                    cleanupEligible = cleanupEligible
                )
            }
        }.onFailure { error ->
            NPLogger.w(COVER_URL_MAPPER_TAG, "Failed to save cover URL mapping", error)
        }
    }

    override fun delete(localUrls: Collection<String>) {
        runCatching {
            runBlocking(Dispatchers.IO) {
                roomStore.delete(
                    localUrls = localUrls,
                    cleanupEligible = cleanupEligible
                )
            }
        }.onFailure { error ->
            NPLogger.w(COVER_URL_MAPPER_TAG, "Failed to delete cover URL mappings", error)
        }
    }

    private fun readLegacyMappings(): LegacyMappingLoadResult {
        if (!legacyFile.exists()) return LegacyMappingLoadResult.Missing
        return runCatching {
            val loaded = gson.fromJson<Map<String, String?>>(
                legacyFile.readText(Charsets.UTF_8),
                legacyType
            ).orEmpty()
            val mappings = loaded.mapNotNull { (localUrl, networkUrl) ->
                if (localUrl.isBlank() || networkUrl.isNullOrBlank()) {
                    null
                } else {
                    localUrl to networkUrl
                }
            }.toMap()
            LegacyMappingLoadResult.Loaded(mappings)
        }.getOrElse { error ->
            LegacyMappingLoadResult.Failed(error)
        }
    }
}

private class InMemoryCoverUrlMappingStore(
    initialMappings: Map<String, String>
) : CoverUrlMappingStore {
    private val mappings = ConcurrentHashMap(initialMappings)

    override fun load(): Map<String, String> = mappings.toMap()

    override fun save(localUrl: String, networkUrl: String) {
        mappings[localUrl] = networkUrl
    }

    override fun delete(localUrls: Collection<String>) {
        localUrls.forEach(mappings::remove)
    }
}

private sealed interface LegacyMappingLoadResult {
    data object Missing : LegacyMappingLoadResult

    data class Loaded(val mappings: Map<String, String>) : LegacyMappingLoadResult

    data class Failed(val error: Throwable) : LegacyMappingLoadResult
}
