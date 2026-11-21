package moe.ouom.neriplayer.lanltw.media

import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.lan.LanSharedLyricLine
import moe.ouom.neriplayer.data.model.lan.LanSharedPresentation
import moe.ouom.neriplayer.data.model.lan.LanSharedWordTiming
import moe.ouom.neriplayer.lanltw.protocol.LanCodec
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LanSessionPresentationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun longWordTimedLyricsTransferWithMemberCredentialsAndRetireOnExit(): Unit = runBlocking {
        val lyrics = List(60) { index ->
            val start = index * 4_000L
            val words = List(20) { word ->
                val time = start + word * 200L
                LanSharedWordTiming(time, time + 200, 1)
            }
            LanSharedLyricLine("字".repeat(20), start, start + 4_000, words)
        }
        val value = LanSharedPresentation("Song", "Artist", "Album", lyrics = lyrics)
        assertTrue(LanPresentationCodec.encode(value).size > LanCodec.MAX_BYTES)
        val client = OkHttpClient()
        val directory = temporary.newFolder()
        val host = LanSessionPresentation(client, temporary.newFolder())
        val member = LanSessionPresentation(client, directory)
        val server = LanMediaServer()
        server.start(5_000, true)
        try {
            val descriptor = host.publish(server, value)
            assertEquals(descriptor, host.publish(server, value))
            val endpoint = "http://127.0.0.1:9000/ws"
            member.setEndpoint(endpoint, server.listeningPort, server.grant("member"))
            assertEquals(value, member.resolve(descriptor))
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            host.clear()
            assertEquals(value, member.resolve(descriptor))
            member.clear()
            assertNull(member.resolve(descriptor))
            member.setEndpoint(endpoint, server.listeningPort, server.grant("member"))
            assertThrows(IllegalStateException::class.java) { runBlocking { member.resolve(descriptor) } }
        } finally {
            host.clear()
            member.clear()
            server.stop()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
