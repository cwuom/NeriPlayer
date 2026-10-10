package moe.ouom.neriplayer.api.sync.webdav

import java.io.Closeable
import java.io.IOException
import java.util.UUID
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class WebDavProviderLeaseTest(private val providerName: String, private val baseUrl: String) {
    @Before
    fun requireLocalProvider() {
        assumeTrue("Set NERIPLAYER_WEBDAV_COMPAT_URLS to enable real provider tests", baseUrl.isNotEmpty())
        val endpoint = baseUrl.toHttpUrl()
        require(endpoint.host in setOf("127.0.0.1", "localhost", "::1")) { "Compatibility providers must use loopback" }
        require(endpoint.username.isEmpty() && endpoint.password.isEmpty()) { "Use anonymous local providers" }
        require(endpoint.encodedQuery == null && endpoint.fragment == null) { "Use a collection URL without routing suffixes" }
    }

    @Test
    fun `a nested encoded collection refreshes its same lease excludes competitors and unlocks for a new writer`() {
        ProviderFixture().use { fixture ->
            val payload = "complete fixture content".toByteArray()
            fixture.api.updateFileContent(fixture.fileUrl, payload, createOnly = true).getOrThrow()
            var now = 0L
            val lease = requireNotNull(WebDavArchiveLease.acquire(fixture.fileUrl, fixture.client, AUTHORIZATION,
                "Local fixture authentication failed", false, {}, { now }))
            lease.use {
                assertEquals(fixture.collection, it.root)
                val conflict = assertThrows(WebDavContentConflictException::class.java) {
                    WebDavArchiveLease.acquire(fixture.fileUrl, fixture.client, AUTHORIZATION,
                        "Local fixture authentication failed", true, {})
                }
                assertEquals(423, conflict.statusCode)

                // 只推进客户端时钟来触发续租，真实服务器上的目录锁仍在有效期内
                now = 250_000L
                assertArrayEquals(payload, fixture.api.getFileContentLocked(fixture.fileUrl, it).getOrThrow().content)
                assertTrue("$providerName refreshes the acquired token", fixture.marks.any {
                    it.method == "LOCK" && !it.hasBody && it.status == 200 && it.ownsInitialToken
                })
            }

            requireNotNull(WebDavArchiveLease.acquire(fixture.fileUrl, fixture.client, AUTHORIZATION,
                "Local fixture authentication failed", true, {})).use {
                assertArrayEquals(payload, fixture.api.getFileContentLocked(fixture.fileUrl, it).getOrThrow().content)
            }
            assertEquals(2, fixture.marks.count { it.method == "UNLOCK" && it.status == 204 })
            assertEquals(3, fixture.marks.count { it.method == "LOCK" && it.status == 200 })
        }
    }

    private inner class ProviderFixture : Closeable {
        val marks = arrayListOf<RequestMark>()
        private var initialToken: String? = null
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .addInterceptor { chain ->
                val request = chain.request()
                val response = chain.proceed(request)
                if (request.method == "LOCK" && request.body != null && response.code == 200 && initialToken == null) {
                    initialToken = response.header("Lock-Token")?.trim()?.removeSurrounding("<", ">")
                }
                marks += RequestMark(request.method, request.body != null, response.code,
                    initialToken?.let { request.header("If")?.contains("<$it>") == true } ?: false)
                response
            }.build()
        private val parent: HttpUrl = (baseUrl.trimEnd('/') + '/').toHttpUrl().newBuilder()
            .addPathSegment("neriplayer-lease-${UUID.randomUUID()}").addPathSegment("").build()
        val collection: HttpUrl = parent.newBuilder().addPathSegment("nested collection 汉字").addPathSegment("").build()
        val fileUrl = requireNotNull(collection.resolve("payload.bin")).toString()
        val api = WebDavApiClient("compatibility-test", "local-fixture-only", client, "Local fixture authentication failed")

        init {
            try {
                collectionRequest(parent, "MKCOL", setOf(201))
                try { collectionRequest(collection, "MKCOL", setOf(201)) }
                catch (failure: Throwable) {
                    try { collectionRequest(parent, "DELETE", setOf(200, 204)) }
                    catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                    throw failure
                }
            } catch (failure: Throwable) {
                shutdownClient()
                throw failure
            }
        }

        override fun close() {
            try { collectionRequest(parent, "DELETE", setOf(200, 204)) }
            finally { shutdownClient() }
        }

        private fun collectionRequest(url: HttpUrl, method: String, accepted: Set<Int>) {
            val request = Request.Builder().url(url).header("Authorization", AUTHORIZATION).method(method, null).build()
            client.newCall(request).execute().use {
                if (it.code !in accepted) throw IOException("$providerName isolated collection $method failed with HTTP ${it.code}")
            }
        }

        private fun shutdownClient() {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private data class RequestMark(val method: String, val hasBody: Boolean, val status: Int, val ownsInitialToken: Boolean)

    companion object {
        private val AUTHORIZATION = Credentials.basic("compatibility-test", "local-fixture-only")

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun providers(): Collection<Array<String>> {
            val urls = System.getenv("NERIPLAYER_WEBDAV_COMPAT_URLS").orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
            return if (urls.isEmpty()) listOf(arrayOf("not configured", ""))
            else urls.mapIndexed { index, url -> arrayOf("provider-${index + 1}", url) }
        }
    }
}
