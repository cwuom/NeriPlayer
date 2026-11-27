package moe.ouom.neriplayer.core.player.dlna.server

import moe.ouom.neriplayer.core.player.dlna.protocol.UpnpXmlParser
import moe.ouom.neriplayer.core.player.dlna.session.monotonicBootElapsedMs
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hardened embedded LAN HTTP/1.1 media & artwork server for lossless DLNA streaming.
 *
 * Security & protocol guarantees:
 * - Binds on ephemeral port, enforces private/link-local LAN peer IP check.
 * - Exposes only unpredictable 128-bit UUID resource tokens; never filesystem paths or upstream URLs.
 * - Rejects path traversal, whitespace/control chars, query spoofing, duplicate/malformed Range headers.
 * - Supports GET, HEAD, 200 OK, 206 Partial Content, 416 Range Not Satisfiable, 410 Gone, 405.
 * - Supports both raw file streaming (FLAC/WAV) and hybrid WAV+ID3 header prefix + PCM file streaming
 *   with exact dynamic byte offsets across header/ID3/PCM boundaries.
 */
class DlnaMediaServer(
    private val onGenaNotify: (subscriptionSid: String, values: Map<String, String>) -> Unit = { _, _ -> },
    private val onLog: (String) -> Unit = {}
) {
    companion object {
        private const val MAX_HEADER_BYTES = 8192
        private const val HEADER_READ_DEADLINE_MS = 15_000L
        private const val STREAM_BUFFER_BYTES = 64 * 1024
        private const val MAX_ARTWORK_BYTES = 8 * 1024 * 1024
        private const val MAX_ARTWORK_COUNT = 8
        private const val MAX_ARTWORK_CACHE_BYTES = 24 * 1024 * 1024
        private const val SERVER_BANNER = "NeriPlayer/1.0 UPnP/1.0 DLNADOC/1.50"
    }

    sealed class StreamContent {
        abstract val totalBytes: Long
        abstract val contentType: String
        abstract val contentFeatures: String
        abstract val ephemeralFileToDeleteOnUnregister: File?

        data class FileContent(
            val file: File,
            override val contentType: String,
            override val contentFeatures: String,
            override val ephemeralFileToDeleteOnUnregister: File? = null
        ) : StreamContent() {
            override val totalBytes: Long = file.length()
        }

        data class HybridWavContent(
            val headerPrefix: ByteArray,
            val pcmFile: File,
            val pcmDataOffsetInFile: Long = 0L,
            val pcmDataLength: Long = (pcmFile.length() - pcmDataOffsetInFile).coerceAtLeast(0L),
            override val contentType: String = "audio/wav",
            override val contentFeatures: String = "",
            override val ephemeralFileToDeleteOnUnregister: File? = null
        ) : StreamContent() {
            override val totalBytes: Long = headerPrefix.size.toLong() + pcmDataLength + (pcmDataLength and 1L)
        }
    }

    private data class ArtworkEntry(
        val path: String,
        val mimeType: String,
        val bytes: ByteArray
    )

    internal sealed class RangeParseResult {
        data class Full(val start: Long, val end: Long) : RangeParseResult()
        data class Partial(val start: Long, val end: Long) : RangeParseResult()
        data object Unsatisfiable : RangeParseResult()
    }

    private val running = AtomicBoolean(false)
    @Volatile
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var acceptThread: Thread? = null
    @Volatile
    private var executor: ExecutorService? = null

    private val streams = ConcurrentHashMap<String, StreamContent>()
    private val retiredTokens = ConcurrentHashMap.newKeySet<String>()
    private val activeClientSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val activeGenaSids = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    var allowedPeerHost: String? = null
    @Volatile
    var expectedGenaCallbackPath: String? = null
    @Volatile
    var genaSubscriptionInFlight: Boolean = false
    @Volatile
    var lastPeerActivityBootMs: Long = 0L
        private set

    private fun markPeerActivity() {
        lastPeerActivityBootMs = monotonicBootElapsedMs()
    }
    private val artworkLock = Any()
    private val artworkQueue = ArrayDeque<ArtworkEntry>()
    private var artworkTotalBytes = 0

    val port: Int
        get() = serverSocket?.localPort ?: 0

    val isRunning: Boolean
        get() = running.get() && serverSocket?.isClosed == false

    @Synchronized
    fun start(): Int {
        if (isRunning) return port
        val socket = ServerSocket(0, 32, InetAddress.getByName("0.0.0.0"))
        val pool = ThreadPoolExecutor(
            2,
            12,
            30L,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(24)
        ) { runnable ->
            Thread(runnable, "NeriDlnaHttpWorker").apply { isDaemon = true }
        }
        serverSocket = socket
        executor = pool
        running.set(true)
        val localPort = socket.localPort
        acceptThread = Thread({
            while (running.get()) {
                val client = try {
                    socket.accept()
                } catch (_: SocketException) {
                    break
                } catch (_: IOException) {
                    if (!running.get()) break else continue
                }
                if (!running.get()) {
                    runCatching { client.close() }
                    break
                }
                activeClientSockets.add(client)
                try {
                    pool.execute { handleClient(client) }
                } catch (_: RejectedExecutionException) {
                    activeClientSockets.remove(client)
                    runCatching { client.close() }
                }
            }
        }, "NeriDlnaHttpAccept").apply {
            isDaemon = true
            start()
        }
        onLog("DlnaMediaServer started on port $localPort")
        return localPort
    }

    @Synchronized
    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        activeClientSockets.toList().forEach { sock ->
            runCatching { sock.close() }
        }
        activeClientSockets.clear()
        executor?.shutdownNow()
        executor = null
        val ephemeralFiles = streams.values.mapNotNull { it.ephemeralFileToDeleteOnUnregister }
        streams.clear()
        ephemeralFiles.forEach { file -> runCatching { file.delete() } }
        retiredTokens.clear()
        activeGenaSids.clear()
        expectedGenaCallbackPath = null
        genaSubscriptionInFlight = false
        allowedPeerHost = null
        lastPeerActivityBootMs = 0L
        synchronized(artworkLock) {
            artworkQueue.clear()
            artworkTotalBytes = 0
        }
    }

    fun registerGenaSubscription(sid: String) {
        if (sid.isNotBlank()) activeGenaSids.add(sid)
    }

    fun unregisterGenaSubscription(sid: String) {
        if (sid.isNotBlank()) activeGenaSids.remove(sid)
    }

    fun clearGenaSubscriptions() {
        activeGenaSids.clear()
        expectedGenaCallbackPath = null
        genaSubscriptionInFlight = false
    }

    fun registerStream(extension: String, content: StreamContent): String {
        val cleanExt = extension.trimStart('.').lowercase(Locale.US).ifBlank { "bin" }
        val token = UUID.randomUUID().toString().replace("-", "")
        val path = "/dlna/stream/$token.$cleanExt"
        streams[path] = content
        return path
    }

    fun unregisterStream(path: String?) {
        if (path.isNullOrBlank()) return
        val removed = streams.remove(path)
        if (removed != null) {
            removed.ephemeralFileToDeleteOnUnregister?.let { file ->
                runCatching { file.delete() }
            }
            retiredTokens.add(path)
            if (retiredTokens.size > 256) {
                val iter = retiredTokens.iterator()
                if (iter.hasNext()) {
                    iter.next()
                    iter.remove()
                }
            }
        }
    }

    fun getStreamBackingFile(path: String?): File? {
        if (path.isNullOrBlank()) return null
        return when (val content = streams[path]) {
            is StreamContent.FileContent -> content.file
            is StreamContent.HybridWavContent -> content.pcmFile
            null -> null
        }
    }

    fun registerArtwork(bytes: ByteArray): String? {
        if (bytes.isEmpty() || bytes.size > MAX_ARTWORK_BYTES) return null
        val mime = detectImageMime(bytes) ?: return null
        val ext = if (mime == "image/png") "png" else "jpg"
        val path = "/dlna/artwork/${UUID.randomUUID().toString().replace("-", "")}.$ext"
        val entry = ArtworkEntry(path = path, mimeType = mime, bytes = bytes.copyOf())
        synchronized(artworkLock) {
            artworkQueue.addLast(entry)
            artworkTotalBytes += entry.bytes.size
            while (artworkQueue.size > MAX_ARTWORK_COUNT || artworkTotalBytes > MAX_ARTWORK_CACHE_BYTES) {
                val evicted = artworkQueue.removeFirstOrNull() ?: break
                artworkTotalBytes -= evicted.bytes.size
            }
        }
        return path
    }

    fun buildUrl(hostIp: String, path: String): String {
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        return "http://$hostIp:$port$cleanPath"
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.use { client ->
                runCatching {
                    client.soTimeout = 10_000
                    client.tcpNoDelay = false
                    if (!DlnaLanAddressResolver.isAuthorizedClient(client.inetAddress, allowedPeerHost)) {
                        sendSimpleResponse(client.getOutputStream(), "403 Forbidden")
                        return
                    }
                    val input = BufferedInputStream(client.getInputStream(), 8192)
                    val out = client.getOutputStream()
                    val headerBlock = readHeaderBlock(input) ?: run {
                        sendSimpleResponse(out, "400 Bad Request")
                        return
                    }
                    val lines = headerBlock.split("\r\n")
                    val requestLine = lines.firstOrNull().orEmpty()
                    val parts = requestLine.split(' ')
                    if (parts.size != 3) {
                        sendSimpleResponse(out, "400 Bad Request")
                        return
                    }
                    val method = parts[0].uppercase(Locale.US)
                    val rawTarget = parts[1]
                    val httpVersion = parts[2]
                    if (httpVersion != "HTTP/1.1" && httpVersion != "HTTP/1.0") {
                        sendSimpleResponse(out, "400 Bad Request")
                        return
                    }
                    val cleanPath = sanitizeOriginPath(rawTarget) ?: run {
                        sendSimpleResponse(out, "400 Bad Request")
                        return
                    }
                    if (method == "NOTIFY") {
                        markPeerActivity()
                        handleGenaNotify(cleanPath, lines.drop(1), input, out)
                        return
                    }
                    if (method != "GET" && method != "HEAD") {
                        sendSimpleResponse(out, "405 Method Not Allowed", allowHeader = "GET, HEAD")
                        return
                    }
                    val isHead = method == "HEAD"
                    if (cleanPath.startsWith("/dlna/artwork/")) {
                        markPeerActivity()
                        serveArtwork(cleanPath, isHead, out)
                        return
                    }
                    if (cleanPath.startsWith("/dlna/stream/")) {
                        markPeerActivity()
                        serveStream(cleanPath, lines.drop(1), isHead, out)
                        return
                    }
                    sendSimpleResponse(out, "404 Not Found")
                }
            }
        } finally {
            activeClientSockets.remove(socket)
        }
    }

    private fun handleGenaNotify(
        callbackPath: String,
        headerLines: List<String>,
        input: BufferedInputStream,
        out: OutputStream
    ) {
        val expectedPath = expectedGenaCallbackPath
        if (expectedPath.isNullOrBlank() || callbackPath != expectedPath) {
            sendSimpleResponse(out, "404 Not Found")
            return
        }
        var contentLength = 0
        var sid = ""
        for (line in headerLines) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase(Locale.US)
            val value = line.substring(colon + 1).trim()
            if (name == "content-length") contentLength = value.toIntOrNull()?.coerceIn(0, 128 * 1024) ?: 0
            if (name == "sid") sid = value
        }
        val bodyBytes = ByteArray(contentLength)
        var readTotal = 0
        while (readTotal < contentLength) {
            val read = input.read(bodyBytes, readTotal, contentLength - readTotal)
            if (read <= 0) break
            readTotal += read
        }
        val knownSids = activeGenaSids
        val sidAccepted = sid.isNotBlank() && (
            sid in knownSids || (knownSids.isEmpty() && genaSubscriptionInFlight)
        )
        if (!sidAccepted) {
            sendSimpleResponse(out, "412 Precondition Failed")
            return
        }
        if (readTotal > 0) {
            val xml = String(bodyBytes, 0, readTotal, Charsets.UTF_8)
            val parsed = UpnpXmlParser.parseGenaLastChange(xml)
            if (parsed.isNotEmpty()) {
                onGenaNotify(sid, parsed)
            }
        }
        val response = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        out.write(response.toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    private fun serveArtwork(path: String, isHead: Boolean, out: OutputStream) {
        val entry = synchronized(artworkLock) {
            artworkQueue.firstOrNull { it.path == path }
        }
        if (entry == null) {
            sendSimpleResponse(out, "404 Not Found")
            return
        }
        val headers = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Server: $SERVER_BANNER\r\n")
            append("Content-Type: ${entry.mimeType}\r\n")
            append("Content-Length: ${entry.bytes.size}\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Cache-Control: private, max-age=3600, immutable\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(headers.toByteArray(Charsets.US_ASCII))
        if (!isHead) {
            out.write(entry.bytes)
        }
        out.flush()
    }

    private fun serveStream(
        path: String,
        headerLines: List<String>,
        isHead: Boolean,
        out: OutputStream
    ) {
        val content = streams[path]
        if (content == null) {
            if (path in retiredTokens) {
                val gone = "HTTP/1.1 410 Gone\r\nRetry-After: 5\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                out.write(gone.toByteArray(Charsets.US_ASCII))
                out.flush()
            } else {
                sendSimpleResponse(out, "404 Not Found")
            }
            return
        }
        val total = content.totalBytes
        if (total <= 0L) {
            sendSimpleResponse(out, "404 Not Found")
            return
        }
        val rangeResult = parseRangeHeaders(headerLines, total)
        val (statusLine, start, end, isPartial) = when (rangeResult) {
            is RangeParseResult.Full -> Quad("200 OK", rangeResult.start, rangeResult.end, false)
            is RangeParseResult.Partial -> Quad("206 Partial Content", rangeResult.start, rangeResult.end, true)
            RangeParseResult.Unsatisfiable -> {
                val resp = "HTTP/1.1 416 Range Not Satisfiable\r\n" +
                    "Content-Range: bytes */$total\r\n" +
                    "Content-Length: 0\r\n" +
                    "Connection: close\r\n\r\n"
                out.write(resp.toByteArray(Charsets.US_ASCII))
                out.flush()
                return
            }
        }

        val length = end - start + 1L
        val headerText = buildString {
            append("HTTP/1.1 $statusLine\r\n")
            append("Server: $SERVER_BANNER\r\n")
            append("Content-Type: ${content.contentType}\r\n")
            append("Content-Length: $length\r\n")
            if (isPartial) {
                append("Content-Range: bytes $start-$end/$total\r\n")
            }
            append("Accept-Ranges: bytes\r\n")
            append("transferMode.dlna.org: Streaming\r\n")
            if (content.contentFeatures.isNotBlank()) {
                append("contentFeatures.dlna.org: ${content.contentFeatures}\r\n")
            }
            append("X-Content-Type-Options: nosniff\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(headerText.toByteArray(Charsets.US_ASCII))
        if (!isHead) {
            writeContentRange(content, start, length, out)
        }
        out.flush()
    }

    private fun writeContentRange(
        content: StreamContent,
        start: Long,
        length: Long,
        out: OutputStream
    ) {
        when (content) {
            is StreamContent.FileContent -> {
                streamFileRange(content.file, start, length, out)
            }
            is StreamContent.HybridWavContent -> {
                var cursor = start
                var remaining = length
                val prefixLen = content.headerPrefix.size.toLong()
                if (cursor < prefixLen && remaining > 0L) {
                    val take = minOf(prefixLen - cursor, remaining).toInt()
                    out.write(content.headerPrefix, cursor.toInt(), take)
                    markPeerActivity()
                    cursor += take
                    remaining -= take
                }
                val pcmEnd = prefixLen + content.pcmDataLength
                if (cursor < pcmEnd && remaining > 0L) {
                    val pcmTake = minOf(pcmEnd - cursor, remaining)
                    val pcmOffset = content.pcmDataOffsetInFile + (cursor - prefixLen)
                    streamFileRange(content.pcmFile, pcmOffset, pcmTake, out)
                    cursor += pcmTake
                    remaining -= pcmTake
                }
                if (remaining > 0L) {
                    out.write(0)
                }
            }
        }
    }

    private fun streamFileRange(file: File, offset: Long, length: Long, out: OutputStream) {
        val buf = ByteArray(STREAM_BUFFER_BYTES)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            var remaining = length
            while (remaining > 0L && running.get()) {
                val want = minOf(buf.size.toLong(), remaining).toInt()
                val read = raf.read(buf, 0, want)
                if (read <= 0) break
                out.write(buf, 0, read)
                markPeerActivity()
                remaining -= read
            }
        }
    }

    internal fun parseRangeHeaders(headerLines: List<String>, total: Long): RangeParseResult {
        if (total <= 0L) return RangeParseResult.Unsatisfiable
        var rangeValue: String? = null
        for (line in headerLines) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim()
            if (name.equals("Range", ignoreCase = true)) {
                if (rangeValue != null) {
                    // Reject duplicate Range headers
                    return RangeParseResult.Unsatisfiable
                }
                rangeValue = line.substring(colon + 1).trim()
            }
        }
        val raw = rangeValue ?: return RangeParseResult.Full(0L, total - 1L)
        if (!raw.startsWith("bytes=", ignoreCase = true)) return RangeParseResult.Unsatisfiable
        val spec = raw.substring(6).trim()
        if (spec.contains(',')) return RangeParseResult.Unsatisfiable // reject multi-range
        val dash = spec.indexOf('-')
        if (dash < 0) return RangeParseResult.Unsatisfiable
        val left = spec.substring(0, dash).trim()
        val right = spec.substring(dash + 1).trim()

        return if (left.isEmpty()) {
            val suffix = right.toLongOrNull()?.takeIf { it > 0L } ?: return RangeParseResult.Unsatisfiable
            val start = maxOf(0L, total - suffix)
            RangeParseResult.Partial(start, total - 1L)
        } else {
            val start = left.toLongOrNull()?.takeIf { it >= 0L && it < total }
                ?: return RangeParseResult.Unsatisfiable
            val end = if (right.isEmpty()) {
                total - 1L
            } else {
                val parsedEnd = right.toLongOrNull() ?: return RangeParseResult.Unsatisfiable
                if (parsedEnd < start) return RangeParseResult.Unsatisfiable
                minOf(parsedEnd, total - 1L)
            }
            RangeParseResult.Partial(start, end)
        }
    }

    internal fun sanitizeOriginPath(rawTarget: String): String? {
        if (!rawTarget.startsWith("/")) return null
        val noQuery = rawTarget.substringBefore('?')
        if (noQuery.isEmpty() || noQuery.contains("..") || noQuery.contains('\\') || noQuery.contains('#')) {
            return null
        }
        for (ch in noQuery) {
            if (ch.code <= 0x20 || ch.code >= 0x7F) return null
        }
        return noQuery
    }

    // soTimeout 只限制单次 read，每 9 秒送 1 字节就能无限占住工作线程；这里再加整段请求头的总时限
    internal fun readHeaderBlock(
        input: BufferedInputStream,
        deadlineNanos: Long = System.nanoTime() + HEADER_READ_DEADLINE_MS * 1_000_000L
    ): String? {
        val bytes = ByteArray(MAX_HEADER_BYTES)
        var count = 0
        while (count < MAX_HEADER_BYTES) {
            if (System.nanoTime() - deadlineNanos > 0L) return null
            val b = input.read()
            if (b < 0) break
            bytes[count++] = b.toByte()
            if (count >= 4 &&
                bytes[count - 4] == '\r'.code.toByte() &&
                bytes[count - 3] == '\n'.code.toByte() &&
                bytes[count - 2] == '\r'.code.toByte() &&
                bytes[count - 1] == '\n'.code.toByte()
            ) {
                return String(bytes, 0, count - 4, Charsets.US_ASCII)
            }
        }
        return null
    }

    private fun sendSimpleResponse(
        out: OutputStream,
        status: String,
        allowHeader: String? = null
    ) {
        val text = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Server: $SERVER_BANNER\r\n")
            append("Content-Length: 0\r\n")
            if (allowHeader != null) append("Allow: $allowHeader\r\n")
            append("Connection: close\r\n\r\n")
        }
        runCatching {
            out.write(text.toByteArray(Charsets.US_ASCII))
            out.flush()
        }
    }

    private fun detectImageMime(bytes: ByteArray): String? {
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() &&
            bytes[4] == 0x0D.toByte() && bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() && bytes[7] == 0x0A.toByte()
        ) {
            return "image/png"
        }
        if (bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        ) {
            return "image/jpeg"
        }
        return null
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
