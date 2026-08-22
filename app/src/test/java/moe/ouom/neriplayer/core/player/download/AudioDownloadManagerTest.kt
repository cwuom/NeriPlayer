package moe.ouom.neriplayer.core.player.download

import moe.ouom.neriplayer.core.api.youtube.YouTubePlayableStreamType
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.player.resolver.youtube.ChunkRequestIOException
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
import java.io.IOException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean

class AudioDownloadManagerTest {

    @Test
    fun `cancelYouTubeCalls cancels only trusted YouTube hosts`() {
        val youtubeCall = FakeCall("https://rr1---sn.example.googlevideo.com/audio")
        val innertubeCall = FakeCall("https://youtubei.googleapis.com/youtubei/v1/player")
        val unrelatedCall = FakeCall("https://example.com/audio")

        val canceled = AudioDownloadManager.cancelYouTubeCalls(
            listOf(youtubeCall, innertubeCall, unrelatedCall)
        )

        assertEquals(2, canceled)
        assertTrue(youtubeCall.isCanceled())
        assertTrue(innertubeCall.isCanceled())
        assertFalse(unrelatedCall.isCanceled())
    }

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
    fun `romanized lyric download only piggybacks on an existing lyric request`() {
        assertFalse(
            AudioDownloadManager.shouldFetchRomanizedLyricForDownload(
                shouldFetchPrimaryLyric = false,
                shouldFetchTranslatedLyric = false
            )
        )
        assertTrue(
            AudioDownloadManager.shouldFetchRomanizedLyricForDownload(
                shouldFetchPrimaryLyric = true,
                shouldFetchTranslatedLyric = false
            )
        )
        assertTrue(
            AudioDownloadManager.shouldFetchRomanizedLyricForDownload(
                shouldFetchPrimaryLyric = false,
                shouldFetchTranslatedLyric = true
            )
        )
    }

