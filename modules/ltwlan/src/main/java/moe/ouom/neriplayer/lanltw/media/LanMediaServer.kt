package moe.ouom.neriplayer.lanltw.media

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.PrintWriter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.SynchronousQueue

/** Session-only resource server. Paths come exclusively from the private resource registry. */
class LanMediaServer : NanoHTTPD(0) {
    private data class Resource(
        val byteLength: Long?, val mime: String, val transfers: Semaphore, val storageBytes: Long,
        val progressive: Boolean, val open: (Long, Long?) -> LanMediaStreamInput, val cleanup: () -> Unit,
        var readers: Int = 0, var retired: Boolean = false
    )
    private data class Grant(val peer: String, val streams: MutableMap<LanMediaTransfer, Resource> = mutableMapOf())
    private data class Body(val input: InputStream, val length: Long?, val contentRange: String? = null)
    private val resources = mutableMapOf<String, Resource>()
    private val grants = mutableMapOf<String, Grant>()
    private val audioTransfers = Semaphore(2)
    private val presentationTransfers = Semaphore(2)
    private val progressiveTransfers = Semaphore(MAX_PROGRESSIVE_TRANSFERS)
    private var stopped = false
    private val retired = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Resource, Boolean>())
    private val runner = BoundedRunner()
    init { setAsyncRunner(runner) }

    fun grant(peer: String): String {
        val token = UUID.randomUUID().toString()
        val previous = synchronized(this) {
            check(!stopped) { "LOCAL_TRANSFER" }
            detachGrants(peer).also { grants[token] = Grant(peer) }
        }
        previous.forEach(::cancel)
        return token
    }

    fun revoke(peer: String) {
        synchronized(this) { detachGrants(peer) }.forEach(::cancel)
    }

    private fun detachGrants(peer: String): List<LanMediaTransfer> {
        val tokens = grants.filterValues { it.peer == peer }.keys.toList()
        return tokens.flatMap { grants.remove(it)?.streams?.keys.orEmpty() }
    }

    fun publish(id: String, file: File, mime: String) {
        require(file.isFile && file.length() > 0)
        registerSnapshot(id, LanReadableMedia.file(file), mime) { file.delete() }
    }

    fun publishBorrowed(id: String, source: LanReadableMedia, mime: String) = registerSnapshot(id, source, mime) {}

    /** opens an independent data source for each HTTP request, with a null length reading through EOF */
    fun publishStream(id: String, mime: String, byteLength: Long?, open: (offset: Long, length: Long?) -> LanMediaStreamInput) {
        require(byteLength == null || byteLength > 0)
        register(id, Resource(byteLength, mime, progressiveTransfers, 0, progressive = true, open = open, cleanup = {}))
    }

    private fun registerSnapshot(id: String, source: LanReadableMedia, mime: String, cleanup: () -> Unit) {
        require(source.byteLength > 0)
        val presentation = source.byteLength <= LanPresentationCodec.MAX_BYTES &&
            (mime == "application/json" || mime == "image/png")
        val transfers = if (presentation) presentationTransfers else audioTransfers
        register(id, Resource(source.byteLength, mime, transfers, source.byteLength, progressive = false,
            open = { _, _ -> LanMediaStreamInput(source.open(), source.byteLength) }, cleanup = cleanup))
    }

    @Synchronized private fun register(id: String, resource: Resource) {
        require(id.matches(resourceId) && resource.mime.isNotBlank() && resource.mime.all { it in ' '..'~' })
        check(!stopped) { "LOCAL_TRANSFER" }
        val available = SNAPSHOT_BUDGET - resources.values.sumOf { it.storageBytes } - retired.sumOf { it.storageBytes }
        check(resource.storageBytes <= available) { "LOCAL_STORAGE" }
        require(id !in resources)
        resources[id] = resource
    }

    fun retire(id: String) {
        val active = synchronized(this) {
            val resource = resources.remove(id) ?: return
            retireResource(resource)
            if (resource.progressive) {
                grants.values.flatMap { grant -> grant.streams.filterValues { it === resource }.keys }
            } else emptyList()
        }
        active.forEach(::cancel)
    }

    private fun retireResource(resource: Resource) {
        resource.retired = true
        if (resource.readers == 0) resource.cleanup() else retired.add(resource)
    }

    override fun serve(session: IHTTPSession): Response =
        serveResource(session).apply {
            closeConnection(true)
            addHeader("Cache-Control", "no-store")
        }

    override fun useGzipWhenAccepted(r: Response) = false

    private fun serveResource(session: IHTTPSession): Response {
        if (session.method != Method.GET && session.method != Method.HEAD) return failure(Response.Status.METHOD_NOT_ALLOWED)
        val id = session.uri.removePrefix("/media/")
        if (session.uri != "/media/$id" || !id.matches(resourceId)) return failure(Response.Status.NOT_FOUND)
        val authorization = session.headers["authorization"] ?: return unauthorized()
        if (!authorization.startsWith("Bearer ", ignoreCase = true)) return unauthorized()
        val token = authorization.substring("Bearer ".length)
        val (resource, transfer) = synchronized(this) {
            val grant = grants[token] ?: return unauthorized()
            val resource = resources[id] ?: return failure(Response.Status.NOT_FOUND)
            if (session.method == Method.HEAD) return head(resource)
            val transfer = acquire(grant, resource) ?: return busy()
            resource to transfer
        }
        val range = if (resource.progressive && session.headers["if-range"] == null) {
            LanMediaByteRange.parse(session.headers["range"])
        } else null
        return serveTransfer(token, resource, transfer, range)
    }

    private fun acquire(grant: Grant, resource: Resource): LanMediaTransfer? {
        if (resource.progressive && grant.streams.values.count { it.progressive } >= MAX_STREAMS_PER_MEMBER) return null
        if (!resource.transfers.tryAcquire()) return null
        resource.readers++
        return LanMediaTransfer(resource.open) { transfer ->
            synchronized(this) {
                grant.streams.remove(transfer)
                resource.readers--
                resource.transfers.release()
                if (resource.retired && resource.readers == 0) {
                    retired.remove(resource)
                    resource.cleanup()
                }
            }
        }.also { grant.streams[it] = resource }
    }

    private fun head(resource: Resource): Response {
        val empty = ByteArrayInputStream(byteArrayOf())
        val response = resource.byteLength?.let {
            newFixedLengthResponse(Response.Status.OK, resource.mime, empty, it)
        } ?: object : Response(Response.Status.OK, resource.mime, empty, 0) {
            // omit the unknown length while allowing NanoHTTPD to send the other HEAD headers
            override fun sendContentLengthHeaderIfNotAlreadyPresent(pw: PrintWriter, defaultSize: Long): Long = 0
        }
        return withRangeSupport(response, resource)
    }

    private fun serveTransfer(token: String, resource: Resource, transfer: LanMediaTransfer, range: LanMediaByteRange?): Response {
        try {
            val body = openBody(resource, transfer, range)
            if (body == null) {
                transfer.close()
                return failure(Response.Status.RANGE_NOT_SATISFIABLE).apply {
                    resource.byteLength?.let { addHeader("Content-Range", "bytes */$it") }
                }
            }
            val status = if (body.contentRange == null) Response.Status.OK else Response.Status.PARTIAL_CONTENT
            val stream = transfer.responseBody(body.input)
            val response = body.length?.let { newFixedLengthResponse(status, resource.mime, stream, it) }
                ?: newChunkedResponse(status, resource.mime, stream)
            body.contentRange?.let { response.addHeader("Content-Range", it) }
            return withRangeSupport(response, resource)
        } catch (_: Exception) {
            val cancelled = transfer.cancelled
            runCatching { transfer.close() }
            if (cancelled && synchronized(this) { token !in grants }) return unauthorized()
            return if (resource.progressive && !cancelled) busy() else failure(Response.Status.NOT_FOUND)
        }
    }

    private fun openBody(resource: Resource, transfer: LanMediaTransfer, requested: LanMediaByteRange?): Body? {
        val total = resource.byteLength
        if (requested == null) return fullBody(transfer, total)
        if (total != null) {
            val range = requested.resolve(total) ?: return null
            val length = range.last - range.first + 1
            return rangeBody(openChecked(transfer, range.first, length), range, total)
        }
        val offset = requested.first ?: return fullBody(transfer, null)
        val probe = try {
            transfer.open(offset, null)
        } catch (error: Exception) {
            if (transfer.cancelled || offset == 0L) throw error
            return fullBody(transfer, null)
        }
        val remaining = probe.byteLength
        if (remaining != null && remaining in 0..(Long.MAX_VALUE - offset)) {
            val discoveredTotal = offset + remaining
            val range = requested.resolve(discoveredTotal) ?: return null
            return rangeBody(probe, range, discoveredTotal)
        }
        // a 206 response needs the final byte position before the body starts
        if (offset == 0L) return Body(probe.stream, null)
        transfer.discardInput()
        return fullBody(transfer, null)
    }

    private fun fullBody(transfer: LanMediaTransfer, length: Long?): Body {
        val input = openChecked(transfer, 0, length)
        return Body(input.stream, length ?: input.byteLength)
    }

    private fun openChecked(transfer: LanMediaTransfer, offset: Long, length: Long?): LanMediaStreamInput =
        transfer.open(offset, length).also { input ->
            check(input.byteLength == null || input.byteLength >= (length ?: 0)) { "Incomplete media source" }
        }

    private fun rangeBody(input: LanMediaStreamInput, range: LongRange, total: Long) =
        Body(input.stream, range.last - range.first + 1, "bytes ${range.first}-${range.last}/$total")

    private fun withRangeSupport(response: Response, resource: Resource): Response = response.apply {
        if (resource.progressive) addHeader("Accept-Ranges", "bytes")
    }

    private fun unauthorized() = failure(Response.Status.UNAUTHORIZED).apply { addHeader("WWW-Authenticate", "Bearer") }
    private fun busy() = failure(Response.Status.SERVICE_UNAVAILABLE).apply { addHeader("Retry-After", "1") }
    private fun failure(status: Response.Status) = newFixedLengthResponse(status, MIME_PLAINTEXT, status.description)
    private fun cancel(transfer: LanMediaTransfer) { runCatching { transfer.cancel() } }

    override fun stop() {
        val transfers = synchronized(this) {
            if (stopped) return
            stopped = true
            val active = grants.values.flatMap { it.streams.keys }
            grants.clear()
            resources.values.forEach(::retireResource)
            resources.clear()
            active
        }
        try {
            super.stop()
        } finally {
            try {
                transfers.forEach(::cancel)
            } finally {
                runner.shutdown()
            }
        }
    }

    private class BoundedRunner : AsyncRunner {
        private val clients = ConcurrentHashMap.newKeySet<ClientHandler>()
        // leave workers available for metadata and short backpressure replies in a full room
        private val pool = ThreadPoolExecutor(0, MAX_HTTP_WORKERS, 20, TimeUnit.SECONDS, SynchronousQueue<Runnable>(),
            { task -> Thread(task, "lan-media").apply { isDaemon = true } })
        override fun exec(clientHandler: ClientHandler) {
            clients.add(clientHandler)
            try { pool.execute(clientHandler) } catch (_: java.util.concurrent.RejectedExecutionException) {
                clients.remove(clientHandler); clientHandler.close()
            }
        }
        override fun closed(clientHandler: ClientHandler) { clients.remove(clientHandler) }
        override fun closeAll() { clients.toList().forEach { it.close() }; clients.clear() }
        fun shutdown() { closeAll(); pool.shutdownNow() }
    }

    private companion object {
        val resourceId = Regex("[a-f0-9-]{36}")
        const val MAX_PROGRESSIVE_TRANSFERS = 16
        const val MAX_STREAMS_PER_MEMBER = 2
        const val MAX_HTTP_WORKERS = 24
        const val SNAPSHOT_BUDGET = 2 * moe.ouom.neriplayer.data.model.lan.LanLocalMedia.MAX_BYTES + 32L * 1024 * 1024
    }
}
