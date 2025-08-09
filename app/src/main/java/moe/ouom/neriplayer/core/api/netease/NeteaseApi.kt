package moe.ouom.neriplayer.core.api.netease

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * 网易云音乐 API 客户端
 */
class NeteaseApi(private val cookieStore: MutableMap<String, String> = mutableMapOf()) {

    /**
     * 组装 Cookie 字符串
     */
    private fun buildCookieHeader(cookies: Map<String, String>): String {
        return cookies.entries.joinToString("; ") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
    }

    /**
     * 从响应头中解析 Set-Cookie，并更新 cookieStore
     */
    private fun parseSetCookie(headers: Map<String, List<String>>) {
        headers["Set-Cookie"]?.forEach { header ->
            val parts = header.split(";".toRegex(), limit = 2)[0] // 只取第一段 "key=value"
            val kv = parts.split("=", limit = 2)
            if (kv.size == 2) {
                cookieStore[kv[0]] = kv[1]
            }
        }
    }

    /**
     * 核心 HTTP 请求方法
     * 根据 crypto 参数决定加密方式
     * @param method "POST" / "GET"
     * @param url 请求地址
     * @param data 请求参数
     * @param crypto 加密方式: "weapi", "eapi", "linuxapi", or "api" (no encryption)
     * @param eapiUrl eapi 模式需要的真实路径
     * @return Raw JSON response body
     */
    @Throws(Exception::class)
    private fun sendRequest(
        method: String,
        url: String,
        data: Map<String, String>,
        crypto: String,
        eapiUrl: String? = null
    ): String {
        val requestBodyPairs: MutableList<Pair<String, String>> = mutableListOf()
        var targetUrl = url

        // 按加密类型处理参数
        when (crypto) {
            "weapi" -> {
                val encrypted = NeteaseCrypto.weapi(data)
                requestBodyPairs.add("params" to encrypted["params"]!!)
                requestBodyPairs.add("encSecKey" to encrypted["encSecKey"]!!)
                targetUrl = targetUrl.replace(Regex("\\w*api"), "weapi")
            }
            "eapi" -> {
                val realPath = eapiUrl ?: throw IllegalArgumentException("eapi 需要 eapiUrl 参数")
                val encrypted = NeteaseCrypto.eapi(realPath, data)
                requestBodyPairs.add("params" to encrypted["params"]!!)
                targetUrl = targetUrl.replace(Regex("\\w*api"), "eapi")
            }
            "linuxapi" -> {
                val payload = mutableMapOf<String, Any>(
                    "method" to method,
                    "url" to targetUrl.replace(Regex("\\w*api"), "api"),
                    "params" to data
                )
                val encrypted = NeteaseCrypto.linuxapi(payload)
                requestBodyPairs.add("eparams" to encrypted["eparams"]!!)
                targetUrl = "https://music.163.com/api/linux/forward"
            }
            "api" -> {
                // 不加密
                data.forEach { (k, v) -> requestBodyPairs.add(k to v) }
            }
            else -> throw IllegalArgumentException("不支持的加密方式: $crypto")
        }

        // 将参数转为 URL 编码的字符串
        val bodyString = requestBodyPairs.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
        }

