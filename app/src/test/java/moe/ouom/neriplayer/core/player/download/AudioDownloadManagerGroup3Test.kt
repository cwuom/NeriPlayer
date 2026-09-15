package moe.ouom.neriplayer.core.player.download

import moe.ouom.neriplayer.core.api.youtube.YouTubePlayableStreamType
import moe.ouom.neriplayer.core.download.DownloadedAudioEmbeddingState
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceLookup
import moe.ouom.neriplayer.core.player.engine.datasource.ChunkRequestIOException
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.Request
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okio.Timeout
import okio.Buffer
import moe.ouom.neriplayer.data.traffic.TrafficByteAccumulator
import moe.ouom.neriplayer.data.traffic.TrafficNetworkType
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject


class AudioDownloadManagerGroup3Test : AudioDownloadManagerTestSupport() {

    @Test
    fun `hls checkpoint requires a durable prefix digest`() {
        val digest = "a".repeat(64)
        val state = AudioDownloadManager.HlsResumeState(
            playlistFingerprint = digest,
            nextSegmentIndex = 1,
            downloadedBytes = 3L
        )

        assertTrue(
            "checkpoint must bind the durable output prefix",
            AudioDownloadManager.serializeHlsResumeState(state)
                .contains("durablePrefixSha256")
        )
        assertEquals(
            null,
            AudioDownloadManager.deserializeHlsResumeState(
                """{"format":"hls-resume-v2","playlistDigestSha256":"$digest","nextSegmentIndex":1,"downloadedBytes":3}"""
            )
        )
    }

    @Test
    fun `hls resume rejects a same length file with a different durable prefix`() {
        val state = AudioDownloadManager.HlsResumeState(
            playlistFingerprint = "a".repeat(64),
            nextSegmentIndex = 2,
            downloadedBytes = 8L,
            durablePrefixSha256 = "b".repeat(64)
        )

        assertFalse(
            AudioDownloadManager.isHlsResumeStateCompatible(
                state = state,
                actualFileLength = 8L,
                actualPrefixSha256 = "c".repeat(64),
                segmentCount = 3
            )
        )
        assertTrue(
            AudioDownloadManager.isHlsResumeStateCompatible(
                state = state,
                actualFileLength = 8L,
                actualPrefixSha256 = "b".repeat(64),
                segmentCount = 3
            )
        )
        assertFalse(
            AudioDownloadManager.isHlsResumeStateCompatible(
                state = state,
                actualFileLength = 7L,
                actualPrefixSha256 = "b".repeat(64),
                segmentCount = 3
            )
        )
        assertFalse(
            AudioDownloadManager.isHlsResumeStateCompatible(
                state = state.copy(playlistFingerprint = "legacy-int"),
                actualFileLength = 8L,
                actualPrefixSha256 = "b".repeat(64),
                segmentCount = 3
            )
        )
    }

    @Test
    fun `hls playlist fingerprint is stable sha256 structured summary`() {
        val urls = listOf(
            "https://example.com/seg-1.ts",
            "https://example.com/seg-2.ts"
        )
        val fingerprint = AudioDownloadManager.buildHlsPlaylistFingerprint(urls)
        assertEquals(64, fingerprint.length)
        assertEquals(fingerprint, AudioDownloadManager.buildHlsPlaylistFingerprint(urls))
        assertNotEquals(
            fingerprint,
            AudioDownloadManager.buildHlsPlaylistFingerprint(urls + "https://example.com/seg-3.ts")
        )
        assertEquals(
            fingerprint,
            AudioDownloadManager.buildHlsPlaylistFingerprint(
                listOf(
                    "https://example.com/seg-1.ts?sig=rotated&expire=2",
                    "https://example.com/seg-2.ts?token=new"
                )
            )
        )
    }

    @Test
    fun `hls playlist fingerprint keeps byte range identity`() {
        val first = AudioDownloadManager.buildHlsPlaylistFingerprint(
            listOf("https://example.com/seg.ts?range=0-99")
        )
        val second = AudioDownloadManager.buildHlsPlaylistFingerprint(
            listOf("https://example.com/seg.ts?range=100-199")
        )

        assertNotEquals(first, second)
    }

