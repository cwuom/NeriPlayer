package moe.ouom.neriplayer.util

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
 * File: moe.ouom.neriplayer.util/JsonUtil
 * Created: 2025/8/10
 */

internal object JsonUtil {
    fun toJson(map: Map<String, Any>): String {
        val sb = StringBuilder()
        sb.append("{")
        val it = map.entries.iterator()
        while (it.hasNext()) {
            val (k, v) = it.next()
            sb.append("\"").append(k).append("\":")
            sb.append(toJsonValue(v))
            if (it.hasNext()) sb.append(",")
        }
        sb.append("}")
        return sb.toString()
    }

    private fun toJsonValue(v: Any?): String = when (v) {
        null -> "null"
        is String -> "\"${v.replace("\"", "\\\"")}\""
        is Number, is Boolean -> v.toString()
        is Map<*, *> -> toJson(v as Map<String, Any>)
        is List<*> -> v.joinToString(prefix = "[", postfix = "]") { toJsonValue(it) }
        else -> "\"${v.toString().replace("\"", "\\\"")}\""
    }
}