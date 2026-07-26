package moe.ouom.neriplayer.core.api.youtube

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
 * File: moe.ouom.neriplayer.core.api.youtube/YouTubeNewPipeFallbackStore
 * Created: 2026/7/27
 */

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.util.io.writeTextAtomically
import java.io.File

private const val NEWPIPE_FALLBACK_STORE_DIRECTORY = "youtube"
private const val NEWPIPE_FALLBACK_STORE_FILE = "newpipe_fallback.json"

internal const val NEWPIPE_FALLBACK_SNAPSHOT_VERSION = 1

/** player.js 每隔几天就换一版, 旧地址的结论留着也没人问 */
internal const val NEWPIPE_FALLBACK_MAX_ENTRIES = 8

@Serializable
internal data class NewPipeFallbackSnapshot(
    val signature: List<String> = emptyList(),
    val throttling: List<String> = emptyList(),
    val version: Int = NEWPIPE_FALLBACK_SNAPSHOT_VERSION
)

/**
 * 只留最近几条, 新的排在前面
 *
 * 同一个地址重复标记时不该占两个位置, 否则几次刷新就能把有用的旧结论挤掉
 */
internal fun retainRecentNewPipeFallbackKeys(
    existing: List<String>,
    added: String,
    maxEntries: Int = NEWPIPE_FALLBACK_MAX_ENTRIES
): List<String> {
    if (added.isBlank() || maxEntries <= 0) {
        return existing.take(maxEntries.coerceAtLeast(0))
    }
    return (listOf(added) + existing.filter { it != added && it.isNotBlank() })
        .take(maxEntries)
}

/**
 * 把 NewPipe 解不动哪版 player.js 记到下次冷启动
 *
 * 它的反混淆正则对同一个 player.js 版本要么匹配要么不匹配, 没有偶发一说; 只存内存的话
 * 每次冷启动的头几首都要各付一次三秒多的必然失败, 而那几秒整段落在首播上
 */
internal class YouTubeNewPipeFallbackStore(
    context: Context,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
) {
    companion object {
        private const val TAG = "YouTubeNewPipeFallback"
    }

    private val storeFile: File by lazy {
        File(File(context.filesDir, NEWPIPE_FALLBACK_STORE_DIRECTORY), NEWPIPE_FALLBACK_STORE_FILE)
    }

    fun load(): NewPipeFallbackSnapshot? {
        val file = storeFile
        if (!file.exists()) {
            return null
        }
        return runCatching {
            json.decodeFromString<NewPipeFallbackSnapshot>(file.readText())
        }.onFailure { error ->
            NPLogger.w(TAG, "load failed, dropping snapshot: ${error.message}")
            runCatching { file.delete() }
        }.getOrNull()?.takeIf { it.version == NEWPIPE_FALLBACK_SNAPSHOT_VERSION }
    }

    fun save(snapshot: NewPipeFallbackSnapshot) {
        runCatching {
            val file = storeFile
            file.parentFile?.mkdirs()
            file.writeTextAtomically(json.encodeToString(snapshot))
        }.onFailure { error ->
            NPLogger.w(TAG, "save failed: ${error.message}")
        }
    }
}
