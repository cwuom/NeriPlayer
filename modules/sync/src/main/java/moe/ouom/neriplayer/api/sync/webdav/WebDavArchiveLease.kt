package moe.ouom.neriplayer.api.sync.webdav

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.w3c.dom.Element

class WebDavArchiveLeaseLostException(message: String) : IOException(message)
private class WebDavUnsafeArchiveLeaseException : IOException("WebDAV archive requires a finite lease of at most 300 seconds")

class WebDavArchiveLease internal constructor(
    val root: HttpUrl,
    private val token: String,
    private val client: OkHttpClient,
    private val authorization: String,
    private val authFailureMessage: String,
    private val now: () -> Long,
    private var expiresAt: Long,
    private val checkActive: () -> Unit
) : Closeable {
    private var closed = false

    internal fun <T> execute(builder: Request.Builder, parse: (Response, () -> Unit) -> T): T {
        checkActive()
        refreshIfNeeded()
        val request = builder.header("If", "<${root}> (<$token>)")
            .header("Cache-Control", "no-cache").header("Pragma", "no-cache").build()
        val call = client.newCall(request)
        call.timeout().timeout(requestBudgetMs(), TimeUnit.MILLISECONDS)
        return call.execute().use { response ->
            requireValidResponse(response)
            val value = parse(response, call::cancel)
            checkActive()
            requireLive()
            value
        }
    }

    private fun requestBudgetMs(): Long {
        val remaining = expiresAt - now() - SAFETY_MS
        if (remaining <= 0L) throw WebDavArchiveLeaseLostException("WebDAV archive lease expired")
        return minOf(remaining, REQUEST_MS)
    }

    private fun requireValidResponse(response: Response) {
        checkActive()
        requireLive()
        if (response.code == 412 || response.code == 423) {
            throw WebDavContentConflictException(response.code, "WebDAV archive condition changed")
        }
    }

    private fun requireLive() {
        if (closed || now() >= expiresAt) throw WebDavArchiveLeaseLostException("WebDAV archive lease expired")
    }

    private fun refreshIfNeeded() {
        requireLive()
        if (expiresAt - now() > REQUEST_MS + SAFETY_MS) return
        val start = now()
        val request = Request.Builder().url(root).header("Authorization", authorization)
            .header("If", "<${root}> (<$token>)").header("Timeout", "Second-300")
            .header("Cache-Control", "no-cache").method("LOCK", null).build()
        val call = client.newCall(request)
        call.timeout().timeout(minOf(expiresAt - start, REQUEST_MS), TimeUnit.MILLISECONDS)
        val grant = call.execute().use { response ->
            checkActive()
            requireLive()
            WebDavArchiveLockResponse.read(response, root, authFailureMessage, token)
        }
        expiresAt = Math.addExact(start, grant.durationMs)
        requireLive()
    }

    override fun close() {
        if (closed) return
        closed = true
        unlock(root, token, client, authorization)
    }

    companion object {
        private const val REQUEST_MS = 60_000L
        private const val SAFETY_MS = 5_000L

        internal fun acquire(remoteUrl: String, client: OkHttpClient, authorization: String,
            authFailureMessage: String, knownSupported: Boolean, checkActive: () -> Unit,
            now: () -> Long = { System.nanoTime() / 1_000_000L }): WebDavArchiveLease? {
            checkActive()
            val root = collectionRoot(remoteUrl)
            // query 可能选择其它资源视图，目录锁的覆盖范围无法从路径证明
            if (root.encodedQuery != null) {
                if (knownSupported) throw IOException("A previously locked WebDAV query target cannot use unguarded sync")
                return null
            }
            val leaseClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
            val start = now()
            val request = Request.Builder().url(root).header("Authorization", authorization)
                .header("Depth", "infinity").header("Timeout", "Second-300")
                .method("LOCK", "<d:lockinfo xmlns:d=\"DAV:\"><d:lockscope><d:exclusive/></d:lockscope><d:locktype><d:write/></d:locktype></d:lockinfo>"
                    .toRequestBody("application/xml; charset=utf-8".toMediaType())).build()
            val call = leaseClient.newCall(request)
            call.timeout().timeout(REQUEST_MS, TimeUnit.MILLISECONDS)
            return call.execute().use { response ->
                val grant = readInitialGrant(response, root, leaseClient, authorization, authFailureMessage, knownSupported)
                if (grant == null) {
                    checkActive()
                    return@use null
                }
                val lease = WebDavArchiveLease(root, grant.token, leaseClient, authorization, authFailureMessage,
                    now, Math.addExact(start, grant.durationMs), checkActive)
                try { checkActive(); lease.requireLive(); lease }
                catch (error: Exception) {
                    try { lease.close() } catch (closeError: Exception) { error.addSuppressed(closeError) }
                    throw error
                }
            }
        }

        private fun collectionRoot(remoteUrl: String): HttpUrl {
            val url = remoteUrl.toHttpUrl()
            return url.newBuilder().fragment(null).removePathSegment(url.pathSize - 1).addPathSegment("").build()
        }

        private fun readInitialGrant(response: Response, root: HttpUrl, client: OkHttpClient,
            authorization: String, authFailureMessage: String, knownSupported: Boolean): WebDavArchiveLockResponse.Grant? {
            if (response.code in setOf(405, 501) && !knownSupported) return null
            return try { WebDavArchiveLockResponse.read(response, root, authFailureMessage) }
            catch (error: Exception) {
                val released = releaseFailedGrant(response, root, client, authorization, error)
                if (error is WebDavUnsafeArchiveLeaseException && released && !knownSupported) null else throw error
            }
        }

        private fun releaseFailedGrant(response: Response, root: HttpUrl, client: OkHttpClient,
            authorization: String, error: Exception): Boolean {
            // 201 可能已创建并锁住普通资源，拒绝目录租约时也要释放自己的锁
            if (response.code != 200 && response.code != 201) return false
            val token = WebDavArchiveLockResponse.headerToken(response) ?: return false
            return try { unlock(root, token, client, authorization); true }
            catch (cleanup: Exception) { error.addSuppressed(cleanup); false }
        }

        private fun unlock(root: HttpUrl, token: String, client: OkHttpClient, authorization: String) {
            val request = Request.Builder().url(root).header("Authorization", authorization)
                .header("Lock-Token", "<$token>").method("UNLOCK", null).build()
            val call = client.newCall(request)
            call.timeout().timeout(5_000L, TimeUnit.MILLISECONDS)
            call.execute().use { response ->
                if (response.code != 204 && response.code != 409) {
                    throw WebDavApiException(response.code, "Failed to release WebDAV archive lease")
                }
            }
        }
    }
}

