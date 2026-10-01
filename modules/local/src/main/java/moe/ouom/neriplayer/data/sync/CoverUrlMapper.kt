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
import java.util.concurrent.ConcurrentHashMap
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.sync.cover.missingLocalCoverFile
import moe.ouom.neriplayer.data.sync.policy.sanitizeCoverUrlForSync
import moe.ouom.neriplayer.data.sync.cover.CoverUrlMappingStore
import moe.ouom.neriplayer.data.sync.cover.InMemoryCoverUrlMappingStore
import moe.ouom.neriplayer.data.sync.cover.createCoverMappingStore

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
        val normalizedUrl = url?.trim().orEmpty()
        if (normalizedUrl.isBlank()) return null
        val directUrl = sanitizeCoverUrlForSync(normalizedUrl)
        if (directUrl != null) return directUrl
        return sanitizeCoverUrlForSync(mapping[normalizedUrl])
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
        val toRemove = mapping.keys.filter(::missingLocalCoverFile)
        if (toRemove.isEmpty()) return
        toRemove.forEach { mapping.remove(it) }
        store.delete(toRemove)
        NPLogger.d(COVER_URL_MAPPER_TAG, "Cleaned up ${toRemove.size} invalid mappings")
    }

    companion object {
        private const val FILE_NAME = "cover_url_mapping.json"

        @Volatile
        private var instance: CoverUrlMapper? = null

        fun getInstance(context: Context): CoverUrlMapper {
            val appContext = context.applicationContext ?: context
            return instance ?: initialize(appContext)
        }

        private fun initialize(context: Context): CoverUrlMapper = synchronized(this) {
            instance ?: CoverUrlMapper(createCoverMappingStore(context, FILE_NAME)).also { instance = it }
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
