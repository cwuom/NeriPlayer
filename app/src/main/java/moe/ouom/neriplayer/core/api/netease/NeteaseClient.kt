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

import android.util.Log
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
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * 网易云音乐客户端
 */
class NeteaseClient {
    private val okHttpClient: OkHttpClient
    private val cookieStore: MutableMap<String, MutableList<Cookie>> = mutableMapOf()

    /** 从外部注入的持久化 Cookie，用于非登录请求统一携带 */
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

    /** 设置/更新 持久化 Cookie，供非登录接口统一携带 */
    fun setPersistedCookies(cookies: Map<String, String>) {
        // 始终补齐关键标识，很多接口（尤其歌单详情）需要它们才会返回全量 tracks
        val m = cookies.toMutableMap()
        m.putIfAbsent("os", "pc")
        m.putIfAbsent("appver", "8.10.35")
        persistedCookies = m.toMap()
    }

    /** 获取当前运行时 CookieJar 中的所有 Cookie */
    fun getCookies(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        cookieStore.values.forEach { list ->
            list.forEach { cookie -> result[cookie.name] = cookie.value }
        }
        return result
    }

    /** 清空运行期 Cookie */
    fun logout() {
        cookieStore.clear()
    }

    /** 根据名称获取运行期 Cookie 值 */
    private fun getCookie(name: String): String? {
        cookieStore.values.forEach { list ->
            list.firstOrNull { it.name == name }?.let { return it.value }
        }
        return null
    }

    /** 构建最终要发送的 Cookie 头 */
    private fun buildPersistedCookieHeader(): String? {
        val map = persistedCookies.toMutableMap()
        if (map.isEmpty()) {
            // 即便外部未注入，也补上最关键的两项，提升接口成功率
            map["os"] = "pc"
            map["appver"] = "8.10.35"
        } else {
            map.putIfAbsent("os", "pc")
            map.putIfAbsent("appver", "8.10.35")
        }
        if (map.isEmpty()) return null
        return map.entries.joinToString("; ") { (k, v) -> "$k=$v" }
    }

