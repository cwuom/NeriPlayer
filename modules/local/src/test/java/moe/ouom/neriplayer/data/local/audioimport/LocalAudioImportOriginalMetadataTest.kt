package moe.ouom.neriplayer.data.local.audioimport

import android.content.Context
import java.io.File
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.source.CrashLogCleanup
import moe.ouom.neriplayer.data.local.media.source.LocalMediaCoverAccess
import moe.ouom.neriplayer.data.local.media.source.LocalMediaDownloadAccess
import moe.ouom.neriplayer.data.local.media.source.LocalMediaHostAccess
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.download.DownloadLibraryEntry
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.doReturn
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocalAudioImportOriginalMetadataTest {
    @get:Rule val directory = TemporaryFolder()
    private lateinit var downloads: LocalMediaDownloadAccess

    @Before
    fun bindLocalMedia() {
        downloads = mock(LocalMediaDownloadAccess::class.java)
        LocalMediaHostAccess.bind(downloads, mock(LocalMediaCoverAccess::class.java), CrashLogCleanup { false })
    }

    @After
    fun clearCache() = LocalMediaSupport.clearLyricsLookupCache()

    @Test
    fun `text hydration preserves the original lyrics and explicit missing translation`() {
        val song = song().copy(originalLyric = "original", originalTranslatedLyric = "")
        File(directory.root, "song.lrc").writeText("edited")
        File(directory.root, "song_trans.lrc").writeText("edited translation")

        val hydrated = hydrate(song)

        assertEquals("edited", hydrated.matchedLyric)
        assertEquals("original", hydrated.originalLyric)
        assertEquals("", hydrated.originalTranslatedLyric)
    }

    @Test
    fun `fresh import recovers original lyrics and cover from metadata instead of current sidecars`() {
        val song = song()
        File(directory.root, "song.lrc").writeText("edited")
        File(directory.root, "song.flac.npmeta.json").writeText(
            """{"name":"Song","originalLyric":"original","originalTranslatedLyric":"",
                "originalCoverUrl":"/saved/original.jpg","coverPath":"/saved/edited.jpg"}"""
        )

        val hydrated = hydrate(song)

        assertEquals("edited", hydrated.matchedLyric)
        assertEquals("original", hydrated.originalLyric)
        assertEquals("", hydrated.originalTranslatedLyric)
        assertEquals("/saved/original.jpg", hydrated.originalCoverUrl)
    }

    @Test
    fun `explicitly absent original cover survives another text hydration`() {
        val song = song().copy(coverUrl = "/saved/edited.jpg", originalCoverUrl = "")
        assertEquals("", hydrate(song).originalCoverUrl)
    }

    @Test
    fun `fresh import preserves an explicitly absent original cover`() {
        val song = song()
        File(directory.root, "song.flac.npmeta.json").writeText(
            """{"name":"Song","originalCoverUrl":"","coverPath":"/saved/edited.jpg"}"""
        )
        val hydrated = hydrate(song)
        assertEquals("/saved/edited.jpg", hydrated.coverUrl)
        assertEquals("", hydrated.originalCoverUrl)
    }

    @Test
    fun `rebinding the current cover never changes the original cover`() {
        val song = song()
        val original = "content://test.documents/document/original-cover"
        val edited = "content://test.documents/document/edited-cover"
        File(directory.root, "song.flac.npmeta.json").writeText(
            """{"name":"Song","originalCoverUrl":"$original","coverPath":"$edited"}"""
        )
        val audio = mock(DownloadLibraryEntry::class.java)
        doReturn(audio).`when`(downloads).peekDownloadedAudio(song)
        doReturn(edited).`when`(downloads).peekCoverReference(audio)

        val hydrated = hydrate(song)

        assertEquals(edited, hydrated.coverUrl)
        assertEquals(original, hydrated.originalCoverUrl)
    }

    @Test
    fun `merging detailed metadata preserves an explicitly absent original cover`() {
        val quick = song().copy(originalCoverUrl = "")
        val detailed = quick.copy(coverUrl = "/saved/edited.jpg", originalCoverUrl = "/saved/edited.jpg")

        val merged = LocalAudioImportManager.mergeImportedSongMetadata(quick, detailed)

        assertEquals("/saved/edited.jpg", merged.coverUrl)
        assertEquals("", merged.originalCoverUrl)
    }

    @Test
    fun `merging a media store preview keeps the authoritative empty original from a detailed scan`() {
        val quick = song()
        val detailed = quick.copy(coverUrl = "/saved/edited.jpg", originalCoverUrl = "")

        val merged = LocalAudioImportManager.mergeImportedSongMetadata(quick, detailed)

        assertEquals("/saved/edited.jpg", merged.coverUrl)
        assertEquals("", merged.originalCoverUrl)
    }

    @Test
    fun `cover hydration preserves an explicitly absent original cover when finding edited artwork`() {
        val edited = directory.newFile("edited.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val song = song().copy(originalCoverUrl = "", customCoverUrl = edited.absolutePath)

        val hydrated = LocalAudioImportManager.hydrateLocalSongCoverMetadata(mock(Context::class.java), song)

        assertEquals(edited.absolutePath, hydrated.coverUrl)
        assertEquals("", hydrated.originalCoverUrl)
    }

    private fun song(): SongItem {
        val audio = directory.newFile("song.flac")
        return SongItem(
            id = 1L, name = "Song", artist = "Artist", album = "Album", albumId = 0L,
            durationMs = 1000L, coverUrl = null, localFilePath = audio.absolutePath,
            mediaUri = android.net.Uri.fromFile(audio).toString(), channelId = "local"
        )
    }

    private fun hydrate(song: SongItem) = LocalAudioImportManager.hydrateLocalSongTextMetadata(
        context = mock(Context::class.java), song = song, resolveCoverFallback = false,
        includeEmbeddedFallback = false, includeEmbeddedCoverFallback = false,
        resolveDurationFallback = false
    )
}
