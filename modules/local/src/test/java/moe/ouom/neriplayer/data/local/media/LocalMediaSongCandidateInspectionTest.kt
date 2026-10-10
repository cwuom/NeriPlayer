package moe.ouom.neriplayer.data.local.media

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.local.LocalMediaDetails
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import java.io.File

class LocalMediaSongCandidateInspectionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val resolver = mock(ContentResolver::class.java)
    private val context = mock(Context::class.java)
    private val fileUri = mock(Uri::class.java)
    private lateinit var audio: File
    private lateinit var lyric: File
    private lateinit var song: SongItem

    @Before
    fun setUp() {
        val music = temporaryFolder.newFolder("music")
        audio = File(music, "Night Drive.mp3").apply { writeBytes(ByteArray(64)) }
        lyric = File(music, "Night Drive.lrc").apply { writeText(LYRIC) }
        song = SongItem(
            id = 7L,
            name = "Night Drive",
            artist = "",
            album = "",
            albumId = 0L,
            durationMs = 0L,
            coverUrl = null,
            localFilePath = audio.absolutePath
        )
        doReturn(resolver).`when`(context).contentResolver
        doReturn("Unknown artist").`when`(context).getString(CoreCommonR.string.music_unknown_artist)
        doReturn("Local Files").`when`(context).getString(CoreCommonR.string.local_files)
        doReturn("External lyric").`when`(context).getString(CoreCommonR.string.local_song_lyric_external)
        doReturn("file").`when`(fileUri).scheme
        doReturn(audio.absolutePath).`when`(fileUri).path
        doReturn(audio.name).`when`(fileUri).lastPathSegment
        doReturn("file://${audio.absolutePath}").`when`(fileUri).toString()
    }

    @Test
    fun `metadata only inspection names the file candidate and reads its nearby lyric`() {
        val details = withFileUri { LocalMediaSupport.inspectMetadataOnly(context, song, resolveCoverFallback = false) }

        assertEquals(
            listOf<Any?>(
                fileUri, "Night Drive.mp3", "Night Drive", "Unknown artist", true, audio.absolutePath, 64L,
                LYRIC, lyric.absolutePath, "External lyric", null
            ),
            summary(requireNotNull(details))
        )
    }

    @Test
    fun `full inspection names the file candidate and reads its nearby lyric`() {
        val details = withFileUri { LocalMediaSupport.inspect(context, song) }

        assertEquals(
            listOf<Any?>(
                fileUri, "Night Drive.mp3", "Night Drive", "Unknown artist", true, audio.absolutePath, 64L,
                LYRIC, lyric.absolutePath, "External lyric", null
            ),
            summary(requireNotNull(details))
        )
        assertEquals(listOf<Any?>(0L, null, false), listOf(details.durationMs, details.coverSource, details.embeddedCover))
    }

    @Test
    fun `candidates rejected by the provider are logged and yield no details`() {
        doThrow(SecurityException("type lookup denied")).`when`(resolver).getType(fileUri)

        mockStatic(Log::class.java).use { log ->
            val results = withFileUri {
                listOf(
                    LocalMediaSupport.inspect(context, song),
                    LocalMediaSupport.inspectMetadataOnly(context, song, resolveCoverFallback = false)
                )
            }

            assertEquals(listOf(null, null), results)
            val inspectFailure = "inspect candidate failed for file://${audio.absolutePath}: type lookup denied"
            val metadataFailure = "inspect metadata-only candidate failed for file://${audio.absolutePath}: type lookup denied"
            log.verify { Log.w(anyString(), eq(inspectFailure), isNull()) }
            log.verify { Log.w(anyString(), eq(metadataFailure), isNull()) }
        }
    }

    private fun <T> withFileUri(block: () -> T): T =
        mockStatic(Uri::class.java, CALLS_REAL_METHODS).use { uris ->
            uris.`when`<Uri> { Uri.fromFile(audio) }.thenReturn(fileUri)
            block()
        }

    private fun summary(details: LocalMediaDetails): List<Any?> = listOf(
        details.sourceUri,
        details.displayName,
        details.title,
        details.artist,
        details.usesFallbackAlbum,
        details.filePath,
        details.sizeBytes,
        details.lyricContent,
        details.lyricPath,
        details.lyricSource,
        details.coverUri
    )

    private companion object {
        const val LYRIC = "[00:01.00]night drive"
    }
}
