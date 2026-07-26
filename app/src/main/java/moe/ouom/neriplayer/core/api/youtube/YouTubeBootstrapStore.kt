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
 * File: moe.ouom.neriplayer.core.api.youtube/YouTubeBootstrapStore
 * Created: 2026/7/27
 */

import android.content.Context
import kotlinx.serialization.json.Json
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.util.io.writeTextAtomically
import java.io.File

/** 存档结构变了就整份作废, 拿旧字段拼出来的 bootstrap 只会让首播失败得更难查 */
internal const val BOOTSTRAP_SNAPSHOT_VERSION_CURRENT = 1

private const val BOOTSTRAP_STORE_DIRECTORY = "youtube"
private const val BOOTSTRAP_STORE_FILE = "playback_bootstrap.json"

/**
 * 存档比这还旧就不再拿来垫首播
 *
 * apiKey/visitorData/STS 这些实际能稳定几个小时, 但隔夜的 clientVersion 可能已经被服务端淘汰,
 * 那时候宁可老实重新拉一次也不要拿它去换一串解析失败
 */
internal const val BOOTSTRAP_SNAPSHOT_MAX_AGE_MS: Long = 12L * 60L * 60L * 1000L

internal fun isYouTubeBootstrapSnapshotUsable(
    snapshot: YouTubePlaybackBootstrap?,
    authFingerprint: String,
    nowMs: Long,
    maxAgeMs: Long = BOOTSTRAP_SNAPSHOT_MAX_AGE_MS
): Boolean {
    val candidate = snapshot ?: return false
    if (candidate.version != BOOTSTRAP_SNAPSHOT_VERSION_CURRENT) {
        return false
    }
    // 换了账号的存档不能用, 里面的 sessionIndex/dataSyncId 全是上一个身份的
    if (candidate.authFingerprint.isBlank() || candidate.authFingerprint != authFingerprint) {
        return false
    }
    if (candidate.apiKey.isBlank() || candidate.playerJsUrl.isBlank()) {
        return false
    }
    val ageMs = nowMs - candidate.fetchedAtMs
    return ageMs in 0L until maxAgeMs
}

/**
 * 把解析好的 bootstrap 留到下次冷启动
 *
 * 这份东西要现拉首页再解析上千条 EXPERIMENT_FLAGS, 实测冷启动要十几秒, 而里面每一项
 * 都能稳定几个小时; 只存在内存里等于每次进程重建都重新付一遍, 首播慢的账全记在这
 */
internal class YouTubeBootstrapStore(
    context: Context,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
) {
    companion object {
        private const val TAG = "YouTubeBootstrapStore"
    }

    private val storeFile: File by lazy {
        File(File(context.filesDir, BOOTSTRAP_STORE_DIRECTORY), BOOTSTRAP_STORE_FILE)
    }

    fun load(): YouTubePlaybackBootstrap? {
        val file = storeFile
        if (!file.exists()) {
            return null
        }
        return runCatching {
            json.decodeFromString<YouTubePlaybackBootstrap>(file.readText())
        }.onFailure { error ->
            NPLogger.w(TAG, "load failed, dropping snapshot: ${error.message}")
            runCatching { file.delete() }
        }.getOrNull()?.takeIf { it.version == BOOTSTRAP_SNAPSHOT_VERSION_CURRENT }
    }

    fun save(bootstrap: YouTubePlaybackBootstrap) {
        runCatching {
            val file = storeFile
            file.parentFile?.mkdirs()
            // cookieHeader 是 @Transient, 序列化时不会落盘, 恢复后由当前 auth 重新拼
            file.writeTextAtomically(json.encodeToString(bootstrap))
        }.onFailure { error ->
            NPLogger.w(TAG, "save failed: ${error.message}")
        }
    }

    fun clear() {
        runCatching { storeFile.delete() }
            .onFailure { error -> NPLogger.w(TAG, "clear failed: ${error.message}") }
    }
}