    /**
     * 发送统一请求
     *
     * @param url 完整请求地址
     * @param params 未加密的请求参数
     * @param mode 加密模式
     * @param method "POST" / "GET"
     * @param usePersistedCookies 是否携带持久化 Cookie
     */
    @Throws(IOException::class)
    fun request(
        url: String,
        params: Map<String, Any>,
        mode: CryptoMode = CryptoMode.WEAPI,
        method: String = "POST",
        usePersistedCookies: Boolean = true
    ): String {
        val requestUrl = url.toHttpUrl()

        NPLogger.d("NERI-NeteaseClient", "call ${url}, ${method}, $mode")
        // 按模式加/不加密
        val bodyParams: Map<String, String> = when (mode) {
            CryptoMode.WEAPI -> NeteaseCrypto.weApiEncrypt(params)              // /weapi
            CryptoMode.EAPI  -> NeteaseCrypto.eApiEncrypt(requestUrl.encodedPath, params) // /eapi
            CryptoMode.LINUX -> NeteaseCrypto.linuxApiEncrypt(params)           // /api (linux)
            CryptoMode.API   -> params.mapValues { it.value.toString() }        // 直传
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

        // WeAPI 需要 csrf_token
        if (mode == CryptoMode.WEAPI) {
            val csrf = getCookie("__csrf") ?: ""
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
            val responseBody = resp.body ?: return ""
            val encoding = resp.header("Content-Encoding")?.lowercase(Locale.getDefault())
            val bytes = when (encoding) {
                "br"   -> BrotliInputStream(responseBody.byteStream()).use { it.readBytesCompat() }
                "gzip" -> GZIPInputStream(responseBody.byteStream()).use { it.readBytesCompat() }
                else   -> responseBody.bytes()
            }
            return String(bytes, StandardCharsets.UTF_8)
        }
    }

    /** 调用 WeAPI 接口 */
    @Throws(IOException::class)
    fun callWeApi(path: String, params: Map<String, Any>, usePersistedCookies: Boolean = true): String {
        val p = if (path.startsWith("/")) path else "/$path"
        val url = "https://music.163.com/weapi$p"
        return request(url, params, CryptoMode.WEAPI, "POST", usePersistedCookies)
    }

    /** 调用 EAPI 接口 */
    @Throws(IOException::class)
    fun callEApi(path: String, params: Map<String, Any>, usePersistedCookies: Boolean = true): String {
        val p = if (path.startsWith("/")) path else "/$path"
        val url = "https://interface.music.163.com/eapi$p"
        return request(url, params, CryptoMode.EAPI, "POST", usePersistedCookies)
    }

    /** 调用 LinuxAPI 接口 */
    @Throws(IOException::class)
    fun callLinuxApi(path: String, params: Map<String, Any>, usePersistedCookies: Boolean = true): String {
        val p = if (path.startsWith("/")) path else "/$path"
        val url = "https://music.163.com/api$p"
        return request(url, params, CryptoMode.LINUX, "POST", usePersistedCookies)
    }

    // -------------------------
    // 认证相关
    // -------------------------

    /** 手机号 + 密码登录（不携带持久化 Cookie） */
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

    /** 发送短信验证码（不携带持久化 Cookie） */
    @Throws(IOException::class)
    fun sendCaptcha(phone: String, ctcode: Int = 86): String {
        val url = "https://interface.music.163.com/weapi/sms/captcha/sent"
        val params = mapOf("cellphone" to phone, "ctcode" to ctcode.toString())
        return request(url, params, CryptoMode.WEAPI, "POST", usePersistedCookies = false)
    }

    /** 校验短信验证码（不携带持久化 Cookie） */
    @Throws(IOException::class)
    fun verifyCaptcha(phone: String, captcha: String, ctcode: Int = 86): String {
        val url = "https://interface.music.163.com/weapi/sms/captcha/verify"
        val params = mapOf("cellphone" to phone, "captcha" to captcha, "ctcode" to ctcode.toString())
        return request(url, params, CryptoMode.WEAPI, "POST", usePersistedCookies = false)
    }

    /** 短信验证码登录（不携带持久化 Cookie） */
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

    // -------------------------
    // 业务接口
    // -------------------------

    /** 获取首页推荐歌单 */
    @Throws(IOException::class)
    fun getRecommendedPlaylists(limit: Int = 30): String {
        val url = "https://music.163.com/weapi/personalized/playlist"
        val params = mapOf("limit" to limit.toString())
        return request(url, params, CryptoMode.WEAPI, "POST", usePersistedCookies = true)
    }

    /** 搜索歌曲 */
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

    /** 获取歌曲播放链接 */
    @Throws(IOException::class)
    fun getSongUrl(songId: Long, bitrate: Int = 320000): String {
        val url = "https://music.163.com/weapi/song/enhance/player/url"
        val params = mutableMapOf<String, Any>(
            "ids" to "[$songId]",
            "br" to bitrate.toString()
        )
        return request(url, params, CryptoMode.WEAPI, "POST", usePersistedCookies = true)
    }

    /** 获取歌曲下载链接 */
    @Throws(IOException::class)
    fun getSongDownloadUrl(songId: Long, bitrate: Int = 320000, level: String = "lossless"): String {
        val url = "https://music.163.com/weapi/song/enhance/download/url"
        val params = mutableMapOf<String, Any>(
            "id" to songId.toString(),
            "br" to bitrate.toString(),
            "level" to level
        )

        return request(url, params, CryptoMode.WEAPI, "POST", usePersistedCookies = true)
    }

    /** 获取用户歌单列表 */
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

    /**
     * 获取歌单详情（/api/v6/playlist/detail）
     */
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
                      "creator": {
                        "userId": ${jsonQuote(userIdStr)},
                        "nickname": ${jsonQuote(nickname)}
                      },
                      "coverImgUrl": ${jsonQuote(cover)},
                      "name": ${jsonQuote(playlistName)},
                      "id": ${jsonQuote(playlistIdStr)}
                    }
                """.trimIndent()
                items.add(itemJson)
            }

            val body = """
                {
                  "code": 200,
                  "playlists": [${items.joinToString(",")}]
                }
            """.trimIndent()

            body
        } catch (e: Exception) {
            """
            {
              "code": 500,
              "msg": ${jsonQuote(e.stackTraceToString())}
            }
            """.trimIndent()
        }
    }

    /**
     * 获取精品歌单标签
     */
    @Throws(IOException::class)
    fun getHighQualityTags(): String {
        val url = "https://music.163.com/api/playlist/highquality/tags"
        val params = emptyMap<String, Any>()
        NPLogger.d("NERI-Netease", "getHighQualityTags calling")
        return request(url, params, CryptoMode.WEAPI, "POST", usePersistedCookies = true)
    }

    /**
     * 获取精品歌单
     *
     * @param cat     分类，如 "全部", "华语", "欧美", ...
     * @param limit   数量
     * @param before  上一页最后一个歌单的 updateTime，用于分页
     */
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
        return callWeApi(
            path = "/playlist/highquality/list",
            params = params,
            usePersistedCookies = true
        )
    }
}
