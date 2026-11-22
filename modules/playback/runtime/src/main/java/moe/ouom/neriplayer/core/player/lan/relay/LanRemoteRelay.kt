@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.lan.relay

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.EOFException
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Dispatchers
import moe.ouom.neriplayer.core.player.engine.datasource.shouldUseResumableChunkedHttpRange
import moe.ouom.neriplayer.lanltw.media.LanMediaStreamInput
import okhttp3.Interceptor
import okhttp3.Response

internal class LanRemoteRelay(
    private val uri: Uri,
    private val fallbackMime: String?,
    private val cacheDir: File,
    private val create: () -> RelaySource,
    private val cancel: () -> Unit,
    private val onInvalidated: (String) -> Unit,
) {
    private val invalidated = AtomicBoolean()
    private val stopUpstream = { invalidated.set(true); cancel() }

    fun open(): LanRelayOrigin {
        val spec = DataSpec(uri)
        var source = create()
        try {
            var length = source.dataSource.open(spec).takeIf { it >= 0 }
            val tag = source.dataSource.responseHeaders.header("ETag")?.takeIf(STRONG_ETAG::matches)
            if (tag == null && (shouldUseResumableChunkedHttpRange(spec) ||
                    source.dataSource.responseHeaders.header("Content-Range") != null)) {
                // 显式请求整段，防止无校验器的分块数据源在一次 read 中切换到另一个 HTTP 响应
                source.close()
                source = create()
                length = source.dataSource.open(spec.withRequestHeaders(mapOf("Range" to "bytes=0-"))).takeIf { it >= 0 }
            }
            val mime = relayAudioMime(uri, source.dataSource, fallbackMime)
            val range = responseRange(source.dataSource)
            if (range != null && range.total == null) throw IOException("SOURCE_CHANGED")
            val size = range?.total ?: length
            check(size != 0L) { "AUDIO_FORMAT" }
            if (range != null && range.start != 0L) throw IOException("SOURCE_CHANGED")
            if (tag == null) {
                if (range != null && (range.total == null || range.end != range.total - 1)) throw IOException("SOURCE_CHANGED")
                val first = readProgressivePrefix(RelayInput(source))
                val spool = LanRelaySpool(cacheDir, first, size)
                return LanRelayOrigin(mime, size, object : LanRelayContent {
                    override fun open(offset: Long, limit: Long?): LanMediaStreamInput {
                        require(offset >= 0 && (limit == null || limit >= 0))
                        val remaining = spool.knownLength?.let { (it - offset).coerceAtLeast(0) }
                        val available = if (limit == null) remaining else remaining?.coerceAtMost(limit)
                        return LanMediaStreamInput(reporting(spool.cursor(offset, available ?: limit)), available)
                    }
                    override fun close() {
                        stopUpstream()
                        Dispatchers.IO.dispatch(EmptyCoroutineContext) { spool.close() }
                    }
                })
            }
            val first = readProgressivePrefix(verified(source, tag, size, 0))
            val window = LanRelayWindow(size, first, stopUpstream) { offset ->
                reportFailure {
                    val next = create()
                    try {
                        val request = spec.buildUpon().setPosition(offset).setHttpRequestHeaders(mapOf("If-Match" to tag)).build()
                        val remaining = next.dataSource.open(request)
                        if (remaining >= 0 && size != null && remaining != size - offset) throw IOException("SOURCE_CHANGED")
                        verified(next, tag, size, offset)
                    } catch (error: Throwable) {
                        next.close()
                        throw error
                    }
                }
            }
            return LanRelayOrigin(mime, size, window)
        } catch (error: Throwable) {
            source.close()
            throw error
        }
    }

    private fun verified(source: RelaySource, tag: String, size: Long?, offset: Long): InputStream {
        var position = offset
        var checked: Map<String, List<String>>? = null
        var previousRange: ByteRange? = null
        fun verifyResponse() {
            val headers = source.dataSource.responseHeaders
            if (checked === headers) return
            val range = responseRange(source.dataSource)
            val total = if (range == null) headers.header("Content-Length")?.toLongOrNull() else range.total
            if (headers.header("ETag") != tag || (size != null && total != null && total != size) ||
                (range != null && range != previousRange && range.start != position)) throw IOException("SOURCE_CHANGED")
            checked = headers
            previousRange = range
        }
        verifyResponse()
        return reporting(object : FilterInputStream(RelayInput(source)) {
            override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                val count = super.read(bytes, off, len)
                if (count > 0) {
                    // 上游的续传适配器可能在这次读取中打开了下一段响应
                    verifyResponse()
                    position += count
                    if (previousRange?.let { position - 1 > it.end } == true) throw IOException("SOURCE_CHANGED")
                } else if (count < 0 && size != null && position < size) throw EOFException("SOURCE_CHANGED")
                return count
            }
        })
    }

    private fun reporting(input: InputStream) = object : FilterInputStream(input) {
        override fun read() = reportFailure { super.read() }
        override fun read(b: ByteArray, off: Int, len: Int) = reportFailure { super.read(b, off, len) }
    }

    private inline fun <T> reportFailure(action: () -> T): T = try {
        action()
    } catch (error: IOException) {
        if (error !is LanSpoolSeekOutOfSpace && error !is LanSpoolReadCancelled && invalidated.compareAndSet(false, true)) {
            onInvalidated(if (error.message == "LOCAL_STORAGE") "LOCAL_STORAGE" else "SOURCE_CHANGED")
        }
        throw error
    }

    private fun responseRange(source: DataSource): ByteRange? {
        val header = source.responseHeaders.header("Content-Range")
        if (header == null && (source as? HttpDataSource)?.responseCode != 206) return null
        return parseRelayContentRange(header)
    }

    private fun Map<String, List<String>>.header(name: String) =
        entries.firstOrNull { it.key.equals(name, true) }?.value?.singleOrNull()?.trim()

    private companion object {
        val STRONG_ETAG = Regex(""""[!#-~\x80-\xFF]*"""")
    }
}

private data class ByteRange(val start: Long, val end: Long, val total: Long?)
private val contentRange = Regex("""bytes (\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)

private fun parseRelayContentRange(header: String?): ByteRange {
    val parts = contentRange.matchEntire(header.orEmpty().trim()) ?: throw IOException("SOURCE_CHANGED")
    val start = parts.groupValues[1].toLongOrNull() ?: throw IOException("SOURCE_CHANGED")
    val end = parts.groupValues[2].toLongOrNull() ?: throw IOException("SOURCE_CHANGED")
    val total = parts.groupValues[3].takeUnless { it == "*" }?.let { it.toLongOrNull() ?: throw IOException("SOURCE_CHANGED") }
    if (end < start || (total != null && end >= total)) throw IOException("SOURCE_CHANGED")
    return ByteRange(start, end, total)
}

/** 分块适配器可能在一次读取中重试短响应，每次都要对照该次实际请求的起点 */
internal fun validateRelayHttpRange(chain: Interceptor.Chain): Response {
    val request = chain.request()
    val response = chain.proceed(request)
    if (response.code != 206) return response
    try {
        val range = parseRelayContentRange(response.header("Content-Range"))
        val requested = request.header("Range")
        val start = requested?.substringAfter("bytes=", "")?.substringBefore('-')?.toLongOrNull()
        if ((requested != null && start == null) || range.start != (start ?: 0L)) throw IOException("SOURCE_CHANGED")
        return response
    } catch (error: IOException) {
        response.close()
        throw error
    }
}
