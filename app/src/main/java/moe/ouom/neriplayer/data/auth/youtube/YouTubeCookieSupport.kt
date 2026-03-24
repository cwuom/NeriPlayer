package moe.ouom.neriplayer.data.auth.youtube

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
 * File: moe.ouom.neriplayer.data.auth.youtube/YouTubeCookieSupport
 * Created: 2026/3/16
 */

object YouTubeCookieSupport {
    val webUrls: List<String> = listOf(
        "https://music.youtube.com",
        "https://www.youtube.com",
        "https://m.youtube.com",
        "https://youtube.com"
    )

    val importantLoginCookieKeys: List<String> = listOf(
        "SAPISID",
        "APISID",
        "__Secure-1PAPISID",
        "__Secure-3PAPISID",
        "__Secure-1PSID",
        "__Secure-3PSID",
        "SSID",
        "LOGIN_INFO",
        "SID"
    )

    val activeSessionCookieKeys: List<String> = listOf(
        "SAPISID",
        "APISID",
        "__Secure-1PAPISID",
        "__Secure-3PAPISID",
        "__Secure-1PSID",
        "__Secure-3PSID",
        "SSID",
        "SID"
    )

    private val persistedCookieKeys: Set<String> = setOf(
        "SID",
        "HSID",
        "SSID",
        "APISID",
        "SAPISID",
        "LSID",
        "LOGIN_INFO",
        "SIDCC",
        "PREF",
        "SOCS",
        "CONSENT"
    )

    private val persistedCookiePrefixes: List<String> = listOf(
        "__Secure-1PSID",
        "__Secure-3PSID",
        "__Secure-1PAPISID",
        "__Secure-3PAPISID"
    )

    fun parseCookieString(raw: String): LinkedHashMap<String, String> {
        val map = linkedMapOf<String, String>()
        raw.split(';')
            .map { it.trim() }
            .filter { it.isNotBlank() && it.contains('=') }
            .forEach { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) return@forEach
                val key = part.substring(0, idx).trim()
                val value = part.substring(idx + 1).trim()
                if (key.isNotEmpty()) {
                    map[key] = value
                }
            }
        return map
    }

    fun mergeCookieStrings(rawCookies: Iterable<String>): LinkedHashMap<String, String> {
        val merged = linkedMapOf<String, String>()
        rawCookies
            .filter { it.isNotBlank() }
            .forEach { raw -> merged.putAll(parseCookieString(raw)) }
        return merged
    }

    // 仅保留稳定的登录/会话 Cookie，避免把页面级临时 Cookie 写回持久鉴权后污染 YouTube 请求
    fun sanitizePersistedCookies(cookies: Map<String, String>): LinkedHashMap<String, String> {
        val sanitized = linkedMapOf<String, String>()
        cookies.forEach { (key, value) ->
            if (key.isBlank() || value.isBlank()) {
                return@forEach
            }
            if (key in persistedCookieKeys || persistedCookiePrefixes.any { key.startsWith(it) }) {
                sanitized[key] = value
            }
        }
        return sanitized
    }

    fun isLoggedIn(cookies: Map<String, String>): Boolean {
        return importantLoginCookieKeys.any { key -> !cookies[key].isNullOrBlank() }
    }

}
