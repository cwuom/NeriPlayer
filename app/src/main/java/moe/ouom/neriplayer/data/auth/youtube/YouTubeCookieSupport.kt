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
    val webCookieGoogleUrls: List<String> = listOf(
        "https://accounts.google.com",
        "https://www.google.com",
        "https://google.com"
    )

    val webCookieWriteUrls: List<String> = listOf(
        "https://music.youtube.com",
        "https://www.youtube.com",
        "https://m.youtube.com",
        "https://youtube.com"
    )

    val webCookieReadUrls: List<String> = webCookieGoogleUrls + webCookieWriteUrls

    // 兼容现有调用方，默认写入目标仍然只投射到 YouTube 域
    val webUrls: List<String> = webCookieWriteUrls

    val importantLoginCookieKeys: List<String> = listOf(
        "SAPISID",
        "APISID",
        "__Secure-1PAPISID",
        "__Secure-3PAPISID",
        "__Secure-1PSID",
        "__Secure-3PSID",
        "SSID",
        "SID"
    )

    private val importantLoginCookiePrefixes: List<String> = emptyList()

    /**
     * 收到过期指令也不许删的登录 cookie
     *
     * 比 importantLoginCookieKeys 多出 HSID/LSID/LOGIN_INFO: 那份只有 SID 家族, 于是匿名响应
     * 带的 Set-Cookie 过期指令能把这三项删掉并落盘; 而 HSID 和 LOGIN_INFO 正是 youtube.com
     * 判定登录用的, 缺了就一直被判游客, 补回去也会被下一条响应再删一次
     *
     * 单独一份而不是直接扩充 importantLoginCookieKeys, 是因为那份还用来判断"算不算已登录",
     * 把 LOGIN_INFO 算进去会让只剩它时也判成已登录
     */
    val deletionProtectedLoginCookieKeys: Set<String> =
        importantLoginCookieKeys.toSet() + setOf("HSID", "LSID", "LOGIN_INFO")

    val activeSessionCookieKeys: List<String> = listOf(
        "SAPISID",
        "APISID",
        "__Secure-1PAPISID",
        "__Secure-3PAPISID",
        "__Secure-1PSID",
        "__Secure-3PSID",
        "__Secure-1PSIDTS",
        "__Secure-3PSIDTS",
        "__Secure-1PSIDCC",
        "__Secure-3PSIDCC",
        "SSID",
        "SID",
        "SIDCC"
    )

    private val activeSessionCookiePrefixes: List<String> = emptyList()

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
        "CONSENT",
        "GPS",
        "YSC",
        "VISITOR_INFO1_LIVE",
        "VISITOR_PRIVACY_METADATA"
    )

    private val persistedCookiePrefixes: List<String> = listOf(
        "__Secure-1PSID",
        "__Secure-3PSID",
        "__Secure-1PAPISID",
        "__Secure-3PAPISID",
        "__Secure-ROLLOUT_"
    )

    private val webLoginGoogleSeedCookieKeys: Set<String> = setOf(
        "SOCS",
        "CONSENT",
        "PREF"
    )

    private val webLoginYouTubeSeedCookieKeys: Set<String> = setOf(
        "SOCS",
        "CONSENT",
        "PREF",
        "VISITOR_INFO1_LIVE",
        "VISITOR_PRIVACY_METADATA"
    )

    private val webLoginBlockingCookieKeys: Set<String> = setOf(
        "SID",
        "HSID",
        "SSID",
        "APISID",
        "SAPISID",
        "LSID",
        "LOGIN_INFO",
        "SIDCC"
    )

    private val webLoginBlockingCookiePrefixes: List<String> = listOf(
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
            if (matchesCookieKey(key, persistedCookieKeys, persistedCookiePrefixes)) {
                sanitized[key] = value
            }
        }
        return sanitized
    }

    fun sanitizeWebLoginGoogleSeedCookies(
        cookies: Map<String, String>
    ): LinkedHashMap<String, String> {
        return sanitizeCookiesByExactKeys(cookies, webLoginGoogleSeedCookieKeys)
    }

    fun sanitizeWebLoginYouTubeSeedCookies(
        cookies: Map<String, String>
    ): LinkedHashMap<String, String> {
        return sanitizeCookiesByExactKeys(cookies, webLoginYouTubeSeedCookieKeys)
    }

    fun isLoggedIn(cookies: Map<String, String>): Boolean {
        return collectImportantLoginCookieKeys(cookies).isNotEmpty()
    }

    fun hasUsefulRequestCookies(rawCookieHeader: String): Boolean {
        if (rawCookieHeader.isBlank()) {
            return false
        }
        return isLoggedIn(parseCookieString(rawCookieHeader))
    }

    fun collectImportantLoginCookieKeys(cookies: Map<String, String>): List<String> {
        return collectMatchingCookieKeys(
            cookies = cookies,
            exactKeys = importantLoginCookieKeys,
            prefixes = importantLoginCookiePrefixes
        )
    }

    fun collectActiveSessionCookieKeys(cookies: Map<String, String>): List<String> {
        return collectMatchingCookieKeys(
            cookies = cookies,
            exactKeys = activeSessionCookieKeys,
            prefixes = activeSessionCookiePrefixes
        )
    }

    fun collectWebLoginBlockingCookieKeys(cookies: Map<String, String>): List<String> {
        return collectMatchingCookieKeys(
            cookies = cookies,
            exactKeys = webLoginBlockingCookieKeys,
            prefixes = webLoginBlockingCookiePrefixes
        )
    }

    /**
     * 判断这个 cookie 是不是身份凭据
     *
     * HSID 和 LOGIN_INFO 是 HttpOnly, 老存档里可能压根没收录过,
     * 拿存档做差集去清理浏览器就会把仍然有效的身份 cookie 一起抹掉
     */
    fun isIdentityCookieKey(key: String): Boolean {
        val normalized = key.trim()
        if (normalized.isEmpty()) {
            return false
        }
        if (normalized in webLoginBlockingCookieKeys) {
            return true
        }
        return webLoginBlockingCookiePrefixes.any { normalized.startsWith(it) }
    }

    fun collectWebLoginResetCookieKeys(cookies: Map<String, String>): List<String> {
        return cookies.entries
            .asSequence()
            .filter { (key, value) -> key.isNotBlank() && value.isNotBlank() }
            .map { (key, _) -> key }
            .distinct()
            .toList()
    }

    private fun sanitizeCookiesByExactKeys(
        cookies: Map<String, String>,
        exactKeys: Set<String>
    ): LinkedHashMap<String, String> {
        val sanitized = linkedMapOf<String, String>()
        cookies.forEach { (key, value) ->
            if (key.isBlank() || value.isBlank()) {
                return@forEach
            }
            if (key in exactKeys) {
                sanitized[key] = value
            }
        }
        return sanitized
    }

    private fun collectMatchingCookieKeys(
        cookies: Map<String, String>,
        exactKeys: Iterable<String>,
        prefixes: Iterable<String>
    ): List<String> {
        return cookies.entries
            .asSequence()
            .filter { (key, value) ->
                key.isNotBlank() &&
                    value.isNotBlank() &&
                    matchesCookieKey(key, exactKeys, prefixes)
            }
            .map { (key, _) -> key }
            .toList()
    }

    private fun matchesCookieKey(
        key: String,
        exactKeys: Iterable<String>,
        prefixes: Iterable<String>
    ): Boolean {
        return key in exactKeys || prefixes.any { prefix -> key.startsWith(prefix) }
    }
}
