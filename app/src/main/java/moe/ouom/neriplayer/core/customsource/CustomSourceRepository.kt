package moe.ouom.neriplayer.core.customsource

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
 * File: moe.ouom.neriplayer.core.customsource/CustomSourceRepository
 * Created: 2026/7/26
 *
 * 自定义音源脚本的持久化:脚本文件存 filesDir/custom_sources,
 * 元数据索引存单个 index.json。
 */

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.logging.NPLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "NERI-CustomSourceRepo"
private const val DIR_NAME = "custom_sources"
private const val INDEX_FILE = "index.json"

class CustomSourceRepository(private val appContext: Context) {

    private val mutex = Mutex()
    private val baseDir: File by lazy {
        File(appContext.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }
    }
    private val indexFile: File get() = File(baseDir, INDEX_FILE)

    private val _sources = MutableStateFlow<List<CustomAudioSource>>(emptyList())
    val sources: StateFlow<List<CustomAudioSource>> = _sources.asStateFlow()

    /**
     * 优先模式:true = 自定义音源优先(解析成功即用,失败回退官方);
     * false = 仅作回退(官方拿不到/试听片段时才用)。默认 false。
     */
    private val _priorityMode = MutableStateFlow(false)
    val priorityMode: StateFlow<Boolean> = _priorityMode.asStateFlow()

    private val modeFile: File get() = File(baseDir, "mode")

    /** 当前启用的音源(0 或 1 个)。 */
    val activeSource: CustomAudioSource? get() = _sources.value.firstOrNull { it.enabled }

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _sources.value = readIndex()
            _priorityMode.value = runCatching {
                modeFile.exists() && modeFile.readText().trim() == "priority"
            }.getOrDefault(false)
        }
    }

    suspend fun setPriorityMode(priority: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { modeFile.writeText(if (priority) "priority" else "fallback") }
            _priorityMode.value = priority
        }
    }

    fun readScript(source: CustomAudioSource): String? {
        val f = File(baseDir, source.scriptFileName)
        return if (f.exists()) f.readText() else null
    }

    /**
     * 导入一个脚本。返回导入后的音源(含解析元数据),失败抛异常。
     * [supportedSources] 可由调用方在运行脚本 inited 后回填。
     */
    suspend fun importScript(
        scriptContent: String,
        supportedSources: Map<String, List<String>> = emptyMap()
    ): CustomAudioSource = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(scriptContent.isNotBlank()) { "脚本内容为空" }
            val meta = CustomSourceMetadataParser.parse(scriptContent)
            val id = "cs_" + Integer.toHexString(scriptContent.hashCode()) + "_" + (scriptContent.length)
            val fileName = "$id.js"
            File(baseDir, fileName).writeText(scriptContent)

            val existing = readIndex().toMutableList()
            // 已存在同 id 则覆盖
            existing.removeAll { it.id == id }
            val source = CustomAudioSource(
                id = id,
                name = meta.name,
                version = meta.version,
                author = meta.author,
                description = meta.description,
                scriptFileName = fileName,
                supportedSources = supportedSources,
                enabled = existing.none { it.enabled }, // 若当前没有启用的,则默认启用新导入的
                importedAt = System.currentTimeMillis()
            )
            existing.add(source)
            writeIndex(existing)
            _sources.value = existing
            source
        }
    }

    suspend fun updateSupportedSources(id: String, supportedSources: Map<String, List<String>>) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val list = readIndex().map {
                    if (it.id == id) it.copy(supportedSources = supportedSources) else it
                }
                writeIndex(list)
                _sources.value = list
            }
        }

    /** 启用某个音源(同时禁用其它,保证至多一个启用)。传 null 表示全部禁用。 */
    suspend fun setEnabled(id: String?) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = readIndex().map { it.copy(enabled = it.id == id) }
            writeIndex(list)
            _sources.value = list
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = readIndex()
            list.firstOrNull { it.id == id }?.let { target ->
                runCatching { File(baseDir, target.scriptFileName).delete() }
            }
            val remaining = list.filter { it.id != id }
            writeIndex(remaining)
            _sources.value = remaining
        }
    }

    private fun readIndex(): List<CustomAudioSource> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(indexFile.readText())
            val out = ArrayList<CustomAudioSource>(arr.length())
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { out.add(CustomAudioSource.fromJson(it)) }
            }
            out
        } catch (e: Exception) {
            NPLogger.e(TAG, "读取音源索引失败", e)
            emptyList()
        }
    }

    private fun writeIndex(list: List<CustomAudioSource>) {
        try {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            indexFile.writeText(arr.toString())
        } catch (e: Exception) {
            NPLogger.e(TAG, "写入音源索引失败", e)
        }
    }
}
