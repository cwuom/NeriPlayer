package moe.ouom.neriplayer.lanltw.media

import java.net.ConnectException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.model.lan.LanLocalMedia
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class LanMediaServerTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun downloaderVerifiesCanonicalContentAndRejectsWrongHash() = kotlinx.coroutines.runBlocking {
        val source = LanFileSnapshot.create(temporary.newFolder()) { "abc".byteInputStream() }
        val server = LanMediaServer()
        val id = UUID.randomUUID().toString()
        server.publish(id, source.file, "audio/mpeg")
        server.start(5000, true)
        val client = OkHttpClient()
        val token = server.grant("member")
        val media = moe.ouom.neriplayer.data.model.lan.LanLocalMedia(id, source.sha256, 3, 1000, "audio/mpeg")
        val directory = temporary.newFolder()
        try {
            val endpoint = okhttp3.HttpUrl.Builder().scheme("http").host("127.0.0.1").port(1234).build()
            val downloader = LanMediaDownload(client)
            val received = downloader.fetch(endpoint, server.listeningPort, token, media, directory)
            assertEquals("abc", received.readText())
            received.delete()
            try {
                downloader.fetch(endpoint, server.listeningPort, token, media.copy(sha256 = "0".repeat(64)), directory)
                fail("Wrong hash accepted")
            } catch (error: IllegalStateException) { assertEquals("AUDIO_CONTENT", error.message) }
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { server.stop(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }
    @Test fun stopRevokesResourcesAndRejectsFurtherPublication() {
        val server = LanMediaServer()
        val file = temporary.newFile().apply { writeText("test") }
        server.publish(UUID.randomUUID().toString(), file, "audio/mpeg")
        server.grant("member")
        server.start(5000, true)
        server.stop()
        assertFalse(file.exists())
        val late = temporary.newFile().apply { writeText("late") }
        try { server.publish(UUID.randomUUID().toString(), late, "audio/mpeg"); fail("Published after stop") }
        catch (expected: IllegalStateException) { assertEquals("LOCAL_TRANSFER", expected.message) }
        assertTrue(late.exists()) // A rejected file still belongs to its caller.
    }
    @Test fun onlyRegisteredResourcesAndActiveMemberCredentialsCanDownload() {
        val server = LanMediaServer()
        val file = temporary.newFile().apply { writeText("synthetic-audio") }
        val id = UUID.randomUUID().toString()
        server.publish(id, file, "audio/mpeg")
        server.start(5000, true)
        val client = OkHttpClient()
        val url = "http://127.0.0.1:${server.listeningPort}/media/$id"
        fun request(token: String? = null, method: String = "GET", target: String = url) =
            client.newCall(Request.Builder().url(target).method(method, null).apply {
                token?.let { header("Authorization", "Bearer $it") }
            }.build()).execute()
        try {
            request().use { assertEquals(401, it.code) }
            val token = server.grant("member")
            request(token).use { assertEquals(200, it.code); assertEquals("synthetic-audio", it.body.string()) }
            request(token, "HEAD").use { assertEquals(200, it.code); assertEquals("15", it.header("Content-Length")); assertEquals(0, it.body.bytes().size) }
            request(token, target = "http://127.0.0.1:${server.listeningPort}/unregistered").use { assertEquals(404, it.code) }
            server.revoke("member")
            request(token).use { assertEquals(401, it.code) }
            val renewed = server.grant("member")
            server.retire(id)
            request(renewed).use { assertEquals(404, it.code) }
            assertFalse(file.exists())
        } finally { server.stop(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }

    @Test fun busyAudioTransfersStillAllowMetadataAndBackpressureForEveryMember() = runBlocking {
        val server = LanMediaServer()
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val clients = List(15) { OkHttpClient() }
        val id = UUID.randomUUID().toString()
        val presentationId = UUID.randomUUID().toString()
        val coverId = UUID.randomUUID().toString()
        server.publish(presentationId, temporary.newFile().apply { writeText("{}") }, "application/json")
        server.publish(coverId, temporary.newFile().apply { writeText("cover") }, "image/png")
        server.publishBorrowed(id, LanReadableMedia(1) {
            object : java.io.ByteArrayInputStream(byteArrayOf(1)) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    return super.read(bytes, offset, length)
                }
            }
        }, "audio/mpeg")
        server.start(5000, true)
        val url = "http://127.0.0.1:${server.listeningPort}/media/$id"
        val requests = clients.indices.map { index ->
            Request.Builder().url(url).header("Authorization", "Bearer ${server.grant("member-$index")}").build()
        }
        try {
            val transfers = clients.take(2).mapIndexed { index, client ->
                async(Dispatchers.IO) { client.newCall(requests[index]).execute().use { it.body.bytes() } }
            }
            try {
                withContext(Dispatchers.IO) {
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    for ((resource, expected) in listOf(presentationId to "{}", coverId to "cover")) {
                        val request = requests[2].newBuilder().url(url.replace(id, resource)).build()
                        clients[2].newCall(request).execute().use {
                            assertEquals(200, it.code)
                            assertEquals(expected, it.body.string())
                        }
                    }
                    for (index in 2 until clients.size) {
                        clients[index].newCall(requests[index]).execute().use { assertEquals(503, it.code) }
                    }
                }
            } finally { release.countDown() }
            transfers.forEach { assertArrayEquals(byteArrayOf(1), it.await()) }
        } finally {
            server.stop()
            clients.forEach { it.connectionPool.evictAll(); it.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun downloaderRetriesTemporaryConnectionFailure() = runBlocking {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            if (calls.getAndIncrement() == 0) throw ConnectException("temporary")
            chain.proceed(chain.request())
        }.build()
        val server = LanMediaServer()
        val audio = temporary.newFile().apply { writeText("abc") }
        val digest = MessageDigest.getInstance("SHA-256").digest(audio.readBytes()).joinToString("") { "%02x".format(it) }
        val id = UUID.randomUUID().toString()
        server.publish(id, audio, "audio/mpeg")
        server.start(5000, true)
        try {
            val endpoint = okhttp3.HttpUrl.Builder().scheme("http").host("127.0.0.1").port(1234).build()
            val media = LanLocalMedia(id, digest, 3, 1000, "audio/mpeg")
            val received = LanMediaDownload(client).fetch(endpoint, server.listeningPort, server.grant("member"),
                media, temporary.newFolder())
            assertEquals("abc", received.readText())
            assertEquals(2, calls.get())
        } finally {
            server.stop()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
