package moe.ouom.neriplayer.lanltw.media

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import moe.ouom.neriplayer.data.model.lan.LanLocalMedia
import moe.ouom.neriplayer.data.model.lan.LanFileResource
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Endpoint host is supplied by the connected control session, never by track metadata. */
class LanMediaDownload(http: OkHttpClient) {
    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS).build()

    suspend fun fetch(controlEndpoint: HttpUrl, port: Int, token: String, media: LanLocalMedia,
        directory: File, progress: (Long, Long) -> Unit = { _, _ -> }): File {
        require(media.valid()) { "SOURCE" }
        return fetchResource(controlEndpoint, port, token, LanFileResource(media.mediaId, media.sha256, media.byteLength, media.mimeType),
            directory, LanLocalMedia.MAX_BYTES, progress)
    }
    suspend fun fetchResource(controlEndpoint: HttpUrl, port: Int, token: String, media: LanFileResource,
        directory: File, maxBytes: Long, progress: (Long, Long) -> Unit = { _, _ -> }): File {
        require(media.valid(maxBytes) && port in 1..65535 && token.isNotBlank()) { "SOURCE" }
        val url = controlEndpoint.newBuilder().scheme("http").port(port).username("").password("")
            .encodedPath("/media/${media.mediaId}").query(null).fragment(null).build()
        return withTimeout(300_000) {
            while (true) {
                val result = attempt(Request.Builder().url(url).header("Authorization", "Bearer $token")
                    .header("Accept-Encoding", "identity").build(), media, directory, progress)
                if (result != null) return@withTimeout result
                delay(1000)
            }
            @Suppress("UNREACHABLE_CODE") error("SOURCE")
        }
    }
    private suspend fun attempt(request: Request, media: LanFileResource, directory: File,
        progress: (Long, Long) -> Unit): File? {
        var completed: File? = null
        try { return withContext(Dispatchers.IO) {
        coroutineScope {
            val call = client.newCall(request)
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                call.execute().use { response ->
                    if (response.code == 503) return@coroutineScope null
                    check(response.code == 200) { "LOCAL_TRANSFER" }
                    val body = checkNotNull(response.body) { "LOCAL_TRANSFER" }
                    check(body.contentLength() == media.byteLength) { "AUDIO_CONTENT" }
                    var received = 0L
                    val snapshot = LanFileSnapshot.create(directory) {
                        object : java.io.FilterInputStream(body.byteStream()) {
                            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                                val count = super.read(bytes, offset, length)
                                if (count > 0) {
                                    received += count
                                    check(received <= media.byteLength) { "AUDIO_CONTENT" }
                                    progress(received, media.byteLength)
                                }
                                return count
                            }
                        }
                    }
                    if (snapshot.byteLength != media.byteLength || snapshot.sha256 != media.sha256) {
                        snapshot.file.delete(); error("AUDIO_CONTENT")
                    }
                    snapshot.file.also { completed = it }
                }
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                throw IllegalStateException("LOCAL_TRANSFER", error)
            } finally { cancellation.cancelAndJoin() }
        }
        } } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { completed?.delete() }
            throw error
        }
    }
}
