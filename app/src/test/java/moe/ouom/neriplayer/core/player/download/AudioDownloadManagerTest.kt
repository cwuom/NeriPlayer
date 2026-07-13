package moe.ouom.neriplayer.core.player.download

import moe.ouom.neriplayer.core.api.youtube.YouTubePlayableStreamType
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.Request
import java.io.IOException
import java.net.SocketException
import java.net.UnknownHostException

class AudioDownloadManagerTest {

    @Test
    fun `batch download parallelism keeps default six and caps at eight workers`() {
        assertEquals(6, AudioDownloadManager.DEFAULT_MAX_CONCURRENT_DOWNLOADS)
        assertEquals(8, AudioDownloadManager.MAX_CONCURRENT_DOWNLOADS_LIMIT)
        assertEquals(1, AudioDownloadManager.clampBatchDownloadParallelism(0))
        assertEquals(4, AudioDownloadManager.clampBatchDownloadParallelism(4))
        assertEquals(8, AudioDownloadManager.clampBatchDownloadParallelism(9))
        assertEquals(0, AudioDownloadManager.resolveBatchDownloadWorkerCount(0, 6))
        assertEquals(2, AudioDownloadManager.resolveBatchDownloadWorkerCount(2, 6))
        assertEquals(8, AudioDownloadManager.resolveBatchDownloadWorkerCount(20, 9))
    }

    @Test
    fun `shouldFetchRemoteLyricForDownload only fetches when local override is absent`() {
        assertEquals(true, AudioDownloadManager.shouldFetchRemoteLyricForDownload(null))
        assertEquals(false, AudioDownloadManager.shouldFetchRemoteLyricForDownload(""))
        assertEquals(false, AudioDownloadManager.shouldFetchRemoteLyricForDownload("   "))
        assertEquals(
            false,
            AudioDownloadManager.shouldFetchRemoteLyricForDownload("[00:00.00]local lyric")
        )
    }

    @Test
    fun `resolveLocalLyricForDownload keeps explicit lyrics and preserves cleared state separately`() {
        assertEquals(null, AudioDownloadManager.resolveLocalLyricForDownload(null))
        assertEquals(null, AudioDownloadManager.resolveLocalLyricForDownload(""))
        assertEquals(null, AudioDownloadManager.resolveLocalLyricForDownload("   "))
        assertEquals(
            "[00:00.00]translated",
            AudioDownloadManager.resolveLocalLyricForDownload("[00:00.00]translated")
        )
    }

    @Test
    fun `transient download retry delay grows and stays capped`() {
        assertEquals(1_000L, AudioDownloadManager.resolveTransientDownloadRetryDelayMs(1))
        assertEquals(2_000L, AudioDownloadManager.resolveTransientDownloadRetryDelayMs(2))
        assertEquals(4_000L, AudioDownloadManager.resolveTransientDownloadRetryDelayMs(3))
        assertEquals(5_000L, AudioDownloadManager.resolveTransientDownloadRetryDelayMs(4))
        assertEquals(5_000L, AudioDownloadManager.resolveTransientDownloadRetryDelayMs(9))
    }

    @Test
    fun `transient download failure detection only retries unstable network failures`() {
        assertTrue(
            AudioDownloadManager.shouldRetryTransientDownloadFailure(
                UnknownHostException("Unable to resolve host")
            )
        )
        assertTrue(
            AudioDownloadManager.shouldRetryTransientDownloadFailure(
                SocketException("Software caused connection abort")
            )
        )
        assertTrue(
            AudioDownloadManager.shouldRetryTransientDownloadFailure(
                IllegalStateException("HTTP 503")
            )
        )
        assertFalse(
            AudioDownloadManager.shouldRetryTransientDownloadFailure(
                IllegalStateException("HTTP 403")
            )
        )
        assertFalse(
            AudioDownloadManager.shouldRetryTransientDownloadFailure(
                IOException("磁盘写入失败")
            )
        )
    }

