package moe.ouom.neriplayer.lanltw.media

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
            request(token).use { assertEquals(200, it.code); assertEquals("synthetic-audio", it.body!!.string()) }
            request(token, "HEAD").use { assertEquals(200, it.code); assertEquals("15", it.header("Content-Length")); assertEquals(0, it.body!!.bytes().size) }
            request(token, target = "http://127.0.0.1:${server.listeningPort}/unregistered").use { assertEquals(404, it.code) }
            server.revoke("member")
            request(token).use { assertEquals(401, it.code) }
            val renewed = server.grant("member")
            server.retire(id)
            request(renewed).use { assertEquals(404, it.code) }
            assertFalse(file.exists())
        } finally { server.stop(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }
}
