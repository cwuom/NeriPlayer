@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.lan.relay

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.Locale
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Request

internal class LanRelayOrigin private constructor(
    val mime: String,
    val byteLength: Long?,
    val window: LanRelayWindow
) : Closeable {
    override fun close() = window.close()

    companion object {
        fun open(factory: DataSource.Factory, uri: Uri, localMime: String? = null): LanRelayOrigin =
            open(uri, localMime, { RelaySource(factory.createDataSource()) }, {})

        suspend fun openRemote(
            factory: (Call.Factory) -> DataSource.Factory,
            calls: Call.Factory,
            uri: Uri,
            localMime: String? = null
        ): LanRelayOrigin = coroutineScope {
            suspendCancellableCoroutine { continuation ->
                val sources = RelayHttpSources(factory, calls)
                continuation.invokeOnCancellation { sources.cancel() }
                launch(Dispatchers.IO) {
                    try {
                        val origin = open(uri, localMime, sources::create, sources::cancel)
                        continuation.resume(origin) { _, discarded, _ -> discarded.close() }
                    } catch (error: Throwable) {
                        sources.cancel()
                        continuation.resumeWithException(error)
                    }
                }
            }
        }

        private fun open(
            uri: Uri,
            localMime: String?,
            create: () -> RelaySource,
            stopUpstream: () -> Unit
        ): LanRelayOrigin {
            val file = uri.path?.takeIf { uri.scheme == "file" }?.let(::File)
            val modified = file?.lastModified()
            val fileSize = file?.length()
            val verify = {
                if (file != null && (!file.isFile || file.lastModified() != modified || file.length() != fileSize)) {
                    throw IOException("SOURCE_CHANGED")
                }
            }
            val initial = create()
            try {
                val size = initial.dataSource.open(DataSpec(uri)).takeIf { it >= 0 }
                check(size != 0L) { "AUDIO_FORMAT" }
                val mime = initial.dataSource.responseHeaders.entries.firstOrNull { it.key.equals("Content-Type", true) }
                    ?.value?.firstOrNull()?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: localMime.orEmpty()
                check(progressiveMediaType(mime) && !uri.path.orEmpty().endsWith(".m3u8", true)) { "AUDIO_FORMAT" }
                val audioMime = when (mime) {
                    "video/mp4" -> "audio/mp4"
                    "video/webm" -> "audio/webm"
                    else -> mime.takeIf { it.matches(Regex("audio/[a-zA-Z0-9.+_-]{1,100}")) } ?: "application/octet-stream"
                }
                val first = readProgressivePrefix(RelayInput(initial, verify))
                val window = LanRelayWindow(size, first, stopUpstream) { offset ->
                    verify()
                    val source = create()
                    try {
                        source.dataSource.open(DataSpec.Builder().setUri(uri).setPosition(offset).build())
                        RelayInput(source, verify)
                    } catch (error: Throwable) {
                        runCatching { source.close() }
                        throw error
                    }
                }
                return LanRelayOrigin(audioMime, size, window)
            } catch (error: Throwable) {
                runCatching { initial.close() }
                throw error
            }
        }
    }
}

internal fun progressiveMediaType(mime: String): Boolean = !mime.startsWith("text/") &&
    !mime.startsWith("image/") && !mime.contains("mpegurl") &&
    !mime.endsWith("xml") && !mime.endsWith("json")

internal fun readProgressivePrefix(input: InputStream): InputStream {
    val bytes = ByteArray(32)
    val result = PushbackInputStream(input, bytes.size)
    var count = 0
    while (count < bytes.size) {
        val read = result.read(bytes, count, bytes.size - count)
        if (read <= 0) break
        count += read
    }
    val prefix = String(bytes, 0, count, Charsets.UTF_8).removePrefix("\uFEFF").trimStart()
    check(count > 0 && !prefix.startsWith("#EXTM3U") && !prefix.startsWith('<') &&
        !prefix.startsWith('{') && !prefix.startsWith('[')) { "AUDIO_FORMAT" }
    result.unread(bytes, 0, count)
    return result
}

private class RelayInput(private val source: RelaySource, private val verify: () -> Unit) : InputStream() {
    private var closed = false
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (closed) throw IOException("LOCAL_TRANSFER")
        verify()
        return source.dataSource.read(b, off, len)
    }
    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
    }
    override fun close() {
        if (closed) return
        closed = true
        source.close()
    }
}

private class RelaySource(val dataSource: DataSource, private val release: () -> Unit = {}) : Closeable {
    override fun close() {
        try { dataSource.close() } finally { release() }
    }
}

private class RelayHttpSources(
    private val factory: (Call.Factory) -> DataSource.Factory,
    private val upstream: Call.Factory
) {
    private val active = mutableSetOf<RelayHttpCall>()
    private var cancelled = false

    fun create(): RelaySource {
        val call = synchronized(this) {
            if (cancelled) throw IOException("LOCAL_TRANSFER")
            RelayHttpCall(upstream).also { active.add(it) }
        }
        try {
            return RelaySource(factory(call).createDataSource()) { release(call) }
        } catch (error: Throwable) {
            release(call)
            throw error
        }
    }

    private fun release(call: RelayHttpCall) {
        synchronized(this) { active.remove(call) }
        call.cancel()
    }

    fun cancel() {
        val calls = synchronized(this) {
            cancelled = true
            active.toList().also { active.clear() }
        }
        calls.forEach(RelayHttpCall::cancel)
    }
}

internal class RelayHttpCall(private val upstream: Call.Factory) : Call.Factory {
    private var current: Call? = null
    private var cancelled = false

    @Synchronized
    override fun newCall(request: Request): Call = upstream.newCall(request).also { call ->
        // a source may create its next chunk request while cancellation reaches the previous one
        if (cancelled) call.cancel() else current = call
    }

    fun cancel() {
        val call = synchronized(this) {
            cancelled = true
            current.also { current = null }
        }
        call?.cancel()
    }
}