        // 发起 HTTP 连接
        val urlObj = URL(targetUrl)
        val conn = urlObj.openConnection() as HttpURLConnection
        conn.requestMethod = method.uppercase()
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )

        // 发送 cookie
        if (cookieStore.isNotEmpty()) {
            conn.setRequestProperty("Cookie", buildCookieHeader(cookieStore))
        }

        // 写入请求体
        conn.doOutput = true
        conn.doInput = true
        OutputStreamWriter(conn.outputStream).use { out ->
            out.write(bodyString)
            out.flush()
        }

        // 读取响应
        val responseBuilder = StringBuilder()
        BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                responseBuilder.append(line)
            }
        }

        // 解析并保存响应中的 cookie
        parseSetCookie(conn.headerFields)
        return responseBuilder.toString()
    }

    /**
     * 获取推荐歌单
     */
    fun getRecommendedPlaylists(limit: Int = 30): String {
        val data = mutableMapOf<String, String>()
        data["limit"] = limit.toString()
        data["total"] = "true"
        data["n"] = "1000"
        return sendRequest("POST", "https://music.163.com/weapi/personalized/playlist", data, "weapi")
    }

    /**
     * 获取用户歌单
     */
    fun getUserPlaylists(uid: String, limit: Int = 30, offset: Int = 0): String {
        val data = mutableMapOf<String, String>()
        data["uid"] = uid
        data["limit"] = limit.toString()
        data["offset"] = offset.toString()
        data["includeVideo"] = "true"
        return sendRequest("POST", "https://music.163.com/api/user/playlist", data, "weapi")
    }

    /**
     * 搜索歌曲或资源
     * type 参数：1 单曲 / 10 专辑 / 100 歌手 / 1000 歌单 / ...
     */
    fun searchSong(keywords: String, type: Int = 1, limit: Int = 30, offset: Int = 0): String {
        val data = mutableMapOf<String, String>()
        if (type == 2000) {
            data["keyword"] = keywords
            data["scene"] = "normal"
        } else {
            data["s"] = keywords
            data["type"] = type.toString()
        }
        data["limit"] = limit.toString()
        data["offset"] = offset.toString()

        val endpoint = if (type == 2000) {
            "https://music.163.com/api/search/voice/get"
        } else {
            "https://music.163.com/weapi/search/get"
        }

        return sendRequest("POST", endpoint, data, "weapi")
    }

    /**
     * 获取歌曲播放链接
     */
    fun getSongUrl(ids: List<String>, br: Int = 999000): String {
        cookieStore["os"] = "pc" // 模拟 PC 环境获取高码率
        val data = mutableMapOf<String, String>()
        data["ids"] = ids.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
        data["br"] = br.toString()
        return sendRequest(
            "POST",
            "https://interface3.music.163.com/eapi/song/enhance/player/url",
            data,
            "eapi",
            eapiUrl = "/api/song/enhance/player/url"
        )
    }

    /**
     * 发送短信验证码
     */
    fun sendCaptcha(phone: String, ctcode: String = "86"): String {
        val data = mutableMapOf<String, String>()
        data["cellphone"] = phone
        data["ctcode"] = ctcode
        return sendRequest("POST", "https://music.163.com/api/sms/captcha/sent", data, "weapi")
    }

    /**
     * 验证短信验证码
     */
    fun verifyCaptcha(phone: String, captcha: String, ctcode: String = "86"): String {
        val data = mutableMapOf<String, String>()
        data["cellphone"] = phone
        data["captcha"] = captcha
        data["ctcode"] = ctcode
        return sendRequest("POST", "https://music.163.com/weapi/sms/captcha/verify", data, "weapi")
    }

    /**
     * 手机号登录
     * 支持验证码、明文密码、MD5 密码
     */
    fun loginCellphone(
        phone: String,
        password: String? = null,
        md5Password: String? = null,
        captcha: String? = null,
        countrycode: String = "86"
    ): String {
        cookieStore["os"] = "ios"     // 模拟 iOS 客户端
        cookieStore["appver"] = "8.20.21"

        val data = mutableMapOf<String, String>()
        data["phone"] = phone
        data["countrycode"] = countrycode

        val pwdField: String
        val pwdValue: String
        if (!captcha.isNullOrEmpty()) {
            pwdField = "captcha"
            pwdValue = captcha
        } else if (!md5Password.isNullOrEmpty()) {
            pwdField = "password"
            pwdValue = md5Password
        } else if (!password.isNullOrEmpty()) {
            pwdField = "password"
            pwdValue = md5Hex(password) // 密码转 MD5
        } else {
            throw IllegalArgumentException("缺少登录凭证")
        }

        data[pwdField] = pwdValue
        data["rememberLogin"] = "true"

        return sendRequest("POST", "https://music.163.com/weapi/login/cellphone", data, "weapi")
    }

    /**
     * 计算字符串 MD5 值
     */
    private fun md5Hex(text: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
