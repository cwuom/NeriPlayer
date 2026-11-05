package moe.ouom.neriplayer.platform.subsonic.api

import java.security.MessageDigest
import moe.ouom.neriplayer.data.model.server.ServerSongRef
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicProfile
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

data class SubsonicCoverReference private constructor(
    val baseUrl: String,
    val coverArtId: String,
    val scope: String
) {
    fun matches(profile: SubsonicProfile): Boolean = runCatching {
        val base = normalizedBase(profile.baseUrl)
        base.toString() == baseUrl && accountScope(base, profile.username) == scope
    }.getOrDefault(false)

    companion object {
        private const val ENDPOINT = "rest/getCoverArt.view"
        private const val MARKER = "neri-cover=v1:"
        private val markerPattern = Regex("neri-cover=v1:[0-9a-f]{64}")

        fun create(profile: SubsonicProfile, coverArtId: String): String {
            require(coverArtId.isNotBlank() && coverArtId.length <= 8192)
            val base = normalizedBase(profile.baseUrl)
            return base.newBuilder().addPathSegment("rest").addPathSegment("getCoverArt.view")
                .addQueryParameter("id", coverArtId).addQueryParameter("size", "600")
                .fragment(MARKER + accountScope(base, profile.username)).build().toString()
        }

        fun parse(url: HttpUrl): SubsonicCoverReference? {
            if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.host == ServerSongRef.RESOURCE_HOST) return null
            val fragment = url.fragment ?: return null
            if (!markerPattern.matches(fragment) || !url.encodedPath.endsWith("/$ENDPOINT")) return null
            if (url.querySize != 2 || url.queryParameterNames != setOf("id", "size") ||
                url.queryParameter("size") != "600") return null
            val id = url.queryParameter("id")?.takeIf { it.isNotBlank() && it.length <= 8192 } ?: return null
            val base = url.newBuilder().encodedPath(url.encodedPath.removeSuffix(ENDPOINT))
                .query(null).fragment(null).build()
            return SubsonicCoverReference(base.toString(), id, fragment.removePrefix(MARKER))
        }

        private fun normalizedBase(raw: String): HttpUrl {
            val url = raw.trim().toHttpUrl()
            require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null &&
                url.host != ServerSongRef.RESOURCE_HOST)
            return url.newBuilder().encodedPath(url.encodedPath.trimEnd('/') + "/").build()
        }

        private fun accountScope(base: HttpUrl, username: String): String {
            val user = username.trim()
            require(user.isNotEmpty())
            // 账号指纹只用于选择本机配置，认证信息不会进入持久化封面地址
            return MessageDigest.getInstance("SHA-256").digest((base.toString() + "\n" + user).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}
