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
 * File: moe.ouom.neriplayer.core.api.youtube/YouTubePlayerScriptStore
 * Created: 2026/7/27
 */

import android.content.Context
import moe.ouom.neriplayer.core.logging.NPLogger
import java.io.File
import java.security.MessageDigest
import java.util.Locale

private const val PLAYER_SCRIPT_STORE_DIRECTORY = "youtube/player_js"
private const val PLAYER_SCRIPT_FILE_SUFFIX = ".js"

/** 留几份就够回退到上一版 player.js, 再多只是占地方 */
internal const val PLAYER_SCRIPT_CACHE_KEEP_COUNT = 3

/** 超过这个岁数的脚本对应的播放器版本早就下线了 */
internal const val PLAYER_SCRIPT_CACHE_MAX_AGE_MS: Long = 7L * 24L * 60L * 60L * 1000L

/** 空文件或明显不是脚本的残留一律当没缓存, 免得把损坏内容喂进 JS 沙箱 */
internal const val PLAYER_SCRIPT_MIN_LENGTH = 1024

/**
 * player.js 的地址自带版本哈希, 同一个地址的内容不会变
 *
 * 所以按地址取摘要当文件名, 命中就是命中, 不需要再校验新鲜度
 */
internal fun youTubePlayerScriptCacheKey(playerJsUrl: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(playerJsUrl.trim().toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { byte -> "%02x".format(Locale.US, byte) }
}

internal data class PlayerScriptCacheEntry(
    val name: String,
    val lastModifiedMs: Long
)

/**
 * 挑出该删的缓存文件
 *
 * 先按过期剔除, 剩下的保留最近用过的几份; 分开成纯函数是为了不用真建文件就能测清楚边界
 */
internal fun selectYouTubePlayerScriptEvictions(
    entries: List<PlayerScriptCacheEntry>,
    nowMs: Long,
    keepCount: Int = PLAYER_SCRIPT_CACHE_KEEP_COUNT,
    maxAgeMs: Long = PLAYER_SCRIPT_CACHE_MAX_AGE_MS
): List<PlayerScriptCacheEntry> {
    val (expired, alive) = entries.partition { entry ->
        nowMs - entry.lastModifiedMs >= maxAgeMs
    }
    val surplus = alive
        .sortedByDescending { it.lastModifiedMs }
        .drop(keepCount.coerceAtLeast(0))
    return expired + surplus
}

/**
 * 把 player.js 留到下次冷启动
 *
 * 求解 sig/n 之前必须先拿到整份脚本, 约 2MB; 只存内存的话每次进程重建都要重新下一遍,
 * 而首播恰好卡在这一步
 */
internal class YouTubePlayerScriptStore(context: Context) {
    companion object {
        private const val TAG = "YouTubePlayerScriptStore"
    }

    private val storeDirectory: File by lazy {
        File(context.filesDir, PLAYER_SCRIPT_STORE_DIRECTORY)
    }

    fun read(playerJsUrl: String): String? {
        return runCatching {
            val file = fileFor(playerJsUrl)
            if (!file.exists()) {
                return@runCatching null
            }
            val script = file.readText()
            if (script.length < PLAYER_SCRIPT_MIN_LENGTH) {
                file.delete()
                return@runCatching null
            }
            // 命中时间戳要往前推, 淘汰时才知道哪份还在用
            file.setLastModified(System.currentTimeMillis())
            script
        }.onFailure { error ->
            NPLogger.w(TAG, "read failed: ${error.message}")
        }.getOrNull()
    }

    fun write(playerJsUrl: String, script: String) {
        if (script.length < PLAYER_SCRIPT_MIN_LENGTH) {
            return
        }
        runCatching {
            storeDirectory.mkdirs()
            val file = fileFor(playerJsUrl)
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(script)
            if (!tmp.renameTo(file)) {
                file.writeText(script)
                tmp.delete()
            }
            prune()
        }.onFailure { error ->
            NPLogger.w(TAG, "write failed: ${error.message}")
        }
    }

    private fun prune() {
        val files = storeDirectory.listFiles()?.filter { it.name.endsWith(PLAYER_SCRIPT_FILE_SUFFIX) }
            ?: return
        val evictions = selectYouTubePlayerScriptEvictions(
            entries = files.map { PlayerScriptCacheEntry(it.name, it.lastModified()) },
            nowMs = System.currentTimeMillis()
        ).map { it.name }.toSet()
        files.filter { it.name in evictions }.forEach { file -> file.delete() }
    }

    private fun fileFor(playerJsUrl: String): File {
        return File(
            storeDirectory,
            "${youTubePlayerScriptCacheKey(playerJsUrl)}$PLAYER_SCRIPT_FILE_SUFFIX"
        )
    }
}
