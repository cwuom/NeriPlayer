package moe.ouom.neriplayer.network.proxy

import java.net.InetAddress
import java.nio.ByteBuffer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.OkHttp
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NetworkDnsTest {
    @Test
    fun `only HTTPS resolvers without embedded credentials or fragments are accepted`() {
        for (value in listOf("http://resolver.example", "https://user@resolver.example/dns-query", "invalid",
            "https://resolver.example/dns-query#extra")) assertNull(value, parseDohEndpoint(value))
        for (provider in DnsProvider.entries) assertEquals(provider.url, parseDohEndpoint(provider.url).toString())
    }

    @Test
    fun `custom resolver receives wire format queries and supplies the target address`() {
        OkHttp.initialize(RuntimeEnvironment.getApplication())
        val endpoint = requireNotNull(parseDohEndpoint("https://resolver.example/custom"))
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals(endpoint, request.url)
            assertEquals("POST", request.method)
            val requestBody = requireNotNull(request.body)
            assertEquals("application/dns-message", requestBody.contentType().toString())
            val buffer = Buffer()
            requestBody.writeTo(buffer)
            val question = buffer.readByteArray()
            val answer = answerFor(question)
            Response.Builder().request(request).protocol(Protocol.HTTP_2).code(200).message("OK")
                .body(answer.toResponseBody("application/dns-message".toMediaType())).build()
        }.build()
        try {
            val records = buildDohResolver(endpoint, transport).lookup("music.youtube.com")
            assertEquals(listOf(InetAddress.getByAddress(byteArrayOf(10, 20, 30, 40))), records)
        } finally {
            transport.dispatcher.executorService.shutdown()
            transport.connectionPool.evictAll()
        }
    }

    private fun answerFor(question: ByteArray): ByteArray {
        val type = ByteBuffer.wrap(question).getShort(question.size - 4).toInt()
        val header = question.copyOf()
        header[2] = 0x81.toByte()
        header[3] = 0x80.toByte()
        if (type != 1) return header
        header[7] = 1
        // compressed name, A record, Internet class, 60 second TTL, four address bytes
        return header + byteArrayOf(0xc0.toByte(), 0x0c, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 10, 20, 30, 40)
    }
}
