package moe.ouom.neriplayer.data.github

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
 * File: moe.ouom.neriplayer.data.github/CoverUrlMapper
 * Created: 2025/1/13
 */

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import moe.ouom.neriplayer.util.NPLogger
import java.io.File

/**
 * 封面地址映射管理器
 * 维护本地地址和网络地址的映射关系
 * 用于同步时将本地地址转换为网络地址
 */
class CoverUrlMapper private constructor(private val context: Context) {

    private val gson = Gson()
    private val file: File = File(context.filesDir, "cover_url_mapping.json")

    // 本地地址 -> 网络地址的映射
    private val mapping = mutableMapOf<String, String>()

    init {
        loadFromDisk()
    }

    private fun loadFromDisk() {
        try {
            if (file.exists()) {
                val type = object : TypeToken<Map<String, String>>() {}.type
                val loaded = gson.fromJson<Map<String, String>>(file.readText(), type)
                if (loaded != null) {
                    mapping.putAll(loaded)
                    NPLogger.d(TAG, "Loaded ${mapping.size} cover URL mappings")
                }
            }
        } catch (e: Exception) {
            NPLogger.e(TAG, "Failed to load cover URL mappings", e)
        }
    }

    private fun saveToDisk() {
        try {
            val json = gson.toJson(mapping)
            val parent = file.parentFile ?: context.filesDir
            val tmp = File(parent, file.name + ".tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) {
                file.writeText(json)
                tmp.delete()
            }
        } catch (e: Exception) {
            NPLogger.e(TAG, "Failed to save cover URL mappings", e)
        }
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
        saveToDisk()
        NPLogger.d(TAG, "Saved cover mapping: $localUrl -> $networkUrl")
    }

    /**
     * 获取网络地址
     * @param url 可能是本地地址或网络地址
     * @return 如果是本地地址且有映射，返回网络地址；否则返回原地址
     */
    fun getNetworkUrl(url: String?): String? {
        if (url.isNullOrBlank()) return url
        if (!isLocalUrl(url)) return url

        return mapping[url] ?: url
    }

    /**
     * 判断是否是本地地址
     */
    private fun isLocalUrl(url: String): Boolean {
        return url.startsWith("/") ||
               url.startsWith("file://") ||
               url.contains("/data/") ||
               url.contains("/storage/")
    }

    /**
     * 清理无效的映射（本地文件已不存在）
     */
    fun cleanupInvalidMappings() {
        val toRemove = mutableListOf<String>()
        for ((localUrl, _) in mapping) {
            val file = File(localUrl.removePrefix("file://"))
            if (!file.exists()) {
                toRemove.add(localUrl)
            }
        }

        if (toRemove.isNotEmpty()) {
            toRemove.forEach { mapping.remove(it) }
            saveToDisk()
            NPLogger.d(TAG, "Cleaned up ${toRemove.size} invalid mappings")
        }
    }

    companion object {
        private const val TAG = "CoverUrlMapper"

        @Volatile
        private var instance: CoverUrlMapper? = null

        fun getInstance(context: Context): CoverUrlMapper {
            return instance ?: synchronized(this) {
                instance ?: CoverUrlMapper(context.applicationContext).also { instance = it }
            }
        }
    }
}