internal object WebDavArchiveLockResponse {
    data class Grant(val token: String, val durationMs: Long)
    private val absoluteToken = Regex("[A-Za-z][A-Za-z0-9+.-]*:[^<>\\s\\p{Cntrl}]+")
    private val decimalToken = Regex("[0-9]+")
    private val secondTimeout = Regex("Second-([0-9]+)")

    fun headerToken(response: Response): String? {
        val header = response.header("Lock-Token") ?: return null
        val token = unwrapToken(header.trim()) ?: return null
        return token.takeIf(::validToken)
    }

    private fun unwrapToken(header: String): String? {
        // WsgiDAV 返回裸令牌，带括号的响应仍只接受一对完整括号
        if (!header.startsWith('<') && !header.endsWith('>')) return header
        if (!header.startsWith('<') || !header.endsWith('>')) return null
        return header.substring(1, header.length - 1)
    }

    // rclone 使用 Go WebDAV 内存锁，令牌是十进制字符串
    private fun validToken(token: String): Boolean = token.length <= 1024 &&
        (absoluteToken.matches(token) || decimalToken.matches(token))

    fun read(response: Response, root: HttpUrl, authFailureMessage: String, expectedToken: String? = null): Grant {
        requireSuccess(response, authFailureMessage)
        val lock = lockProperties(response)
        requireElement(lock, "lockscope", "exclusive")
        requireElement(lock, "locktype", "write")
        requireRoot(lock, root, expectedToken != null)
        val token = readToken(lock, response, expectedToken)
        return Grant(token, duration(text(lock, "timeout")))
    }