    @Test
    fun `youtube download failures refresh source for signed url status codes`() {
        assertTrue(
            AudioDownloadManager.shouldRefreshYouTubeDownloadSourceOnFailure(
                IllegalStateException("HTTP 403")
            )
        )
        assertTrue(
            AudioDownloadManager.shouldRefreshYouTubeDownloadSourceOnFailure(
                IllegalStateException("HTTP 416")
            )
        )
        assertTrue(
            AudioDownloadManager.shouldRetryDownloadFailureForSource(
                IllegalStateException("HTTP 403"),
                isYouTubeMusic = true
            )
        )
        assertFalse(
            AudioDownloadManager.shouldRetryDownloadFailureForSource(
                IllegalStateException("HTTP 403"),
                isYouTubeMusic = false
            )
        )
        assertFalse(
            AudioDownloadManager.shouldRefreshYouTubeDownloadSourceOnFailure(
                IOException("磁盘写入失败")
            )
        )
    }

    @Test
    fun `youtube download resolve plan starts with short shared probe then isolates refresh`() {
        val attempts = AudioDownloadManager.resolveYouTubeDownloadResolveAttempts(forceRefresh = false)

        assertEquals(4, attempts.size)
        assertEquals("shared_direct", attempts[0].logLabel)
        assertEquals(false, attempts[0].forceRefresh)
        assertEquals(true, attempts[0].requireDirect)
        assertEquals(true, attempts[0].shareInFlight)
        assertEquals("fresh_direct", attempts[1].logLabel)
        assertEquals(true, attempts[1].forceRefresh)
        assertEquals(true, attempts[1].requireDirect)
        assertEquals(false, attempts[1].shareInFlight)
        assertEquals("shared_playable", attempts[2].logLabel)
        assertEquals(false, attempts[2].forceRefresh)
        assertEquals(false, attempts[2].requireDirect)
        assertEquals(true, attempts[2].shareInFlight)
        assertEquals("fresh_playable", attempts[3].logLabel)
        assertEquals(true, attempts[3].forceRefresh)
        assertEquals(false, attempts[3].requireDirect)
        assertEquals(false, attempts[3].shareInFlight)
        assertTrue(attempts[0].timeoutMs < attempts[1].timeoutMs)
        assertTrue(attempts[2].timeoutMs < attempts[3].timeoutMs)
    }

    @Test
    fun `youtube download resolve plan skips shared probes after forced refresh`() {
        val attempts = AudioDownloadManager.resolveYouTubeDownloadResolveAttempts(forceRefresh = true)

        assertEquals(2, attempts.size)
        assertEquals(listOf("fresh_direct", "fresh_playable"), attempts.map { it.logLabel })
        assertTrue(attempts.all { it.forceRefresh })
        assertTrue(attempts.none { it.shareInFlight })
    }

    @Test
    fun `cover download candidates keep stable fallback order and de duplicate urls`() {
        val song = SongItem(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            albumId = 1L,
            durationMs = 1_000L,
            coverUrl = "https://example.com/cover.jpg",
            customCoverUrl = "https://example.com/custom.jpg",
            originalCoverUrl = "https://example.com/original.jpg",
            mediaUri = "https://example.com/audio.m4a"
        )

        assertEquals(
            listOf(
                "https://example.com/custom.jpg",
                "https://example.com/cover.jpg",
                "https://example.com/original.jpg"
            ),
            AudioDownloadManager.buildCoverDownloadCandidateUrls(song)
        )
    }

    @Test
    fun `shared cover lookup does not use album key when song has explicit cover url`() {
        val song = SongItem(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "NeteaseAlbum",
            albumId = 1L,
            durationMs = 1_000L,
            coverUrl = "https://example.com/cover.jpg",
            originalCoverUrl = "https://example.com/original.jpg"
        )

        assertEquals(
            listOf(
                "url:https://example.com/cover.jpg",
                "url:https://example.com/original.jpg"
            ),
            AudioDownloadManager.buildSharedCoverLookupKeys(song)
        )
    }

    @Test
    fun `shared cover lookup keeps album fallback only when cover urls are missing`() {
        val song = SongItem(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "NeteaseAlbum",
            albumId = 1L,
            durationMs = 1_000L,
            coverUrl = null
        )

        assertEquals(
            listOf("album:netease"),
            AudioDownloadManager.buildSharedCoverLookupKeys(song)
        )
    }

