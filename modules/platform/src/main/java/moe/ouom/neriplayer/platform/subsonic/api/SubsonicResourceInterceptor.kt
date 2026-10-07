package moe.ouom.neriplayer.platform.subsonic.api

import java.io.IOException
import moe.ouom.neriplayer.data.model.server.ServerSongRef
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicAccounts
import okhttp3.Interceptor
import okhttp3.Response

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
            val profile = store.profile(ref.profileId) ?: throw IOException("音乐服务器未连接")
            val parameters = mutableMapOf("id" to ref.songId)
            val method = when (path[2]) {
                "stream" -> { parameters["format"] = "raw"; "stream" }
                "cover" -> { parameters["size"] = "600"; "getCoverArt" }
                else -> throw IOException("无效的服务器资源类型")
            }
            val url = SubsonicClient.requestUrl(profile, store.password(profile.id), method, parameters)
            val response = chain.proceed(request.newBuilder().url(url)
                .removeHeader("Authorization").removeHeader("Cookie")
                .removeHeader("Referer").removeHeader("Origin").build())
            val type = response.header("Content-Type").orEmpty()
            if (!response.isSuccessful || type.contains("xml", true) || type.contains("json", true) || type.contains("html", true)) {
                response.close()
                throw IOException("音乐服务器未返回有效媒体，请检查账号和资源")
            }
            // Callers and caches keep the opaque URL; credentials stay inside the transport.
            return response.newBuilder().request(request).build()
        } catch (_: Exception) {
            throw IOException("音乐服务器资源加载失败，请检查连接、账号和资源")
        }
    }
}