    @Test
    fun `hls resume accepts only durable prefix and can discard an extra tail`() {
        val state = AudioDownloadManager.HlsResumeState(
            playlistFingerprint = "a".repeat(64),
            nextSegmentIndex = 2,
            downloadedBytes = 8L,
            durablePrefixSha256 = "b".repeat(64)
        )

        assertTrue(
            AudioDownloadManager.isHlsResumeStateCompatible(
                state = state,
                actualFileLength = 12L,
                actualPrefixSha256 = "b".repeat(64),
                segmentCount = 3
            )
        )
        assertFalse(
            AudioDownloadManager.isHlsResumeStateCompatible(
                state = state.copy(nextSegmentIndex = 0),
                actualFileLength = 12L,
                actualPrefixSha256 = "b".repeat(64),
                segmentCount = 3
            )
        )
    }

    @Test
    fun `hls checkpoint cannot be reused by a different operation`() {
        val state = AudioDownloadManager.HlsResumeState(
            playlistFingerprint = "a".repeat(64),
            nextSegmentIndex = 1,
            downloadedBytes = 8L,
            durablePrefixSha256 = "b".repeat(64),
            operationId = "operation-a"
        )

        assertTrue(
            AudioDownloadManager.isHlsResumeStateOwnedByOperation(
                state = state,
                operationId = "operation-a"
            )
        )
        assertFalse(
            AudioDownloadManager.isHlsResumeStateOwnedByOperation(
                state = state,
                operationId = "operation-b"
            )
        )
        assertFalse(
            AudioDownloadManager.isHlsResumeStateOwnedByOperation(
                state = state.copy(operationId = ""),
                operationId = "operation-a"
            )
        )
        assertFalse(
            AudioDownloadManager.isHlsResumeStateOwnedByOperation(
                state = state,
                operationId = ""
            )
        )
    }

    @Test
    fun `hls segment copy streams and strips only leading id3`() {
        val id3TagPayload = byteArrayOf(1, 2, 3, 4)
        val id3Header = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(),
            4, 0, 0, 0, 0, 0, id3TagPayload.size.toByte()
        )
        val payload = ByteArray(128 * 1024) { index -> (index and 0x7f).toByte() }
        val source = Buffer()
            .write(id3Header)
            .write(id3TagPayload)
            .write(payload)
        val sink = Buffer()
        val traffic = TrafficByteAccumulator(Long.MAX_VALUE) {}

        val copied = AudioDownloadManager.copyHlsSegment(source, sink, traffic)

        assertEquals(payload.size.toLong(), copied)
        assertTrue(sink.readByteArray().contentEquals(payload))
    }

    @Test
    fun `hls segment copy rejects a truncated response when length is known`() {
        val source = Buffer().write(byteArrayOf(1, 2, 3))
        val sink = Buffer()
        val traffic = TrafficByteAccumulator(Long.MAX_VALUE) {}

        assertThrows(IllegalStateException::class.java) {
            AudioDownloadManager.copyHlsSegment(
                source = source,
                sink = sink,
                trafficAccumulator = traffic,
                expectedRawBytes = 4L
            )
        }
    }

    @Test
    fun `retry wake signal version advances and wraps safely`() {
        assertEquals(2L, AudioDownloadManager.advanceRetryWakeSignalVersion(1L))
        assertEquals(0L, AudioDownloadManager.advanceRetryWakeSignalVersion(Long.MAX_VALUE))
    }

    @Test
    fun `new transfer clears stale core marker before registering operation`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/" +
                "AudioDownloadManagerRuntime.kt"
        ).readText()
        val body = methodBody(source, "executeDownloadSong")
        val clearIndex = body.indexOf("operationRegistry.clearCoreCommitted(")
        val beginIndex = body.indexOf("beginSongDownloadOperation(")

        assertTrue(clearIndex >= 0)
        assertTrue(beginIndex > clearIndex)
    }
}
