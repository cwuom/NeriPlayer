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
 * File: moe.ouom.neriplayer.core.customsource/CustomSourceManager
 * Created: 2026/7/26
 *
 * 自定义音源的高层门面:管理引擎生命周期,给播放链路提供 resolveNeteaseUrl,
 * 给 UI 提供导入/启用/删除/测试。
 */

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.model.SongItem
import org.json.JSONObject

private const val TAG = "NERI-CustomSourceMgr"

class CustomSourceManager(
    private val appContext: Context,
    val repository: CustomSourceRepository
) {
    private val engineMutex = Mutex()
    private var engine: LxScriptEngine? = null
    private var engineSourceId: String? = null

    /** 是否存在已启用且支持网易云的音源。 */
    fun hasActiveNeteaseSource(): Boolean {
        val active = repository.activeSource ?: return false
        // 若脚本尚未解析出支持平台(旧数据),也允许尝试
        return active.supportedSources.isEmpty() || active.supportsNetease()
    }

    /**
     * 用启用的自定义音源解析网易云歌曲的播放地址。
     * @param neteaseQualityKey NeriPlayer 的网易云音质 key(standard/exhigh/lossless/hires/...)
     * @return 可播放 URL,失败或无可用音源返回 null
     */
    suspend fun resolveNeteaseUrl(song: SongItem, neteaseQualityKey: String): String? {
        val active = repository.activeSource ?: return null
        val eng = ensureEngine(active) ?: return null

        val lxQuality = mapNeteaseQualityToLx(neteaseQualityKey)
        val musicInfo = buildNeteaseMusicInfo(song)
        return try {
            eng.getMusicUrl(
                source = CustomAudioSource.LX_SOURCE_NETEASE,
                quality = lxQuality,
                musicInfo = musicInfo
            )
        } catch (e: Exception) {
            NPLogger.w(TAG, "自定义音源解析失败", e)
            null
        }
    }

    /**
     * 运行一段脚本并等待其 inited,返回解析出的支持平台。用于导入时探测与"测试"。
     * 使用一次性引擎,用完即销毁。
     */
    suspend fun probeScript(scriptContent: String): LxScriptEngine.InitResult {
        val probe = LxScriptEngine(appContext, scriptContent)
        return try {
            probe.start(timeoutMs = 22_000)
        } finally {
            probe.destroy()
        }
    }

    /**
     * 端到端诊断:用当前启用音源真实解析一首示例网易云歌曲,返回可读结果。
     * 供设置页"测试"按钮使用,无需 logcat 即可看到失败原因。
     */
    suspend fun diagnoseActiveNetease(sampleSongId: Long = 1824045033L): String {
        val active = repository.activeSource ?: return "请先启用一个音源"
        val script = repository.readScript(active)
        if (script.isNullOrBlank()) return "脚本内容缺失"

        val eng = LxScriptEngine(appContext, script)
        return try {
            val init = eng.start()
            if (!init.ok) {
                return "① 初始化失败: ${init.error ?: "未知"}"
            }
            val platforms = init.sources.keys.joinToString(", ").ifBlank { "(无)" }
            if (!init.sources.containsKey(CustomAudioSource.LX_SOURCE_NETEASE) && init.sources.isNotEmpty()) {
                return "① 初始化成功,但脚本声明支持的平台为: $platforms,不含网易云(wy)"
            }
            val musicInfo = JSONObject().apply {
                put("songmid", sampleSongId)
                put("id", sampleSongId)
                put("name", "测试歌曲")
                put("singer", "测试")
                put("albumName", "")
                put("source", CustomAudioSource.LX_SOURCE_NETEASE)
            }
            val result = eng.resolve(
                source = CustomAudioSource.LX_SOURCE_NETEASE,
                quality = "320k",
                musicInfo = musicInfo
            )
            "① 初始化成功(平台: $platforms)\n② 解析示例歌曲: ${result.detail}"
        } catch (e: Exception) {
            "测试异常: ${e.message}"
        } finally {
            eng.destroy()
        }
    }

    /** 启用状态变化后调用:重建/销毁引擎。 */
    suspend fun onActiveSourceChanged() {
        engineMutex.withLock {
            engine?.destroy()
            engine = null
            engineSourceId = null
        }
    }

    private suspend fun ensureEngine(source: CustomAudioSource): LxScriptEngine? {
        engineMutex.withLock {
            if (engine != null && engineSourceId == source.id) return engine
            engine?.destroy()
            engine = null
            engineSourceId = null

            val script = repository.readScript(source)
            if (script.isNullOrBlank()) {
                NPLogger.w(TAG, "音源脚本内容缺失: ${source.id}")
                return null
            }
            val eng = LxScriptEngine(appContext, script)
            val init = eng.start()
            if (!init.ok) {
                NPLogger.w(TAG, "音源引擎启动失败: ${init.error}")
                eng.destroy()
                return null
            }
            engine = eng
            engineSourceId = source.id
            return eng
        }
    }

    private fun buildNeteaseMusicInfo(song: SongItem): JSONObject {
        return JSONObject().apply {
            put("songmid", song.id)
            put("id", song.id)
            put("hash", "")
            put("name", song.name)
            put("singer", song.artist)
            put("albumName", song.album)
            put("source", CustomAudioSource.LX_SOURCE_NETEASE)
            put("albumId", song.albumId)
        }
    }

    companion object {
        /**
         * NeriPlayer 网易云音质 -> LX 音质。
         * LX 常见音质: 128k / 320k / flac / flac24bit / master
         */
        fun mapNeteaseQualityToLx(qualityKey: String): String = when (qualityKey) {
            "standard" -> "128k"
            "exhigh" -> "320k"
            "lossless" -> "flac"
            "hires" -> "flac24bit"
            "jyeffect", "sky", "jymaster" -> "flac24bit"
            else -> "320k"
        }
    }
}
