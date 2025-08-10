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

import moe.ouom.neriplayer.util.readBytesCompat
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.brotli.dec.BrotliInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Locale

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
        persistedCookies = cookies.toMap()
    }

    /** 根据名称获取 Cookie 值 */
    private fun getCookie(name: String): String? {
        cookieStore.values.forEach { list ->
            list.firstOrNull { it.name == name }?.let { return it.value }
        }
        return null
    }

    /** 构建 Cookie 头 */
    private fun buildPersistedCookieHeader(): String? {
        val map = persistedCookies
        if (map.isEmpty()) return null
        return map.entries.joinToString("; ") { (k, v) -> "$k=$v" }
    }

    /**
     * 发送统一请求
     *
     * @param url 完整请求地址
     * @param params 未加密的请求参数
     * @param mode 加密模式，默认为 CryptoMode.WEAPI
     * @param method "POST" / "GET"
     * @param usePersistedCookies 是否携带 DataStore 中保存过的 Cookies
     * @return 服务端返回的原始文本字符串
     * @throws IOException 网络异常或服务器异常
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
        // 按模式加密参数
        val bodyParams: Map<String, String> = when (mode) {
            CryptoMode.WEAPI -> NeteaseCrypto.weApiEncrypt(params)
            CryptoMode.EAPI -> NeteaseCrypto.eApiEncrypt(requestUrl.encodedPath, params)
            CryptoMode.LINUX -> NeteaseCrypto.linuxApiEncrypt(params)
            CryptoMode.API -> params.mapValues { it.value.toString() }
        }

        var reqUrl = requestUrl
        val builder = Request.Builder()
        // 公共头部
        builder.header("Accept", "*/*")
        builder.header("Accept-Language", "zh-CN,zh-Hans;q=0.9")
        builder.header("Connection", "keep-alive")
        builder.header("Referer", "https://music.163.com")
        builder.header("Host", requestUrl.host)
        builder.header("User-Agent", "Mozilla/5.0 (Linux; Android 10; Kotlin) NeteaseClient/1.0")

        if (usePersistedCookies) {
            buildPersistedCookieHeader()?.let { builder.header("Cookie", it) }
        }

        // WeAPI 模式需要 csrf_token
        if (mode == CryptoMode.WEAPI) {
            val csrf = getCookie("__csrf") ?: ""
            reqUrl = requestUrl.newBuilder().setQueryParameter("csrf_token", csrf).build()
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
            val bytes = resp.body?.let { responseBody ->
                when (resp.header("Content-Encoding")?.lowercase()) {
                    "br" -> BrotliInputStream(responseBody.byteStream()).use { it.readBytesCompat() }
                    "gzip" -> java.util.zip.GZIPInputStream(responseBody.byteStream()).use { it.readBytesCompat() }
                    else -> responseBody.bytes()
                }
            } ?: ByteArray(0)
            return String(bytes, StandardCharsets.UTF_8)
        }
    }

    /** 调用 WeAPI 接口（默认使用持久化 Cookie） */
    @Throws(IOException::class)
    fun callWeApi(path: String, params: Map<String, Any>, usePersistedCookies: Boolean = true): String {
        val p = if (path.startsWith("/")) path else "/$path"
        val url = "https://music.163.com/weapi$p"
        return request(url, params, CryptoMode.WEAPI, "POST", usePersistedCookies)
    }

    /** 调用 EAPI 接口（默认使用持久化 Cookie） */
    @Throws(IOException::class)
    fun callEApi(path: String, params: Map<String, Any>, usePersistedCookies: Boolean = true): String {
        val p = if (path.startsWith("/")) path else "/$path"
        val url = "https://interface.music.163.com/eapi$p"
        return request(url, params, CryptoMode.EAPI, "POST", usePersistedCookies)
    }

    /** 调用 LinuxAPI 接口（默认使用持久化 Cookie） */
    @Throws(IOException::class)
    fun callLinuxApi(path: String, params: Map<String, Any>, usePersistedCookies: Boolean = true): String {
        val p = if (path.startsWith("/")) path else "/$path"
        val url = "https://music.163.com/api$p"
        return request(url, params, CryptoMode.LINUX, "POST", usePersistedCookies)
    }

    /**
     * 手机号 + 密码登录（不携带持久化 Cookie）
     */
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

    /**
     * 发送短信验证码（不携带持久化 Cookie）
     */
    @Throws(IOException::class)
    fun sendCaptcha(phone: String, ctcode: Int = 86): String {
        val url = "https://interface.music.163.com/weapi/sms/captcha/sent"
        val params = mapOf("cellphone" to phone, "ctcode" to ctcode.toString())
        return request(url, params, CryptoMode.WEAPI, "POST", usePersistedCookies = false)
    }

    /**
     * 校验短信验证码（不携带持久化 Cookie）
     */
    @Throws(IOException::class)
    fun verifyCaptcha(phone: String, captcha: String, ctcode: Int = 86): String {
        val url = "https://interface.music.163.com/weapi/sms/captcha/verify"
        val params = mapOf("cellphone" to phone, "captcha" to captcha, "ctcode" to ctcode.toString())
        return request(url, params, CryptoMode.WEAPI, "POST", usePersistedCookies = false)
    }

    /**
     * 使用短信验证码登录（不携带持久化 Cookie）
     */
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

    /**
     * 获取网友精选碟
     */
    @Throws(IOException::class)
    fun getRecommendedPlaylists(limit: Int = 30): String {
        val url = "https://music.163.com/weapi/personalized/playlist"
        val params = mapOf("limit" to limit.toString())
        // 推荐歌单使用 WEAPI 加密，通过 POST 提交
        return request(url, params, CryptoMode.WEAPI, "POST", usePersistedCookies = true)
    }

    /**
     * 搜索音乐资源
     */
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

    /**
     * 获取歌曲播放链接
     */
    @Throws(IOException::class)
    fun getSongUrl(songId: Long, bitrate: Int = 320000): String {
        val url = "https://music.163.com/weapi/song/enhance/player/url"
        val params = mutableMapOf<String, Any>(
            "ids" to "[$songId]",
            "br" to bitrate.toString()
        )
        return request(url, params, CryptoMode.WEAPI, "POST", usePersistedCookies = true)
    }

    /**
     * 获取歌曲下载链接
     */
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

    /**
     * 获取歌单列表
     */
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
     * 获取歌单详情
     */
    @Throws(IOException::class)
    fun getPlaylistDetail(playlistId: Long, trackCount: Int = 1000): String {
        val url = "https://music.163.com/weapi/v3/playlist/detail"
        val params = mutableMapOf<String, Any>(
            "id" to playlistId.toString(),
            "total" to "true",
            "limit" to trackCount.toString(),
            "n" to trackCount.toString(),
            // Note: 官方实现中参数名写成 offest（手误），但此处沿用以保证兼容性
            "offest" to "0"
        )
        return request(url, params, CryptoMode.WEAPI, "POST", usePersistedCookies = true)
    }

    /**
     * 获取当前运行期 CookieJar 中的所有 Cookie
     */
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
}
