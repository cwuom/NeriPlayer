package moe.ouom.neriplayer.platform.subsonic.api

import java.io.IOException
import moe.ouom.neriplayer.data.model.server.ServerSongRef
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicAccounts
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject

/** Resolve credential-free media references for the existing Media3 and Coil HTTP clients. */
class SubsonicResourceInterceptor(private val accounts: () -> SubsonicAccounts) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != ServerSongRef.RESOURCE_HOST) return chain.proceed(request)
        try {
            val path = request.url.pathSegments
            require(path.size == 4 && path[0] == "v1")
            val ref = ServerSongRef.reference(path[1], path[3])
            val store = accounts()
            val credentials = try { store.credentials(ref.profileId) }
            catch (_: IllegalStateException) { throw SubsonicException.accountUnavailable() }
            val parameters = mutableMapOf("id" to ref.songId)
            val method = when (path[2]) {
                "stream" -> { parameters["format"] = "raw"; "stream" }
                "cover" -> { parameters["size"] = "600"; "getCoverArt" }
                else -> throw IOException("无效的服务器资源类型")
            }
            val url = SubsonicClient.requestUrl(credentials.profile, credentials.password, method, parameters)
            val response = chain.proceed(request.newBuilder().url(url)
                .removeHeader("Authorization").removeHeader("Cookie")
                .removeHeader("Referer").removeHeader("Origin").build())
            // Media3 needs Content-Range to distinguish an EOF probe from an invalid offset.
            // Keep the opaque request even on errors so transport credentials cannot escape.
            if (method == "stream" && response.code == 416) {
                return response.newBuilder().request(request).build()
            }
            val type = response.header("Content-Type").orEmpty()
            if (!response.isSuccessful) {
                val error = SubsonicException.http(response.code, response.header("Retry-After"))
                response.close()
                throw error
            }
            if (type.contains("xml", true) || type.contains("json", true) || type.contains("html", true)) {
                response.use {
                    val text = it.peekBody(65_536L).string()
                    val code = if (type.contains("json", true)) {
                        runCatching { JSONObject(text).optJSONObject("subsonic-response")
                            ?.optJSONObject("error")?.getInt("code") }.getOrNull()
                    } else if (type.contains("xml", true)) {
                        Regex("<error\\b[^>]*\\bcode\\s*=\\s*[\"']([0-9]+)[\"']")
                            .find(text)?.groupValues?.get(1)?.toIntOrNull()
                    } else null
                    throw code?.let { value -> SubsonicException.protocol(value) }
                        ?: SubsonicException(-1, "音乐服务器未返回有效媒体")
                }
            }
            // Callers and caches keep the opaque URL; credentials stay inside the transport.
            return response.newBuilder().request(request).build()
        } catch (error: IOException) {
            throw SubsonicException.transport(error)
        } catch (_: Exception) {
            throw SubsonicException(-1, "音乐服务器资源引用或配置无效")
        }
    }
}
