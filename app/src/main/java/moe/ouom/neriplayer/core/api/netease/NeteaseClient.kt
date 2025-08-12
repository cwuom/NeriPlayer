package moe.ouom.neriplayer.core.api.netease

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
 * File: moe.ouom.neriplayer.core.api.netease/NeteaseClient
 * Created: 2025/8/10
 */

import moe.ouom.neriplayer.util.JsonUtil.jsonQuote
import moe.ouom.neriplayer.util.NPLogger
import moe.ouom.neriplayer.util.readBytesCompat
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.brotli.dec.BrotliInputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.GZIPInputStream

class NeteaseClient {
    private val okHttpClient: OkHttpClient
    private val cookieStore: MutableMap<String, MutableList<Cookie>> = mutableMapOf()

    @Volatile
    private var persistedCookies: Map<String, String> = emptyMap()

    init {
        okHttpClient = OkHttpClient.Builder()
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    val host = url.host
                    val list = cookieStore.getOrPut(host) { mutableListOf() }
                    list.removeAll { c -> cookies.any { it.name == c.name } }
                    list.addAll(cookies)
                }

                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    return cookieStore[url.host] ?: emptyList()
                }
            })
            .build()
    }

    /** 是否已登录 */
    fun hasLogin(): Boolean = !persistedCookies["MUSIC_U"].isNullOrBlank()

    /** 设置/更新持久化 Cookie，并把它们注入到本实例的 CookieJar */
    fun setPersistedCookies(cookies: Map<String, String>) {
        val m = cookies.toMutableMap()
        m.putIfAbsent("os", "pc")
        m.putIfAbsent("appver", "8.10.35")
        persistedCookies = m.toMap()

        // 把持久化 Cookie 注入到运行期 CookieJar，便于本实例读取 __csrf 等
        seedCookieJarFromPersisted("music.163.com")
        seedCookieJarFromPersisted("interface.music.163.com")
    }

    private fun seedCookieJarFromPersisted(host: String) {
        val list = cookieStore.getOrPut(host) { mutableListOf() }
        persistedCookies.forEach { (name, value) ->
            val c = Cookie.Builder()
                .name(name)
                .value(value)
                .domain(host)    // 域 Cookie
                .path("/")
                .build()
            list.removeAll { it.name == name }
            list.add(c)
        }
    }

    fun getCookies(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        cookieStore.values.forEach { list -> list.forEach { cookie -> result[cookie.name] = cookie.value } }
        return result
    }

    fun logout() {
        cookieStore.clear()
    }

    private fun getCookie(name: String): String? {
        cookieStore.values.forEach { list -> list.firstOrNull { it.name == name }?.let { return it.value } }
        return null
    }

    private fun buildPersistedCookieHeader(): String? {
        val map = persistedCookies.toMutableMap()
        map.putIfAbsent("os", "pc")
        map.putIfAbsent("appver", "8.10.35")
        if (map.isEmpty()) return null
        return map.entries.joinToString("; ") { (k, v) -> "$k=$v" }
    }

    /** 访问一次站点首页，通常会下发 __csrf 等 Cookie */
    @Throws(IOException::class)
    fun ensureWeapiSession() {
        request(
            url = "https://music.163.com/",
            params = emptyMap(),
            mode = CryptoMode.API,
            method = "GET",
            usePersistedCookies = true
        )
    }

    @Throws(IOException::class)
    fun request(
        url: String,
        params: Map<String, Any>,
        mode: CryptoMode = CryptoMode.WEAPI,
        method: String = "POST",
        usePersistedCookies: Boolean = true
    ): String {
        val requestUrl = url.toHttpUrl()

        NPLogger.d("NERI-NeteaseClient", "call $url, $method, $mode")
        val bodyParams: Map<String, String> = when (mode) {
            CryptoMode.WEAPI -> NeteaseCrypto.weApiEncrypt(params)
            CryptoMode.EAPI  -> NeteaseCrypto.eApiEncrypt(requestUrl.encodedPath, params)
            CryptoMode.LINUX -> NeteaseCrypto.linuxApiEncrypt(params)
            CryptoMode.API   -> params.mapValues { it.value.toString() }
        }

        var reqUrl = requestUrl
        val builder = Request.Builder()
            .header("Accept", "*/*")
            .header("Accept-Language", "zh-CN,zh-Hans;q=0.9")
            .header("Connection", "keep-alive")
            .header("Referer", "https://music.163.com")
            .header("Host", requestUrl.host)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; NeriPlayer) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")

        if (usePersistedCookies) {
            buildPersistedCookieHeader()?.let { builder.header("Cookie", it) }
        }

        // WEAPI 的 csrf_token 优先用持久化 Cookie，再回退本地 CookieJar
        if (mode == CryptoMode.WEAPI) {
            val csrf = persistedCookies["__csrf"] ?: getCookie("__csrf") ?: ""
            reqUrl = requestUrl.newBuilder()
                .setQueryParameter("csrf_token", csrf)
                .build()
        }

        builder.url(reqUrl)

        when (method.uppercase(Locale.getDefault())) {
            "POST" -> {
                val formBodyBuilder = FormBody.Builder(StandardCharsets.UTF_8)
                bodyParams.forEach { (k, v) -> formBodyBuilder.add(k, v) }
                builder.post(formBodyBuilder.build())
            }
            "GET" -> {
                val urlBuilder = reqUrl.newBuilder()
                bodyParams.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }
                builder.url(urlBuilder.build())
            }
            else -> throw IllegalArgumentException("不支持的请求方法: $method")
        }

        okHttpClient.newCall(builder.build()).execute().use { resp ->
            val responseBody = resp.body
            val encoding = resp.header("Content-Encoding")?.lowercase(Locale.getDefault())
            val bytes = when (encoding) {
                "br"   -> BrotliInputStream(responseBody.byteStream()).use { it.readBytesCompat() }
                "gzip" -> GZIPInputStream(responseBody.byteStream()).use { it.readBytesCompat() }
                else   -> responseBody.bytes()
            }
            return String(bytes, StandardCharsets.UTF_8)
        }
    }

    @Throws(IOException::class)
    fun callWeApi(path: String, params: Map<String, Any>, usePersistedCookies: Boolean = true): String {
        val p = if (path.startsWith("/")) path else "/$path"
        val url = "https://music.163.com/weapi$p"
        return request(url, params, CryptoMode.WEAPI, "POST", usePersistedCookies)
    }

    @Throws(IOException::class)
    fun callEApi(path: String, params: Map<String, Any>, usePersistedCookies: Boolean = true): String {
        val p = if (path.startsWith("/")) path else "/$path"
        val url = "https://interface.music.163.com/eapi$p"
        return request(url, params, CryptoMode.EAPI, "POST", usePersistedCookies)
    }

    @Throws(IOException::class)
    fun callLinuxApi(path: String, params: Map<String, Any>, usePersistedCookies: Boolean = true): String {
        val p = if (path.startsWith("/")) path else "/$path"
        val url = "https://music.163.com/api$p"
        return request(url, params, CryptoMode.LINUX, "POST", usePersistedCookies)
    }

    // 认证相关
    @Throws(IOException::class)
    fun loginByPhone(phone: String, password: String, countryCode: Int = 86, remember: Boolean = true): String {
        val params = mutableMapOf<String, Any>(
            "phone" to phone,
            "countrycode" to countryCode,
            "remember" to remember.toString(),
            "password" to NeteaseCrypto.md5Hex(password),
            "type" to "1"
        )
        return callEApi("/w/login/cellphone", params, usePersistedCookies = false)
    }

    @Throws(IOException::class)
    fun sendCaptcha(phone: String, ctcode: Int = 86): String {
        val url = "https://interface.music.163.com/weapi/sms/captcha/sent"
        val params = mapOf("cellphone" to phone, "ctcode" to ctcode.toString())
        return request(url, params, CryptoMode.WEAPI, "POST", usePersistedCookies = false)
    }

    @Throws(IOException::class)
    fun verifyCaptcha(phone: String, captcha: String, ctcode: Int = 86): String {
        val url = "https://interface.music.163.com/weapi/sms/captcha/verify"
        val params = mapOf("cellphone" to phone, "captcha" to captcha, "ctcode" to ctcode.toString())
        return request(url, params, CryptoMode.WEAPI, "POST", usePersistedCookies = false)
    }

    @Throws(IOException::class)
    fun loginByCaptcha(phone: String, captcha: String, ctcode: Int = 86, remember: Boolean = true): String {
        val params = mutableMapOf<String, Any>(
            "phone" to phone,
            "countrycode" to ctcode.toString(),
            "remember" to remember.toString(),
            "type" to "1",
            "captcha" to captcha
        )
        return callEApi("/w/login/cellphone", params, usePersistedCookies = false)
    }

    // 业务接口
    @Throws(IOException::class)
    fun getRecommendedPlaylists(limit: Int = 30): String {
        val url = "https://music.163.com/weapi/personalized/playlist"
        val params = mapOf("limit" to limit.toString())
        return request(url, params, CryptoMode.WEAPI, "POST", usePersistedCookies = true)
    }

    @Throws(IOException::class)
    fun searchSongs(keyword: String, limit: Int = 30, offset: Int = 0, type: Int = 1): String {
        val url = "https://music.163.com/weapi/cloudsearch/get/web"
        val params = mutableMapOf<String, Any>(
            "s" to keyword,
            "type" to type.toString(),
            "limit" to limit.toString(),
            "offset" to offset.toString(),
            "total" to "true"
        )
        return request(url, params, CryptoMode.WEAPI, "POST", usePersistedCookies = true)
    }

    /** 如果已登录但拿不到 URL，先预热拿 __csrf 再重试一次 */
    @Throws(IOException::class)
    fun getSongDownloadUrl(songId: Long, bitrate: Int = 320000, level: String = "lossless"): String {
        fun call(): String {
            val url = "https://music.163.com/weapi/song/enhance/download/url"
            val params = mutableMapOf<String, Any>(
                "id" to songId.toString(),
                "br" to bitrate.toString(),
                "level" to level
            )
            return request(url, params, CryptoMode.WEAPI, "POST", usePersistedCookies = true)
        }

        var resp = call()
        return try {
            val code = JSONObject(resp).optInt("code", -1)
            if (code == 301 && hasLogin()) {
                try { ensureWeapiSession() } catch (_: Exception) {}
                resp = call()
            }
            resp
        } catch (_: Exception) {
            resp
        }
    }

    @Throws(IOException::class)
    fun getSongUrl(songId: Long, bitrate: Int = 320000): String {
        val url = "https://music.163.com/weapi/song/enhance/player/url"
        val params = mutableMapOf<String, Any>(
            "ids" to "[$songId]",
            "br" to bitrate.toString()
        )
        return request(url, params, CryptoMode.WEAPI, "POST", usePersistedCookies = true)
    }

    @Throws(IOException::class)
    fun getUserPlaylists(userId: Long, offset: Int = 0, limit: Int = 30): String {
        val url = "https://music.163.com/weapi/user/playlist"
        val params = mutableMapOf<String, Any>(
            "uid" to userId.toString(),
            "offset" to offset.toString(),
            "limit" to limit.toString(),
            "includeVideo" to "true"
        )
        return request(url, params, CryptoMode.WEAPI, "POST", usePersistedCookies = true)
    }

    @Throws(IOException::class)
    fun getPlaylistDetail(playlistId: Long, n: Int = 100000, s: Int = 8): String {
        val url = "https://music.163.com/api/v6/playlist/detail"
        val params = mutableMapOf<String, Any>(
            "id" to playlistId.toString(),
            "n" to n.toString(),
            "s" to s.toString()
        )
        return request(url, params, CryptoMode.API, "POST", usePersistedCookies = true)
    }

    @Throws(IOException::class)
    fun getRelatedPlaylists(playlistId: Long): String {
        return try {
            val html = request(
                url = "https://music.163.com/playlist",
                params = mapOf("id" to playlistId.toString()),
                mode = CryptoMode.API,
                method = "GET",
                usePersistedCookies = true
            )

            val regex = Regex(
                pattern = """<div class="cver u-cover u-cover-3">[\s\S]*?<img src="([^"]+)">[\s\S]*?<a class="sname f-fs1 s-fc0" href="([^"]+)"[^>]*>([^<]+?)</a>[\s\S]*?<a class="nm nm f-thide s-fc3" href="([^"]+)"[^>]*>([^<]+?)</a>""",
                options = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
            )

            val items = mutableListOf<String>()

            for (m in regex.findAll(html)) {
                val coverRaw = m.groupValues[1]
                val cover = coverRaw.replace(Regex("""\?param=\d+y\d+$"""), "")
                val playlistHref = m.groupValues[2]
                val playlistName = m.groupValues[3]
                val userHref = m.groupValues[4]
                val nickname = m.groupValues[5]

                val playlistIdStr = playlistHref.removePrefix("/playlist?id=")
                val userIdStr = userHref.removePrefix("/user/home?id=")

                val itemJson = """
                    {
                      "creator": { "userId": ${jsonQuote(userIdStr)}, "nickname": ${jsonQuote(nickname)} },
                      "coverImgUrl": ${jsonQuote(cover)},
                      "name": ${jsonQuote(playlistName)},
                      "id": ${jsonQuote(playlistIdStr)}
                    }
                """.trimIndent()
                items.add(itemJson)
            }

            """
                { "code": 200, "playlists": [${items.joinToString(",")}] }
            """.trimIndent()
        } catch (e: Exception) {
            """{ "code": 500, "msg": ${jsonQuote(e.stackTraceToString())} }"""
        }
    }

    @Throws(IOException::class)
    fun getHighQualityTags(): String {
        val url = "https://music.163.com/api/playlist/highquality/tags"
        val params = emptyMap<String, Any>()
        NPLogger.d("NERI-Netease", "getHighQualityTags calling")
        return request(url, params, CryptoMode.WEAPI, "POST", usePersistedCookies = true)
    }

    @Throws(IOException::class)
    fun getHighQualityPlaylists(
        cat: String = "全部",
        limit: Int = 50,
        before: Long = 0L
    ): String {
        val params = mapOf<String, Any>(
            "cat" to cat,
            "limit" to limit,
            "lasttime" to before,
            "total" to true
        )
        return callWeApi("/playlist/highquality/list", params, usePersistedCookies = true)
    }

    /**
     * 获取用户创建的歌单
     * @param userId 用户 ID；传 0 时自动使用当前登录用户 ID
     * @param offset 偏移量，分页用
     * @param limit  每页返回数量
     */
    @Throws(IOException::class)
    fun getUserCreatedPlaylists(userId: Long, offset: Int = 0, limit: Int = 1000): String {
        val uid = if (userId == 0L) getCurrentUserId() else userId
        val raw = getUserPlaylists(uid, offset, limit)
        return try {
            val root = JSONObject(raw)
            val code = root.optInt("code", 200)
            val list = root.optJSONArray("playlist") ?: JSONArray()
            val created = JSONArray()
            for (i in 0 until list.length()) {
                val pl = list.optJSONObject(i) ?: continue
                val subscribed = pl.optBoolean("subscribed", false)
                val creatorId = pl.optJSONObject("creator")?.optLong("userId") ?: -1L
                if (creatorId == uid || !subscribed) {
                    created.put(pl)
                }
            }
            JSONObject().apply {
                put("code", code)
                put("playlist", created)
                put("count", created.length())
            }.toString()
        } catch (e: Exception) {
            """{ "code": 500, "msg": ${jsonQuote(e.message ?: "parse error")} }"""
        }
    }

    /**
     * 获取用户收藏的歌单
     * @param userId 用户 ID；传 0 时自动使用当前登录用户 ID
     * @param offset 偏移量，分页用
     * @param limit  每页返回数量
     */
    @Throws(IOException::class)
    fun getUserSubscribedPlaylists(userId: Long, offset: Int = 0, limit: Int = 1000): String {
        val uid = if (userId == 0L) getCurrentUserId() else userId
        val raw = getUserPlaylists(uid, offset, limit)
        return try {
            val root = JSONObject(raw)
            val code = root.optInt("code", 200)
            val list = root.optJSONArray("playlist") ?: JSONArray()
            val subs = JSONArray()
            for (i in 0 until list.length()) {
                val pl = list.optJSONObject(i) ?: continue
                if (pl.optBoolean("subscribed", false)) subs.put(pl)
            }
            JSONObject().apply {
                put("code", code)
                put("playlist", subs)
                put("count", subs.length())
            }.toString()
        } catch (e: Exception) {
            """{ "code": 500, "msg": ${jsonQuote(e.message ?: "parse error")} }"""
        }
    }

    /**
     * 获取“我喜欢的音乐”歌单 ID
     * @param userId 用户 ID；传 0 时自动使用当前登录用户 ID
     */
    @Throws(IOException::class)
    fun getLikedPlaylistId(userId: Long): String {
        val uid = if (userId == 0L) getCurrentUserId() else userId
        val raw = getUserPlaylists(uid, 0, 1000)
        return try {
            val root = JSONObject(raw)
            val list = root.optJSONArray("playlist") ?: JSONArray()
            var likedId: Long? = null
            for (i in 0 until list.length()) {
                val pl = list.optJSONObject(i) ?: continue
                val specialType = pl.optInt("specialType", 0)
                val name = pl.optString("name", "")
                val creatorId = pl.optJSONObject("creator")?.optLong("userId") ?: -1L
                if (creatorId == uid && (specialType == 5 || name.contains("我喜欢的音乐"))) {
                    likedId = pl.optLong("id")
                    break
                }
            }
            if (likedId != null) {
                """{ "code": 200, "playlistId": $likedId }"""
            } else {
                """{ "code": 404, "msg": "liked playlist not found" }"""
            }
        } catch (e: Exception) {
            """{ "code": 500, "msg": ${jsonQuote(e.message ?: "parse error")} }"""
        }
    }

    /**
     * 获取用户喜欢的所有歌曲 ID
     * @param userId 用户 ID；传 0 时自动使用当前登录用户 ID
     */
    @Throws(IOException::class)
    fun getUserLikedSongIds(userId: Long): String {
        val uid = if (userId == 0L) getCurrentUserId() else userId
        val url = "https://music.163.com/weapi/song/like/get"
        val params = mapOf("uid" to uid.toString())
        return request(url, params, CryptoMode.WEAPI, "POST", usePersistedCookies = true)
    }

    /**
     * 喜欢/取消喜欢一首歌
     * @param songId 歌曲 ID
     * @param like   是否喜欢（true=喜欢, false=取消喜欢）
     * @param time   可选参数，时间戳
     */
    @Throws(IOException::class)
    fun likeSong(songId: Long, like: Boolean = true, time: Long? = null): String {
        val params = mutableMapOf<String, Any>(
            "trackId" to songId.toString(),
            "like" to like.toString()
        )
        time?.let { params["time"] = it.toString() }
        return callWeApi("/song/like", params, usePersistedCookies = true)
    }

    /**
     * 获取当前登录用户的账户信息（包含 userId）
     */
    @Throws(IOException::class)
    fun getCurrentUserAccount(): String {
        return callWeApi("/w/nuser/account/get", emptyMap(), usePersistedCookies = true)
    }

    /**
     * 获取当前登录用户的 userId
     */
    @Throws(IOException::class)
    fun getCurrentUserId(): Long {
        val raw = getCurrentUserAccount()
        val root = JSONObject(raw)
        if (root.optInt("code", -1) != 200) {
            throw IllegalStateException("获取用户信息失败: $raw")
        }
        val profile = root.optJSONObject("profile")
        return profile?.optLong("userId")
            ?: throw IllegalStateException("未找到 userId: $raw")
    }
}