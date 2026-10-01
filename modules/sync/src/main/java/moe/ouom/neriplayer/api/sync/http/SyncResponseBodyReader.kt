package moe.ouom.neriplayer.api.sync.http

import java.io.IOException
import okhttp3.ResponseBody
import okio.Buffer

internal object SyncResponseBodyReader {
    const val MAX_SYNC_FILE_BYTES = 12 * 1024 * 1024
    private const val READ_CHUNK_BYTES = 8192L

    fun readText(body: ResponseBody): String = read(body).toString(Charsets.UTF_8)

    fun read(body: ResponseBody): ByteArray {
        if (body.contentLength() > MAX_SYNC_FILE_BYTES) throw IOException("Sync payload is too large")
        val content = Buffer()
        val source = body.source()
        while (source.read(content, READ_CHUNK_BYTES) != -1L) {
            if (content.size > MAX_SYNC_FILE_BYTES) throw IOException("Sync payload is too large")
        }
        return content.readByteArray()
    }
}
