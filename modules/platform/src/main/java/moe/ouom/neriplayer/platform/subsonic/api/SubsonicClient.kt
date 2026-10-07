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

/** Subsonic JSON transport. Server error messages and authenticated URLs are never exposed. */
class SubsonicClient(private val client: OkHttpClient) {
    suspend fun call(profile: SubsonicProfile, password: String, method: String,
                     parameters: Map<String, String> = emptyMap()): JSONObject {
        val request = Request.Builder().url(requestUrl(profile, password, method, parameters)).build()
        return try { client.newCall(request).awaitResponse { response ->
            if (!response.isSuccessful) throw SubsonicException.http(response.code, response.header("Retry-After"))
            val body = response.body
            val source = body.source()
            if (source.request(4L * 1024 * 1024 + 1)) throw SubsonicException(-1, "服务器响应过大")
            val root = try { JSONObject(source.readUtf8()).getJSONObject("subsonic-response") }
            catch (_: org.json.JSONException) { throw SubsonicException(-1, "服务器响应格式不正确") }
            if (root.optString("status") != "ok") {
                val code = root.optJSONObject("error")?.optInt("code", -1) ?: -1
                throw SubsonicException.protocol(code)
            }
            root
        } } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: SubsonicException) {
            throw error
        } catch (error: IOException) {
            throw SubsonicException.transport(error)
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

    }
}
