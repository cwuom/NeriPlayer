package moe.ouom.neriplayer.lanltw.media

import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.model.lan.LanFileResource
import moe.ouom.neriplayer.data.model.lan.LanSharedPresentation
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

internal class LanSessionPresentation(http: OkHttpClient, private val directory: File) {
    private data class Endpoint(val url: HttpUrl, val port: Int, val token: String)
    private data class Published(val server: LanMediaServer, val resource: LanFileResource)

    private val downloader = LanMediaDownload(http)
    private val published = ArrayDeque<Published>()
    private var endpoint: Endpoint? = null
    private var cached: Pair<LanFileResource, LanSharedPresentation>? = null

    suspend fun publish(server: LanMediaServer, value: LanSharedPresentation): LanFileResource {
        val bytes = withContext(Dispatchers.Default) { LanPresentationCodec.encodeForTransfer(value) }
        val (source, digest) = LanReadableMedia.fingerprint { bytes.inputStream() }
        val matching = published.firstOrNull { it.server === server && it.resource.sha256 == digest }
        if (matching != null) {
            published.remove(matching)
            published.addLast(matching)
            return matching.resource
        }
        val resource = LanFileResource(UUID.randomUUID().toString(), digest, source.byteLength, "application/json")
        server.publishBorrowed(resource.mediaId, source, resource.mimeType)
        published.addLast(Published(server, resource))
        // 切歌期间，正在追赶进度的成员可能仍在读取上一首歌词
        if (published.size > 2) retire(published.removeFirst())
        return resource
    }

    fun setEndpoint(controlUrl: String, port: Int, token: String) {
        val next = Endpoint(controlUrl.toHttpUrl(), port, token)
        if (next != endpoint) {
            endpoint = next
            cached = null
        }
    }

    suspend fun resolve(resource: LanFileResource): LanSharedPresentation? {
        val connection = endpoint ?: return null
        require(resource.mimeType == "application/json" && resource.valid(LanPresentationCodec.MAX_BYTES))
        cached?.takeIf { it.first == resource }?.let { return it.second }
        val file = downloader.fetchResource(connection.url, connection.port, connection.token, resource,
            directory, LanPresentationCodec.MAX_BYTES)
        val value = try {
            withContext(Dispatchers.IO) { LanPresentationCodec.decode(file.readBytes()) }
        } finally {
            file.delete()
        }
        if (connection !== endpoint) return null
        cached = resource to value
        return value
    }

    fun clear() {
        endpoint = null
        cached = null
        published.forEach(::retire)
        published.clear()
    }

    private fun retire(entry: Published) = entry.server.retire(entry.resource.mediaId)
}
