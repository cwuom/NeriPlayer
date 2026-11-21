package moe.ouom.neriplayer.core.player.lan.relay

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class LanRelayOriginTest {
    @Test fun providerErrorBodiesAndPlaylistsCannotBePublishedAsAudio() {
        for (mime in listOf("text/html", "application/dash+xml", "application/json", "application/vnd.apple.mpegurl")) {
            assertFalse(mime, progressiveMediaType(mime))
        }
        assertTrue(progressiveMediaType("application/octet-stream"))
        for (body in listOf(" \n{\"code\":403}", "#EXTM3U\n", "<!doctype html>", "")) {
            assertThrows(IllegalStateException::class.java) { readProgressivePrefix(body.byteInputStream()) }
        }
    }

    @Test fun probingShortAndFullStreamsPreservesAllBytes() {
        for (size in listOf(10, 32, 331)) {
            val media = ByteArray(size) { it.toByte() }
            assertArrayEquals(media, readProgressivePrefix(media.inputStream()).use { it.readBytes() })
        }
    }

    @Test fun cancellationStopsTheCurrentCallAndAnyLateChunkCall() {
        val client = OkHttpClient()
        val factory = RelayHttpCall(client)
        val request = Request.Builder().url("http://127.0.0.1/media").build()
        try {
            val inFlight = factory.newCall(request)
            assertFalse(inFlight.isCanceled())
            factory.cancel()
            assertTrue(inFlight.isCanceled())
            assertTrue(factory.newCall(request).isCanceled())
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
