package moe.ouom.neriplayer.api.sync.http

import java.io.IOException
import okhttp3.ResponseBody
import okio.Buffer

internal object SyncResponseBodyReader {
    const val MAX_SYNC_FILE_BYTES = 12 * 1024 * 1024
    private const val READ_CHUNK_BYTES = 8192L

    fun readText(body: ResponseBody): String = read(body).toString(Charsets.UTF_8)

    fun read(body: ResponseBody, maxBytes: Int = MAX_SYNC_FILE_BYTES, cancelOnReadFailure: (() -> Unit)? = null): ByteArray {
        require(maxBytes > 0) { "Invalid sync response budget" }
        try {
            if (body.contentLength() > maxBytes) throw IOException("Sync payload is too large")
            val content = Buffer()
            val source = body.source()
            while (source.read(content, minOf(READ_CHUNK_BYTES, maxBytes - content.size + 1L)) != -1L) {
                if (content.size > maxBytes) throw IOException("Sync payload is too large")
            }
            return content.readByteArray()
        } catch (error: IOException) {
            // 调用方持有请求时，读取失败先断开，关闭正文不再排空未读取的数据
            cancelOnReadFailure?.invoke()
            throw error
        }
    }
}
