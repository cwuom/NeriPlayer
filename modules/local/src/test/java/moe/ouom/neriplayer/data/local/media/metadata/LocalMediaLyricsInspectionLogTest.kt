package moe.ouom.neriplayer.data.local.media.metadata

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import moe.ouom.neriplayer.data.local.media.LOCAL_LYRICS_PERF_LOG_LIMIT
import moe.ouom.neriplayer.data.local.media.LocalLyricsScanMetadata
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.contains
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.MockedStatic
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never

class LocalMediaLyricsInspectionLogTest {
    private var savedLogCount = 0

    @Before
    fun resetLogBudget() {
        savedLogCount = LocalMediaSupport.localLyricsPerfLogCount.get()
        LocalMediaSupport.localLyricsPerfLogCount.set(0)
    }

    @After
    fun restoreLogBudget() {
        LocalMediaSupport.localLyricsPerfLogCount.set(savedLogCount)
    }

    @Test
    fun `inspection logs report the explicit source and the sidecar flags`() {
        val source = mock(Uri::class.java).also { uri -> doReturn(SOURCE).`when`(uri).toString() }
        val scan = LocalLyricsScanMetadata(null, null, null, hasOriginalSidecar = true, hasRomanizedSidecar = true)

        withClock { log ->
            LocalMediaSupport.logLyricsInspection(song(LOCAL_PATH), source, "sidecar", STARTED_AT, scan)

            log.verify { Log.d(anyString(), eq(message("sidecar", "true", "false", "true", SOURCE)), isNull()) }
        }
    }

    @Test
    fun `inspection logs fall back to the local path and then the media uri`() {
        val scan = LocalLyricsScanMetadata(null, null, null, hasTranslatedSidecar = true)

        withClock { log ->
            LocalMediaSupport.logLyricsInspection(song(LOCAL_PATH), null, "embedded", STARTED_AT, scan)
            LocalMediaSupport.logLyricsInspection(song(null), null, "embedded", STARTED_AT, scan)

            log.verify { Log.d(anyString(), eq(message("embedded", "false", "true", "false", LOCAL_PATH)), isNull()) }
            log.verify { Log.d(anyString(), eq(message("embedded", "false", "true", "false", MEDIA_URI)), isNull()) }
        }
    }

    @Test
    fun `inspection logging stops once the per process budget is spent`() {
        LocalMediaSupport.localLyricsPerfLogCount.set(LOCAL_LYRICS_PERF_LOG_LIMIT - 1)
        val scan = LocalLyricsScanMetadata(null, null, null)

        withClock { log ->
            LocalMediaSupport.logLyricsInspection(song(null), null, "last", STARTED_AT, scan)
            LocalMediaSupport.logLyricsInspection(song(null), null, "over", STARTED_AT, scan)

            log.verify { Log.d(anyString(), eq(message("last", "false", "false", "false", MEDIA_URI)), isNull()) }
            log.verify({ Log.d(anyString(), contains("stage=over"), any()) }, never())
        }
    }

    private fun withClock(block: (MockedStatic<Log>) -> Unit) {
        mockStatic(SystemClock::class.java).use { clock ->
            clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenReturn(STARTED_AT + 37)
            mockStatic(Log::class.java).use(block)
        }
    }

    private fun song(localFilePath: String?) = SongItem(
        id = 7L,
        name = "Night Drive",
        artist = "Neri Band",
        album = "Demo Tape",
        albumId = 0L,
        durationMs = 180_000L,
        coverUrl = null,
        mediaUri = MEDIA_URI,
        localFilePath = localFilePath
    )

    private fun message(stage: String, original: String, translated: String, romanized: String, source: String) =
        "song=Night Drive, stage=$stage, elapsed=37ms, originalSidecar=$original, " +
            "translatedSidecar=$translated, romanizedSidecar=$romanized, source=$source"

    private companion object {
        const val SOURCE = "content://media/external/audio/media/42"
        const val LOCAL_PATH = "/music/night.flac"
        const val MEDIA_URI = "https://cdn.example.com/night.flac"
        const val STARTED_AT = 10_000L
    }
}