    @Test
    fun `transfer size completeness accepts unknown sizes and rejects mismatched payloads`() {
        assertTrue(AudioDownloadManager.isTransferSizeComplete(null, 128L))
        assertTrue(AudioDownloadManager.isTransferSizeComplete(0L, 128L))
        assertTrue(AudioDownloadManager.isTransferSizeComplete(256L, 256L))
        assertFalse(AudioDownloadManager.isTransferSizeComplete(256L, 512L))
        assertFalse(AudioDownloadManager.isTransferSizeComplete(256L, 128L))
    }

    @Test
    fun `resume range header starts from completed bytes`() {
        assertEquals(null, AudioDownloadManager.buildResumeRangeHeader(0L))
        assertEquals("bytes=1024-", AudioDownloadManager.buildResumeRangeHeader(1_024L))
    }

    @Test
    fun `response expected bytes keeps full size when resuming partial payload`() {
        val headers = mapOf("Content-Range" to listOf("bytes 1024-4095/4096"))

        assertEquals(
            4_096L,
            AudioDownloadManager.resolveResponseExpectedBytes(
                requestUrl = "https://example.com/audio.m4a",
                headers = headers,
                bodyLength = 3_072L,
                resumedBytes = 1_024L,
                isPartialResponse = true
            )
        )
    }

    @Test
    fun `download transport kind falls back to chunked range only for googlevideo without explicit range`() {
        val chunkedRequest = Request.Builder()
            .url("https://rr1---sn-abcd.googlevideo.com/videoplayback?source=youtube")
            .build()
        val directRequest = Request.Builder()
            .url("https://example.com/audio.m4a")
            .build()
        val explicitRangeRequest = chunkedRequest.newBuilder()
            .header("Range", "bytes=0-4095")
            .build()

        assertEquals(
            AudioDownloadManager.DownloadTransportKind.CHUNKED_RANGE,
            AudioDownloadManager.resolveDownloadTransportKind(
                YouTubePlayableStreamType.DIRECT,
                chunkedRequest
            )
        )
        assertEquals(
            AudioDownloadManager.DownloadTransportKind.DIRECT,
            AudioDownloadManager.resolveDownloadTransportKind(
                YouTubePlayableStreamType.DIRECT,
                directRequest
            )
        )
        assertEquals(
            AudioDownloadManager.DownloadTransportKind.DIRECT,
            AudioDownloadManager.resolveDownloadTransportKind(
                YouTubePlayableStreamType.DIRECT,
                explicitRangeRequest
            )
        )
    }

    @Test
    fun `partial download preservation requires bytes and hls checkpoint when needed`() {
        assertTrue(
            AudioDownloadManager.shouldPreservePartialDownloadForRetry(
                transportKind = AudioDownloadManager.DownloadTransportKind.DIRECT,
                existingBytes = 4_096L,
                hasHlsResumeState = false
            )
        )
        assertTrue(
            AudioDownloadManager.shouldPreservePartialDownloadForRetry(
                transportKind = AudioDownloadManager.DownloadTransportKind.CHUNKED_RANGE,
                existingBytes = 4_096L,
                hasHlsResumeState = false
            )
        )
        assertFalse(
            AudioDownloadManager.shouldPreservePartialDownloadForRetry(
                transportKind = AudioDownloadManager.DownloadTransportKind.HLS,
                existingBytes = 4_096L,
                hasHlsResumeState = false
            )
        )
        assertTrue(
            AudioDownloadManager.shouldPreservePartialDownloadForRetry(
                transportKind = AudioDownloadManager.DownloadTransportKind.HLS,
                existingBytes = 4_096L,
                hasHlsResumeState = true
            )
        )
    }

    @Test
    fun `hls resume state serialization round trips`() {
        val state = AudioDownloadManager.HlsResumeState(
            playlistFingerprint = 77,
            nextSegmentIndex = 12,
            downloadedBytes = 34_567L
        )

        val restored = AudioDownloadManager.deserializeHlsResumeState(
            AudioDownloadManager.serializeHlsResumeState(state)
        )

        assertEquals(state, restored)
        assertEquals(null, AudioDownloadManager.deserializeHlsResumeState("{"))
    }

    @Test
    fun `retry wake signal version advances and wraps safely`() {
        assertEquals(2L, AudioDownloadManager.advanceRetryWakeSignalVersion(1L))
        assertEquals(0L, AudioDownloadManager.advanceRetryWakeSignalVersion(Long.MAX_VALUE))
    }
}
