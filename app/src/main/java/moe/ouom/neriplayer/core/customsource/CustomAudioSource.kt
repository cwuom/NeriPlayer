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
 * File: moe.ouom.neriplayer.core.customsource/CustomAudioSource
 * Created: 2026/7/26
 *
 * 自定义音源(兼容洛雪音乐 LX Music 自定义源脚本)的元数据模型。
 */

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个已导入的自定义音源脚本。
 *
 * [scriptFileName] 指向 filesDir/custom_sources 下的脚本文件,内容为原始 JS。
 */
data class CustomAudioSource(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val scriptFileName: String,
    /** 脚本声明支持的平台(LX source key -> 支持的音质列表)。运行脚本 inited 后回填。 */
    val supportedSources: Map<String, List<String>> = emptyMap(),
    val enabled: Boolean = false,
    val importedAt: Long = 0L
) {
    fun supportsNetease(): Boolean = supportedSources.containsKey(LX_SOURCE_NETEASE)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("version", version)
        put("author", author)
        put("description", description)
        put("scriptFileName", scriptFileName)
        put("enabled", enabled)
        put("importedAt", importedAt)
        val sources = JSONObject()
        supportedSources.forEach { (key, qualities) ->
            sources.put(key, JSONArray(qualities))
        }
        put("supportedSources", sources)
    }

    companion object {
        const val LX_SOURCE_NETEASE = "wy"
        const val LX_SOURCE_TENCENT = "tx"
        const val LX_SOURCE_KUGOU = "kg"
        const val LX_SOURCE_KUWO = "kw"
        const val LX_SOURCE_MIGU = "mg"

        fun fromJson(obj: JSONObject): CustomAudioSource {
            val sources = mutableMapOf<String, List<String>>()
            obj.optJSONObject("supportedSources")?.let { s ->
                val keys = s.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val arr = s.optJSONArray(k) ?: JSONArray()
                    val list = ArrayList<String>(arr.length())
                    for (i in 0 until arr.length()) list.add(arr.optString(i))
                    sources[k] = list
                }
            }
            return CustomAudioSource(
                id = obj.optString("id"),
                name = obj.optString("name"),
                version = obj.optString("version"),
                author = obj.optString("author"),
                description = obj.optString("description"),
                scriptFileName = obj.optString("scriptFileName"),
                supportedSources = sources,
                enabled = obj.optBoolean("enabled", false),
                importedAt = obj.optLong("importedAt", 0L)
            )
        }
    }
}

/** 解析脚本头部注释里的元数据(LX 脚本头部形如 /** @name ... @version ... */)。 */
object CustomSourceMetadataParser {
    fun parse(script: String): ParsedMeta {
        val name = extract(script, "name") ?: "自定义音源"
        val version = extract(script, "version") ?: "1.0.0"
        val author = extract(script, "author") ?: ""
        val description = extract(script, "description") ?: ""
        return ParsedMeta(name, version, author, description)
    }

    private fun extract(script: String, key: String): String? {
        // 匹配 "@key value" 直到行尾
        val regex = Regex("@$key[ \\t]+([^\\r\\n*]+)", RegexOption.IGNORE_CASE)
        val head = script.take(4000)
        return regex.find(head)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    data class ParsedMeta(
        val name: String,
        val version: String,
        val author: String,
        val description: String
    )
}