    @Test
    fun `sidecar merge preserves expected lyric artifacts across retries`() {
        val merged = AudioDownloadManager.mergeDownloadedSidecarReferences(
            existing = AudioDownloadManager.DownloadedSidecarReferences(
                expectedLyric = true
            ),
            incoming = AudioDownloadManager.DownloadedSidecarReferences(
                expectedTranslatedLyric = true,
                expectedRomanizedLyric = true
            )
        )

        assertTrue(merged.expectedLyric)
        assertTrue(merged.expectedTranslatedLyric)
        assertTrue(merged.expectedRomanizedLyric)
        assertFalse(merged.isEmpty)
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
    fun `forbidden download failure is detected for 403 only`() {
        // 403(含 ChunkRequestIOException)才触发"改走 HLS", 其余可刷新码不触发
        assertTrue(AudioDownloadManager.isForbiddenYouTubeDownloadFailure(IllegalStateException("HTTP 403")))
        assertTrue(AudioDownloadManager.isForbiddenYouTubeDownloadFailure(ChunkRequestIOException(403, "forbidden")))
        assertFalse(AudioDownloadManager.isForbiddenYouTubeDownloadFailure(ChunkRequestIOException(429, "rate")))
        assertFalse(AudioDownloadManager.isForbiddenYouTubeDownloadFailure(IllegalStateException("HTTP 416")))
        assertFalse(AudioDownloadManager.isForbiddenYouTubeDownloadFailure(IOException("磁盘写入失败")))
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
    fun `cover download candidates exclude device local references`() {
        val song = SongItem(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            albumId = 1L,
            durationMs = 1_000L,
            coverUrl = "file:/data/user/0/moe.ouom.neriplayer/files/local_audio_covers/cover.jpg",
            customCoverUrl = "content://media/external/images/media/1",
            originalCoverUrl = "https://example.com/original.jpg"
        )

        assertEquals(
            listOf("https://example.com/original.jpg"),
            AudioDownloadManager.buildCoverDownloadCandidateUrls(song)
        )
    }

    @Test
    fun `cover download candidates trim before de duplicating urls`() {
        val song = SongItem(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            albumId = 1L,
            durationMs = 1_000L,
            coverUrl = "https://example.com/cover.jpg",
            customCoverUrl = " https://example.com/cover.jpg ",
            originalCoverUrl = "https://example.com/original.jpg"
        )

        assertEquals(
            listOf(
                "https://example.com/cover.jpg",
                "https://example.com/original.jpg"
            ),
            AudioDownloadManager.buildCoverDownloadCandidateUrls(song)
        )
    }

    @Test
    fun `cover sidecar file names include stable song identity`() {
        val firstCoverName = AudioDownloadManager.buildCoverSidecarFileName(
            baseName = "Artist - Song",
            songKey = "1|netease|"
        )
        val sameNameSecondCoverName = AudioDownloadManager.buildCoverSidecarFileName(
            baseName = "Artist - Song",
            songKey = "2|netease|"
        )
        val numberedDuplicateCoverName = AudioDownloadManager.buildCoverSidecarFileName(
            baseName = "Artist - Song (1)",
            songKey = "2|netease|"
        )

        assertNotEquals(firstCoverName, sameNameSecondCoverName)
        assertNotEquals(firstCoverName, numberedDuplicateCoverName)
    }

    @Test
    fun `transfer size completeness rejects short payloads while allowing bounded provider drift`() {
        assertTrue(AudioDownloadManager.isTransferSizeComplete(null, 128L))
        assertTrue(AudioDownloadManager.isTransferSizeComplete(0L, 128L))
        assertTrue(AudioDownloadManager.isTransferSizeComplete(256L, 256L))
        assertTrue(AudioDownloadManager.isTransferSizeComplete(256L, 257L))
        assertFalse(AudioDownloadManager.isTransferSizeComplete(1_000_000L, 999_000L))
        assertTrue(AudioDownloadManager.isTransferSizeComplete(1_000_000L, 1_001_000L))
        assertFalse(AudioDownloadManager.isTransferSizeComplete(null, 0L))
        assertFalse(AudioDownloadManager.isTransferSizeComplete(256L, 258L))
        assertFalse(AudioDownloadManager.isTransferSizeComplete(1_000_000L, 998_999L))
        assertFalse(AudioDownloadManager.isTransferSizeComplete(1_000_000L, 1_001_001L))
        assertFalse(AudioDownloadManager.isTransferSizeComplete(256L, 512L))
        assertFalse(AudioDownloadManager.isTransferSizeComplete(256L, 128L))
        assertFalse(AudioDownloadManager.isTransferSizeComplete(3_758_751L, 5_129_657L))
    }

    @Test
    fun `commit size validation drops transfer expectation after metadata changes file`() {
        assertNull(
            AudioDownloadManager.resolveAudioCommitExpectedSize(
                transferExpectedBytes = 5_129_657L,
                bytesBeforeMetadata = 5_129_657L,
                bytesAtCommit = 4_551_323L
            )
        )
        assertEquals(
            3_758_751L,
            AudioDownloadManager.resolveAudioCommitExpectedSize(
                transferExpectedBytes = 3_758_751L,
                bytesBeforeMetadata = 3_758_751L,
                bytesAtCommit = 3_758_751L
            )
        )
        assertNull(
            AudioDownloadManager.resolveAudioCommitExpectedSize(
                transferExpectedBytes = null,
                bytesBeforeMetadata = 1_024L,
                bytesAtCommit = 1_024L
            )
        )
        assertNull(
            AudioDownloadManager.resolveAudioCommitExpectedSize(
                transferExpectedBytes = 3_758_751L,
                bytesBeforeMetadata = 5_129_657L,
                bytesAtCommit = 4_551_323L
            )
        )
    }

    @Test
    fun `resume range header starts from completed bytes`() {
        assertEquals(null, AudioDownloadManager.buildResumeRangeHeader(0L))
        assertEquals("bytes=1024-", AudioDownloadManager.buildResumeRangeHeader(1_024L))
    }

    @Test
    fun `resume request includes if range validator when fingerprint is available`() {
        val request = Request.Builder()
            .url("https://example.com/audio.m4a")
            .build()
        val fingerprint = ManagedDownloadStorage.WorkingResumeFingerprint(
            sourceUrl = request.url.toString(),
            etag = "\"abc123\"",
            lastModified = "Wed, 15 Jul 2026 12:00:00 GMT",
            expectedContentLength = 4_096L
        )

        val resumedRequest = AudioDownloadManager.buildResumeRequest(
            request = request,
            completedBytes = 1_024L,
            fingerprint = fingerprint
        )

        assertEquals("bytes=1024-", resumedRequest.header("Range"))
        assertEquals("\"abc123\"", resumedRequest.header("If-Range"))
    }

    @Test
    fun `resume request does not use last modified without strong etag`() {
        val request = Request.Builder()
            .url("https://example.com/audio.m4a")
            .build()
        val fingerprint = ManagedDownloadStorage.WorkingResumeFingerprint(
            sourceUrl = request.url.toString(),
            etag = null,
            lastModified = "Wed, 15 Jul 2026 12:00:00 GMT",
            expectedContentLength = 4_096L
        )

        val resumedRequest = AudioDownloadManager.buildResumeRequest(
            request = request,
            completedBytes = 1_024L,
            fingerprint = fingerprint
        )

        assertEquals("bytes=1024-", resumedRequest.header("Range"))
        assertNull(resumedRequest.header("If-Range"))
        assertEquals("identity", resumedRequest.header("Accept-Encoding"))
    }

    @Test
    fun `resume request rejects weak etag for if range`() {
        val request = Request.Builder()
            .url("https://example.com/audio.m4a")
            .build()
        val weakWithDate = ManagedDownloadStorage.WorkingResumeFingerprint(
            sourceUrl = request.url.toString(),
            etag = "W/\"weak\"",
            lastModified = "Wed, 15 Jul 2026 12:00:00 GMT"
        )
        val weakOnly = weakWithDate.copy(lastModified = null)

        assertNull(
            AudioDownloadManager.buildResumeRequest(request, 1_024L, weakWithDate)
                .header("If-Range")
        )
        assertNull(
            AudioDownloadManager.buildResumeRequest(request, 1_024L, weakOnly)
                .header("If-Range")
        )
    }

    @Test
    fun `signed url rotation does not discard a resumable file`() {
        val fingerprint = ManagedDownloadStorage.WorkingResumeFingerprint(
            sourceUrl = "https://example.com/audio.m4a?token=old",
            etag = "\"same-validator\"",
            lastModified = null,
            expectedContentLength = 4_096L
        )

        assertFalse(
            AudioDownloadManager.shouldDiscardWorkingFileForResume(
                requestUrl = "https://example.com/audio.m4a?token=new",
                fingerprint = fingerprint
            )
        )
        assertFalse(
            AudioDownloadManager.shouldDiscardWorkingFileForResume(
                requestUrl = "https://example.com/audio.m4a?token=new",
                fingerprint = fingerprint.copy(etag = null, lastModified = null)
            )
        )
        assertTrue(
            AudioDownloadManager.shouldDiscardWorkingFileForResume(
                requestUrl = "https://example.com/other.m4a?token=new",
                fingerprint = fingerprint.copy(etag = null, lastModified = null)
            )
        )
        assertFalse(
            AudioDownloadManager.shouldDiscardWorkingFileForResume(
                requestUrl = "https://example.com/audio.m4a?token=old",
                fingerprint = fingerprint
            )
        )
        assertFalse(
            AudioDownloadManager.shouldDiscardWorkingFileForResume(
                requestUrl = "https://example.com/audio.m4a",
                fingerprint = null
            )
        )
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
    fun `content range validation requires exact start total and body length`() {
        val validHeaders = mapOf("Content-Range" to listOf("bytes 1024-4095/4096"))
        assertEquals(
            4_096L,
            AudioDownloadManager.validatePartialContentRange(
                headers = validHeaders,
                expectedStart = 1_024L,
                bodyLength = 3_072L
            ).total
        )
        assertThrows(IOException::class.java) {
            AudioDownloadManager.validatePartialContentRange(
                headers = validHeaders,
                expectedStart = 1_025L,
                bodyLength = 3_072L
            )
        }
        assertThrows(IOException::class.java) {
            AudioDownloadManager.validatePartialContentRange(
                headers = mapOf("Content-Range" to listOf("bytes 1024-4095/*")),
                expectedStart = 1_024L,
                bodyLength = 3_072L
            )
        }
        assertThrows(IOException::class.java) {
            AudioDownloadManager.validatePartialContentRange(
                headers = validHeaders,
                expectedStart = 1_024L,
                bodyLength = 3_071L
            )
        }
    }

    @Test
    fun `resume response requires the same strong etag and total`() {
        val fingerprint = ManagedDownloadStorage.WorkingResumeFingerprint(
            etag = "\"stable\"",
            expectedContentLength = 4_096L
        )
        val headers = mapOf("ETag" to listOf("\"stable\""))

        assertTrue(
            AudioDownloadManager.isResumeResponseCompatible(
                fingerprint,
                headers,
                4_096L
            )
        )
        assertFalse(
            AudioDownloadManager.isResumeResponseCompatible(
                fingerprint,
                mapOf("ETag" to listOf("\"rotated\"")),
                4_096L
            )
        )
        assertFalse(
            AudioDownloadManager.isResumeResponseCompatible(
                fingerprint,
                headers,
                4_097L
            )
        )
        assertFalse(
            AudioDownloadManager.isResumeResponseCompatible(
                fingerprint,
                mapOf("ETag" to listOf("W/\"stable\"")),
                4_096L
            )
        )
    }

    @Test
    fun `range not satisfiable is accepted only at exact total`() {
        val headers = mapOf("Content-Range" to listOf("bytes */4096"))
        assertTrue(AudioDownloadManager.isExactRangeEnd(headers, 4_096L))
        assertFalse(AudioDownloadManager.isExactRangeEnd(headers, 4_095L))
        assertFalse(
            AudioDownloadManager.isExactRangeEnd(
                mapOf("Content-Range" to listOf("bytes */4096")),
                4_097L
            )
        )
    }

    @Test
    fun `response content length wins over stale non youtube query length`() {
        val headers = mapOf("Content-Length" to listOf("5129657"))

        assertEquals(
            5_129_657L,
            AudioDownloadManager.resolveResponseExpectedBytes(
                requestUrl = "https://m801.music.126.net/audio.mp3?clen=3758751",
                headers = headers,
                bodyLength = 5_129_657L,
                resumedBytes = 0L,
                isPartialResponse = false
            )
        )
    }

    @Test
    fun `non youtube query length is ignored when response length is unavailable`() {
        assertNull(
            AudioDownloadManager.resolveResponseExpectedBytes(
                requestUrl = "https://m801.music.126.net/audio.mp3?clen=3758751",
                headers = emptyMap(),
                bodyLength = -1L,
                resumedBytes = 0L,
                isPartialResponse = false
            )
        )
    }

    @Test
    fun `google video query length remains a fallback when response length is unavailable`() {
        assertEquals(
            3_758_751L,
            AudioDownloadManager.resolveResponseExpectedBytes(
                requestUrl = "https://rr1---sn-abcd.googlevideo.com/videoplayback?clen=3758751&source=youtube",
                headers = emptyMap(),
                bodyLength = -1L,
                resumedBytes = 0L,
                isPartialResponse = false
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
    fun `download transport chunks seekable web remix direct url instead of full range`() {
        // 回归: 已解析(n+sig+clen)的 WEB_REMIX 直链此前被判为 DIRECT(整档下载)导致 403
        // 现应统一走 CHUNKED_RANGE, 避免整档 GET 触发 googlevideo 全量下载风控
        val seekableWebRemixRequest = Request.Builder()
            .url(
                "https://rr1---sn-aigl6ney.googlevideo.com/videoplayback" +
                    "?source=youtube&id=audio-demo&n=resolved-n&sig=resolved-signature&mime=audio%2Fmp4&clen=3611036"
            )
            .build()

        assertEquals(
            AudioDownloadManager.DownloadTransportKind.CHUNKED_RANGE,
            AudioDownloadManager.resolveDownloadTransportKind(
                YouTubePlayableStreamType.DIRECT,
                seekableWebRemixRequest
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
            playlistFingerprint = "a".repeat(64),
            nextSegmentIndex = 12,
            downloadedBytes = 34_567L
        )

        val restored = AudioDownloadManager.deserializeHlsResumeState(
            AudioDownloadManager.serializeHlsResumeState(state)
        )

        assertEquals(state, restored)
        assertTrue(
            AudioDownloadManager.serializeHlsResumeState(state)
                .contains("playlistDigestSha256")
        )
        assertEquals(null, AudioDownloadManager.deserializeHlsResumeState("{"))
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
    fun `retry wake signal version advances and wraps safely`() {
        assertEquals(2L, AudioDownloadManager.advanceRetryWakeSignalVersion(1L))
        assertEquals(0L, AudioDownloadManager.advanceRetryWakeSignalVersion(Long.MAX_VALUE))
    }

    private class FakeCall(url: String) : Call {
        private val request = Request.Builder().url(url).build()
        private val canceled = AtomicBoolean(false)

        override fun request(): Request = request

        override fun execute(): Response {
            throw UnsupportedOperationException("execution is not used")
        }

        override fun enqueue(responseCallback: Callback) {
            throw UnsupportedOperationException("enqueue is not used")
        }

        override fun cancel() {
            canceled.set(true)
        }

        override fun isExecuted(): Boolean = false

        override fun isCanceled(): Boolean = canceled.get()

        override fun timeout(): Timeout = Timeout.NONE

        override fun clone(): Call = FakeCall(request.url.toString())

        override fun addEventListener(eventListener: okhttp3.EventListener) = Unit

        override fun <T : Any> tag(type: kotlin.reflect.KClass<T>): T? = null

        override fun <T> tag(type: Class<out T>): T? = null

        override fun <T : Any> tag(type: kotlin.reflect.KClass<T>, computeIfAbsent: () -> T): T =
            computeIfAbsent()

        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T =
            computeIfAbsent()
    }
}
