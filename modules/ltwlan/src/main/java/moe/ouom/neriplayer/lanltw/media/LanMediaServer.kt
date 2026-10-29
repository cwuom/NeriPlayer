package moe.ouom.neriplayer.lanltw.media

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.SynchronousQueue

/** Session-only resource server. Paths come exclusively from the private resource registry. */
class LanMediaServer : NanoHTTPD(0) {
    private data class Resource(val file: File, val mime: String, var readers: Int = 0, var retired: Boolean = false)
    private data class Grant(val peer: String, val streams: MutableSet<InputStream> = mutableSetOf())
    private val resources = mutableMapOf<String, Resource>()
    private val grants = mutableMapOf<String, Grant>()
    private val transfers = Semaphore(2)
    private var stopped = false
    private val retired = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Resource, Boolean>())
    private val runner = BoundedRunner()
    init { setAsyncRunner(runner) }

    @Synchronized fun grant(peer: String): String {
        revoke(peer)
        return UUID.randomUUID().toString().also { grants[it] = Grant(peer) }
    }
    @Synchronized fun revoke(peer: String) {
        val tokens = grants.filterValues { it.peer == peer }.keys.toList()
        tokens.forEach { token -> grants.remove(token)?.streams?.toList()?.forEach { runCatching { it.close() } } }
    }
    @Synchronized fun publish(id: String, file: File, mime: String) {
        require(id.matches(Regex("[a-f0-9-]{36}")) && file.isFile && file.length() > 0)
        check(!stopped) { "LOCAL_TRANSFER" }
        check(resources.values.sumOf { it.file.length() } + retired.sumOf { it.file.length() } + file.length() <= 2 * moe.ouom.neriplayer.data.model.lan.LanLocalMedia.MAX_BYTES + 32L * 1024 * 1024) { "LOCAL_STORAGE" }
        require(id !in resources)
        resources[id] = Resource(file, mime)
    }
    @Synchronized fun retire(id: String) {
        val resource = resources.remove(id) ?: return
        resource.retired = true
        if (resource.readers == 0) resource.file.delete() else retired.add(resource)
    }
    override fun serve(session: IHTTPSession): Response {
        if (session.method != Method.GET && session.method != Method.HEAD) return failure(Response.Status.METHOD_NOT_ALLOWED)
        val id = session.uri.removePrefix("/media/")
        if (session.uri != "/media/$id" || !id.matches(Regex("[a-f0-9-]{36}"))) return failure(Response.Status.NOT_FOUND)
        val token = session.headers["authorization"]?.removePrefix("Bearer ") ?: return failure(Response.Status.UNAUTHORIZED)
        synchronized(this) {
            val grant = grants[token] ?: return failure(Response.Status.UNAUTHORIZED)
            val resource = resources[id] ?: return failure(Response.Status.NOT_FOUND)
            if (session.method == Method.HEAD) return newFixedLengthResponse(Response.Status.OK, resource.mime,
                java.io.ByteArrayInputStream(ByteArray(0)), resource.file.length()).apply { addHeader("Cache-Control", "no-store") }
            if (!transfers.tryAcquire()) return failure(Response.Status.SERVICE_UNAVAILABLE).apply { addHeader("Retry-After", "1") }
            val input = try { resource.file.inputStream() } catch (_: Exception) {
                transfers.release(); return failure(Response.Status.NOT_FOUND)
            }
            resource.readers++
            val stream = object : FilterInputStream(input) {
                private var closed = false
                override fun close() {
                    synchronized(this@LanMediaServer) {
                        if (closed) return
                        closed = true
                        try { super.close() } finally {
                            grant.streams.remove(this); resource.readers--; transfers.release()
                            if (resource.retired && resource.readers == 0) { resource.file.delete(); retired.remove(resource) }
                        }
                    }
                }
            }
            grant.streams.add(stream)
            return newFixedLengthResponse(Response.Status.OK, resource.mime, stream, resource.file.length()).apply {
                addHeader("Cache-Control", "no-store")
            }
        }
    }
    private fun failure(status: Response.Status) = newFixedLengthResponse(status, MIME_PLAINTEXT, status.description)
    override fun stop() {
        synchronized(this) { stopped = true }
        super.stop()
        synchronized(this) {
            grants.values.map { it.peer }.distinct().forEach(::revoke)
            resources.keys.toList().forEach(::retire)
        }
        runner.shutdown()
    }
    private class BoundedRunner : AsyncRunner {
        private val clients = ConcurrentHashMap.newKeySet<ClientHandler>()
        private val pool = ThreadPoolExecutor(0, 4, 20, TimeUnit.SECONDS, SynchronousQueue<Runnable>(),
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
}