    private fun requireSuccess(response: Response, authFailureMessage: String) {
        if (response.code == 401) throw WebDavAuthException(authFailureMessage)
        if (response.code == 403) throw WebDavAccessDeniedException("WebDAV archive lock is not permitted")
        if (response.code == 423) throw WebDavContentConflictException(423, "WebDAV archive is locked")
        if (response.code != 200) throw WebDavApiException(response.code, "Failed to acquire WebDAV archive lease")
    }

    private fun lockProperties(response: Response): Element {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; isExpandEntityReferences = false }
        val builder = factory.newDocumentBuilder().apply { setEntityResolver { _, _ -> throw IOException("External XML entities are not allowed") } }
        val document = builder.parse(WebDavXmlResponse.read(response, 64 * 1024))
        val locks = document.getElementsByTagNameNS("DAV:", "activelock")
        if (locks.length != 1) throw IOException("Invalid WebDAV archive lock response")
        return locks.item(0) as Element
    }

    private fun requireRoot(lock: Element, root: HttpUrl, refreshing: Boolean) {
        if (text(lock, "depth") != "infinity") {
            throw IOException("WebDAV archive requires an exclusive depth infinity collection lock")
        }
        // 旧 DAV 服务器可能省略 lockroot，成功且不重定向的 LOCK 已绑定请求目录
        val lockRoot = childOrNull(lock, "lockroot") ?: return
        val href = text(lockRoot, "href")
        if (href.isEmpty()) throw IOException("Invalid WebDAV archive lock root")
        val grantedRoot = root.resolve(href) ?: throw IOException("Invalid WebDAV archive lock root")
        if (grantedRoot == root) return
        // Go WebDAV 续租会去掉目录末尾斜杠，只兼容已持有锁的同一路径
        if (!refreshing || grantedRoot.newBuilder().addPathSegment("").build() != root) {
            throw IOException("WebDAV archive requires an exclusive depth infinity collection lock")
        }
    }

    private fun readToken(lock: Element, response: Response, expectedToken: String?): String {
        val token = text(child(lock, "locktoken"), "href")
        if (!validToken(token)) throw IOException("Invalid WebDAV archive lock token")
        if (expectedToken == null && headerToken(response) != token || expectedToken != null && token != expectedToken) {
            throw IOException("WebDAV archive lock token does not match")
        }
        return token
    }

    private fun duration(timeout: String): Long {
        if (timeout == "Infinite") throw WebDavUnsafeArchiveLeaseException()
        val seconds = seconds(timeout)
        if (seconds > 300L) throw WebDavUnsafeArchiveLeaseException()
        return seconds * 1000L
    }

    private fun seconds(timeout: String): Long {
        val match = secondTimeout.matchEntire(timeout) ?: throw IOException("Invalid WebDAV archive lease timeout")
        val seconds = match.groupValues[1].toLongOrNull() ?: throw IOException("Invalid WebDAV archive lease timeout")
        if (seconds !in 1L..4_294_967_295L) throw IOException("Invalid WebDAV archive lease timeout")
        return seconds
    }

    private fun requireElement(lock: Element, parent: String, name: String) { child(child(lock, parent), name) }
    private fun text(parent: Element, name: String): String = child(parent, name).textContent.trim()
    private fun child(parent: Element, name: String): Element {
        return childOrNull(parent, name) ?: throw IOException("Invalid WebDAV archive lock properties")
    }

    private fun childOrNull(parent: Element, name: String): Element? {
        val children = (0 until parent.childNodes.length).map { parent.childNodes.item(it) }.filterIsInstance<Element>()
        val matching = children.filter { it.namespaceURI == "DAV:" && it.localName == name }
        if (matching.size > 1) throw IOException("Invalid WebDAV archive lock properties")
        return matching.singleOrNull()
    }
}
