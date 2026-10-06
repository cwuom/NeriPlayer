package moe.ouom.neriplayer.core.player.download.transfer

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.network.range.ChunkRequestIOException
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class AudioDownloadTransferPolicyTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `cover candidates keep trimmed network urls in display priority without duplicates`() {
        val song = song(
            coverUrl = "  http://cdn.example/cover.jpg  ",
            customCoverUrl = "HTTPS://custom.example/cover.png",
            originalCoverUrl = "content://media/external/images/1"
        )

        assertEquals(
            listOf("HTTPS://custom.example/cover.png", "http://cdn.example/cover.jpg"),
            AudioDownloadTransferPolicy.buildCoverDownloadCandidateUrls(song)
        )
    }

    @Test
    fun `cover candidates drop blank and missing urls`() {
        val song = song(coverUrl = "   ", originalCoverUrl = null, customCoverUrl = null)

        assertTrue(AudioDownloadTransferPolicy.buildCoverDownloadCandidateUrls(song).isEmpty())
    }

    @Test
    fun `only quoted non weak etags count as strong response validators`() {
        fun strong(etag: String?) = AudioDownloadTransferPolicy.hasStrongResponseEtag(
            if (etag == null) emptyMap() else mapOf("etag" to listOf(etag))
        )

        assertTrue(strong("\"v1\""))
        assertTrue(strong("  \"v1\"  "))
        assertFalse(strong(null))
        assertFalse(strong("W/\"v1\""))
        assertFalse(strong("w/\"v1\""))
        assertFalse(strong("\""))
        assertFalse(strong("v1\""))
        assertFalse(strong("\"v1"))
    }

    @Test
    fun `resume validator header only exposes strong fingerprint etags`() {
        assertEquals(
            "\"abc\"",
            AudioDownloadTransferPolicy.resolveResumeValidatorHeader(fingerprint(etag = " \"abc\" "))
        )
        assertNull(AudioDownloadTransferPolicy.resolveResumeValidatorHeader(fingerprint(etag = "W/\"abc\"")))
        assertNull(AudioDownloadTransferPolicy.resolveResumeValidatorHeader(null))
    }

    @Test
    fun `chunk resume request only sends If-Range after the first byte with a strong validator`() {
        val request = Request.Builder()
            .url("https://cdn.example/audio.m4a")
            .header("If-Range", "stale")
            .header("Accept-Encoding", "gzip")
            .build()
        val strong = fingerprint(etag = "\"chunk\"")

        val resumed = AudioDownloadTransferPolicy.buildChunkResumeRequest(request, 100L, 50L, strong)
        val firstChunk = AudioDownloadTransferPolicy.buildChunkResumeRequest(request, 0L, 50L, strong)
        val weak = AudioDownloadTransferPolicy.buildChunkResumeRequest(
            request,
            100L,
            50L,
            fingerprint(etag = "W/\"chunk\"")
        )

        assertEquals("bytes=100-149", resumed.header("Range"))
        assertEquals("\"chunk\"", resumed.header("If-Range"))
        assertEquals("identity", resumed.header("Accept-Encoding"))
        assertEquals("bytes=0-49", firstChunk.header("Range"))
        assertNull(firstChunk.header("If-Range"))
        assertNull(weak.header("If-Range"))
        assertEquals("identity", weak.header("Accept-Encoding"))
    }

    @Test
    fun `content range parser accepts consistent ranges and rejects malformed or overflowing values`() {
        fun parse(value: String?) = AudioDownloadTransferPolicy.parseContentRange(
            if (value == null) emptyMap() else mapOf("content-range" to listOf(value))
        )

        assertEquals(
            moe.ouom.neriplayer.core.player.download.AudioDownloadManager.ParsedContentRange(10L, 19L, 100L),
            parse(" BYTES 10-19/100 ")
        )
        assertNull(parse(null))
        assertNull(parse("bytes 10-19/*"))
        assertNull(parse("bytes 99999999999999999999-19/100"))
        assertNull(parse("bytes 10-99999999999999999999/100"))
        assertNull(parse("bytes 10-19/99999999999999999999"))
        assertNull(parse("bytes 20-19/100"))
        assertNull(parse("bytes 10-19/19"))
    }

    @Test
    fun `unsatisfied content range exposes only a parseable total`() {
        fun total(value: String?) = AudioDownloadTransferPolicy.parseUnsatisfiedContentRangeTotal(
            if (value == null) emptyMap() else mapOf("Content-Range" to listOf(value))
        )

        assertEquals(4096L, total("bytes */4096"))
        assertEquals(0L, total("bytes */0"))
        assertNull(total(null))
        assertNull(total("bytes 0-1/4096"))
        assertNull(total("bytes */99999999999999999999"))
        assertTrue(
            AudioDownloadTransferPolicy.isExactRangeEnd(
                mapOf("Content-Range" to listOf("bytes */4096")),
                4096L
            )
        )
        assertFalse(
            AudioDownloadTransferPolicy.isExactRangeEnd(
                mapOf("Content-Range" to listOf("bytes */4096")),
                -1L
            )
        )
    }

    @Test
    fun `response fingerprint is persisted beside the working file and ignores non positive lengths`() {
        val workingFile = File(tempFolder.newFolder("download_staging"), "npdl_song.m4a.download")
        val headers = mapOf(
            "ETag" to listOf("\"v2\""),
            "Last-Modified" to listOf("Wed, 15 Jul 2026 12:00:00 GMT")
        )

        assertTrue(
            AudioDownloadTransferPolicy.updateWorkingResumeFingerprint(
                workingFile,
                "https://cdn.example/audio.m4a",
                headers,
                expectedContentLength = 0L
            )
        )
        assertEquals(
            ManagedDownloadStorage.WorkingResumeFingerprint(
                sourceUrl = "https://cdn.example/audio.m4a",
                etag = "\"v2\"",
                lastModified = "Wed, 15 Jul 2026 12:00:00 GMT",
                expectedContentLength = null
            ),
            ManagedDownloadStorage.readWorkingResumeFingerprint(workingFile)
        )

        assertTrue(
            AudioDownloadTransferPolicy.updateWorkingResumeFingerprint(
                workingFile,
                "https://cdn.example/audio.m4a",
                emptyMap(),
                expectedContentLength = 2048L
            )
        )
        assertEquals(
            ManagedDownloadStorage.WorkingResumeFingerprint(
                sourceUrl = "https://cdn.example/audio.m4a",
                expectedContentLength = 2048L
            ),
            ManagedDownloadStorage.readWorkingResumeFingerprint(workingFile)
        )
    }

    @Test
    fun `fingerprint write failure reports false instead of throwing`() {
        val blockingFile = tempFolder.newFile("not-a-directory")
        val workingFile = File(blockingFile, "npdl_song.m4a.download")

        assertFalse(
            AudioDownloadTransferPolicy.updateWorkingResumeFingerprint(
                workingFile,
                "https://cdn.example/audio.m4a",
                mapOf("ETag" to listOf("\"v3\"")),
                expectedContentLength = null
            )
        )
        assertNull(ManagedDownloadStorage.readWorkingResumeFingerprint(workingFile))
    }

    @Test
    fun `youtube source refresh follows auth expiry range and transient status codes`() {
        fun refresh(code: Int) = AudioDownloadTransferPolicy.shouldRefreshYouTubeDownloadSourceOnFailure(
            ChunkRequestIOException(code, "chunk failed")
        )

        assertTrue(refresh(401))
        assertTrue(refresh(403))
        assertTrue(refresh(410))
        assertTrue(refresh(416))
        assertTrue(refresh(503))
        assertFalse(refresh(404))
        assertTrue(
            AudioDownloadTransferPolicy.shouldRefreshYouTubeDownloadSourceOnFailure(
                IOException("HTTP 410 Gone")
            )
        )
        assertFalse(
            AudioDownloadTransferPolicy.shouldRefreshYouTubeDownloadSourceOnFailure(
                IOException("no status here")
            )
        )
        assertFalse(
            AudioDownloadTransferPolicy.shouldRefreshYouTubeDownloadSourceOnFailure(
                java.util.concurrent.CancellationException("HTTP 403")
            )
        )
    }

    @Test
    fun `chunk status retry policy covers request timeout conflict early data throttling and server errors`() {
        fun retry(code: Int) = AudioDownloadTransferPolicy.shouldRetryTransientDownloadFailure(
            ChunkRequestIOException(code, "chunk failed")
        )

        assertTrue(retry(408))
        assertTrue(retry(409))
        assertTrue(retry(425))
        assertTrue(retry(429))
        assertTrue(retry(500))
        assertTrue(retry(599))
        assertFalse(retry(499))
        assertFalse(retry(600))
        assertFalse(retry(404))
    }

    @Test
    fun `transient network messages are retried only for known connection failures`() {
        fun retry(message: String?) = AudioDownloadTransferPolicy.shouldRetryTransientDownloadFailure(
            IOException(message)
        )

        assertTrue(retry("Unexpected end of stream on Connection"))
        assertTrue(retry("connection reset by peer"))
        assertTrue(retry("Read TIMED OUT"))
        assertTrue(retry("stream was reset: CANCEL"))
        assertFalse(retry("disk full"))
        assertFalse(retry(null))
        assertFalse(retry("   "))
    }

    private fun fingerprint(etag: String?): ManagedDownloadStorage.WorkingResumeFingerprint {
        return ManagedDownloadStorage.WorkingResumeFingerprint(
            sourceUrl = "https://cdn.example/audio.m4a",
            etag = etag
        )
    }

    private fun song(
        coverUrl: String?,
        customCoverUrl: String?,
        originalCoverUrl: String?
    ): SongItem {
        return SongItem(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            albumId = 1L,
            durationMs = 1_000L,
            coverUrl = coverUrl,
            customCoverUrl = customCoverUrl,
            originalCoverUrl = originalCoverUrl
        )
    }
}
