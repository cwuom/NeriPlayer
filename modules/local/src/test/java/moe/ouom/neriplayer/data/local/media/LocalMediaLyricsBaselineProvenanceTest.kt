package moe.ouom.neriplayer.data.local.media

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.File
import moe.ouom.neriplayer.data.local.media.source.CrashLogCleanup
import moe.ouom.neriplayer.data.local.media.source.LocalMediaCoverAccess
import moe.ouom.neriplayer.data.local.media.source.LocalMediaDownloadAccess
import moe.ouom.neriplayer.data.local.media.source.LocalMediaHostAccess
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.stubbing.Answer
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LocalMediaLyricsBaselineProvenanceTest {
    @get:Rule val directory = TemporaryFolder()
    private val context = mock(Context::class.java)
    private val resolver = mock(ContentResolver::class.java)
    private val mediaAccessField = LocalMediaHostAccess::class.java.getDeclaredField("access").apply { isAccessible = true }
    private val previousMediaAccess = mediaAccessField.get(null)

    @Before
    fun bindMediaHost() {
        doReturn(resolver).`when`(context).contentResolver
        LocalMediaHostAccess.bind(mock(LocalMediaDownloadAccess::class.java), mock(LocalMediaCoverAccess::class.java), CrashLogCleanup { false })
        LocalMediaSupport.clearLyricsLookupCache()
        LocalMediaSupport.invalidateSafReadCaches()
    }

    @After
    fun restoreMediaHost() {
        mediaAccessField.set(null, previousMediaAccess)
        LocalMediaSupport.clearLyricsLookupCache()
        LocalMediaSupport.invalidateSafReadCaches()
    }

    @Test
    fun `unreadable direct sidecars stay unknown while independently absent and empty tracks resolve`() {
        val file = audio("direct")
        File(file.parentFile, "song.lrc").writeBytes(ByteArray(MAX_LOCAL_LYRIC_BYTES.toInt() + 1))
        File(file.parentFile, "song_trans.lrc").writeText("")
        val song = song(file)

        val result = withEmbeddedMetadata(song, file) {
            LocalMediaSupport.inspectLyricsFast(context, song, includeStoredFallback = false, forceRefresh = true)
        }

        assertNull(result.originalLyricBaseline)
        assertEquals("", result.translatedLyricBaseline)
        assertEquals("", result.romanizedLyricBaseline)
        assertEquals(false, result.hasOriginalSidecar)
        assertEquals(true, result.hasTranslatedSidecar)
    }

    @Test
    fun `known references that cannot be read do not become known empty originals`() {
        val file = audio("known")
        val song = song(file)
        val references = LocalKnownSidecarReferences(NearbyLyricReferences(
            File(file.parentFile, "missing.lrc").absolutePath,
            File(file.parentFile, "missing_trans.lrc").absolutePath,
            File(file.parentFile, "missing_roma.lrc").absolutePath
        ))

        val result = withEmbeddedMetadata(song, file) {
            LocalMediaSupport.inspectLyricsFast(context, song, includeStoredFallback = false,
                knownSidecarReferences = references, forceRefresh = true)
        }

        assertNull(result.originalLyricBaseline)
        assertNull(result.translatedLyricBaseline)
        assertNull(result.romanizedLyricBaseline)
    }

    @Test
    fun `unresolved content sidecar discovery cannot establish empty originals from an empty tag`() {
        val song = song(audio("content")).copy(mediaUri = "content://media/external/audio/media/9", localFilePath = null)

        val result = withEmbeddedMetadata(song, null) {
            LocalMediaSupport.inspectLyricsFast(context, song, includeStoredFallback = false, forceRefresh = true)
        }

        assertNull(result.originalLyricBaseline)
        assertNull(result.translatedLyricBaseline)
        assertNull(result.romanizedLyricBaseline)
    }

    @Test
    fun `scan inspection preserves unreadable sidecar provenance before merging embedded metadata`() {
        val file = audio("scan")
        File(file.parentFile, "song.lrc").writeBytes(ByteArray(MAX_LOCAL_LYRIC_BYTES.toInt() + 1))
        File(file.parentFile, "song_trans.lrc").writeText("actual translation")
        val song = song(file)

        val result = withEmbeddedMetadata(song, file) {
            LocalMediaSupport.inspectLyricsForScan(context, requireNotNull(song.localMediaUri()))
        }

        assertNull(result.originalLyricBaseline)
        assertEquals("actual translation", result.translatedLyricBaseline)
        assertEquals("", result.romanizedLyricBaseline)
    }

    @Test
    fun `complete known absence permits empty originals while incomplete absence stays unknown`() {
        val file = audio("complete-known")
        val song = song(file)

        withEmbeddedMetadata(song, file) {
            listOf(false, true).forEach { complete ->
                val result = LocalMediaSupport.inspectLyricsFast(context, song, includeStoredFallback = false,
                    knownSidecarReferences = LocalKnownSidecarReferences(NearbyLyricReferences(
                        null, null, null, lookupComplete = complete
                    )), forceRefresh = true)

                val expected = if (complete) "" else null
                assertEquals(expected, result.originalLyricBaseline)
                assertEquals(expected, result.translatedLyricBaseline)
                assertEquals(expected, result.romanizedLyricBaseline)
            }
        }
    }

    private fun <T> withEmbeddedMetadata(song: SongItem, file: File?, block: () -> T): T {
        val uri = requireNotNull(song.localMediaUri())
        val resolved = mock(LocalMediaSupport.ResolvedInspectableLocalMedia::class.java)
        doReturn(uri).`when`(resolved).playableUri
        doReturn(file).`when`(resolved).file
        doReturn("song.flac").`when`(resolved).displayName
        val embedded = mock(LocalMediaSupport.TagLibMetadata::class.java)
        return mockStatic(Class.forName("moe.ouom.neriplayer.data.local.media.LocalMediaSupportInspectionKt"),
            Answer<Any?> { invocation ->
                when (invocation.method.name) {
                    "resolveInspectableLocalMedia" -> resolved
                    "inspectTagLibMetadata" -> embedded
                    else -> invocation.callRealMethod()
                }
            }).use { block() }
    }

    private fun audio(name: String): File = File(directory.newFolder(name), "song.flac").apply { writeText("audio") }

    private fun song(file: File) = SongItem(
        id = 9L, name = "Song", artist = "Artist", album = "Album", albumId = 0L,
        durationMs = 1000L, coverUrl = null, mediaUri = Uri.fromFile(file).toString(),
        localFilePath = file.absolutePath, localFileName = file.name, channelId = "local"
    )
}
