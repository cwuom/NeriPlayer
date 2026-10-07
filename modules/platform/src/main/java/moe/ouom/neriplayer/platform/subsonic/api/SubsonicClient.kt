package moe.ouom.neriplayer.platform.subsonic.api

import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import moe.ouom.neriplayer.network.http.awaitResponse
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicProfile
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class SubsonicException(val code: Int, message: String) : IOException(message)

/** Subsonic JSON transport. Server error messages and authenticated URLs are never exposed. */
class SubsonicClient(private val client: OkHttpClient) {
    suspend fun call(profile: SubsonicProfile, password: String, method: String,
                     parameters: Map<String, String> = emptyMap()): JSONObject {
        val request = Request.Builder().url(requestUrl(profile, password, method, parameters)).build()
        return try { client.newCall(request).awaitResponse { response ->
            if (!response.isSuccessful) throw SubsonicException(response.code, errorText(response.code))
            val body = response.body
            val source = body.source()
            if (source.request(4L * 1024 * 1024 + 1)) throw SubsonicException(-1, "服务器响应过大")
            val root = try { JSONObject(source.readUtf8()).getJSONObject("subsonic-response") }
            catch (_: org.json.JSONException) { throw SubsonicException(-1, "服务器响应格式不正确") }
            if (root.optString("status") != "ok") {
                val code = root.optJSONObject("error")?.optInt("code", -1) ?: -1
                throw SubsonicException(code, errorText(code))
            }
            root
        } } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: SubsonicException) {
            throw error
        } catch (_: IOException) {
            throw IOException("音乐服务器连接失败")
        }
    }

    companion object {
        private val random = SecureRandom()
        fun requestUrl(profile: SubsonicProfile, password: String, method: String,
                       parameters: Map<String, String> = emptyMap()): HttpUrl {
            require(method.matches(Regex("[A-Za-z][A-Za-z0-9]*"))) {
                "Invalid Subsonic method name"
            }
            val salt = ByteArray(16).also(random::nextBytes).toHex()
            val token = MessageDigest.getInstance("MD5")
                .digest((password + salt).toByteArray(Charsets.UTF_8)).toHex()
            return profile.baseUrl.toHttpUrl().newBuilder()
                .addPathSegment("rest").addPathSegment("$method.view")
                .addQueryParameter("u", profile.username).addQueryParameter("t", token)
                .addQueryParameter("s", salt).addQueryParameter("v", "1.16.1")
                .addQueryParameter("c", "NeriPlayer").addQueryParameter("f", "json")
                .apply { parameters.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }

        private fun errorText(code: Int): String = when (code) {
            40, 41, 401 -> "服务器账号或密码不正确，请重新登录"
            50, 403 -> "该账号没有访问权限"
            70, 404 -> "服务器资源或接口不存在"
            20, 30 -> "服务器协议版本不兼容"
            else -> "服务器请求失败（$code）"
        }
    }
}
