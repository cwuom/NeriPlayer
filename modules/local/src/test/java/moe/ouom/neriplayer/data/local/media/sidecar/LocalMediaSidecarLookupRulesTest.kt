package moe.ouom.neriplayer.data.local.media.sidecar

import moe.ouom.neriplayer.data.local.media.LEGACY_DOWNLOAD_ROOT
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.NearbyLyricFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalMediaSidecarLookupRulesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `cover samples halve until both edges fit the cache dimension`() {
        assertEquals(8, LocalMediaSupport.embeddedCoverCacheSampleSizeImpl(4000, 3000, 512))
        assertEquals(4, LocalMediaSupport.embeddedCoverCacheSampleSizeImpl(300, 2000, 512))
        assertEquals(1, LocalMediaSupport.embeddedCoverCacheSampleSizeImpl(512, 512, 512))
        assertEquals(
            listOf(1, 1, 1),
            listOf(
                LocalMediaSupport.embeddedCoverCacheSampleSizeImpl(0, 4000, 512),
                LocalMediaSupport.embeddedCoverCacheSampleSizeImpl(4000, -1, 512),
                LocalMediaSupport.embeddedCoverCacheSampleSizeImpl(4000, 4000, 0)
            )
        )
    }

    @Test
    fun `document children match only their own parent, id and display name`() {
        val path = listOf("root", "album", "doc-7")

        assertTrue(matches(path, "album", "doc-7", actualDisplayName = "song.FLAC"))
        assertTrue(matches(path, "album", "doc-7", actualDisplayName = null))
        assertFalse(matches(path, "album", "doc-7", actualDisplayName = "other.flac"))
        assertFalse(matches(path, "album", null, actualDisplayName = null))
        assertFalse(matches(path, "album", " ", actualDisplayName = null))
        assertFalse(matches(path, "artist", "doc-7", actualDisplayName = null))
        assertFalse(matches(path, "album", "doc-8", actualDisplayName = null))
    }

    @Test
    fun `managed sidecar directories accept the same name or a numbered duplicate`() {
        assertTrue(LocalMediaSupport.isManagedSidecarDirectoryNameImpl("lyrics", "Lyrics"))
        assertTrue(LocalMediaSupport.isManagedSidecarDirectoryNameImpl("Cafe\u0301", "Caf\u00e9"))
        assertTrue(LocalMediaSupport.isManagedSidecarDirectoryNameImpl("lyrics (2)", "Lyrics"))
        assertFalse(LocalMediaSupport.isManagedSidecarDirectoryNameImpl("Covers", "Lyrics"))
        assertFalse(LocalMediaSupport.isManagedSidecarDirectoryNameImpl("Lyrics (2", "Lyrics"))
        assertFalse(LocalMediaSupport.isManagedSidecarDirectoryNameImpl("Lyrics (two)", "Lyrics"))
    }

    @Test
    fun `nearby lyric files prefer the Lyrics folder over the audio folder`() {
        val album = temporaryFolder.newFolder("album")
        val audio = File(album, "song.flac").apply { writeText("audio") }
        val lyricsFolder = File(album, "Lyrics").apply { mkdir() }
        val original = File(lyricsFolder, "song.lrc").apply { writeText("[00:01]folder") }
        File(album, "song.lrc").writeText("[00:01]shadowed")
        val translated = File(album, "song_trans.txt").apply { writeText("translated") }

        assertEquals(NearbyLyricFiles(original, translated, null), LocalMediaSupport.findNearbyLyricFilesImpl(audio))
    }

    @Test
    fun `songs without a usable folder have no nearby lyric files`() {
        val none = NearbyLyricFiles(null, null, null)

        assertEquals(none, LocalMediaSupport.findNearbyLyricFilesImpl(null))
        assertEquals(none, LocalMediaSupport.findNearbyLyricFilesImpl(File("song.flac")))
        assertEquals(none, LocalMediaSupport.findNearbyLyricFilesImpl(File(LEGACY_DOWNLOAD_ROOT, "song.flac")))
    }

    private fun matches(
        path: List<String>,
        parentDocumentId: String,
        sourceDocumentId: String?,
        actualDisplayName: String?
    ): Boolean = LocalMediaSupport.matchesDocumentPathParentImpl(
        path = path,
        parentDocumentId = parentDocumentId,
        sourceDocumentId = sourceDocumentId,
        displayName = "Song.flac",
        actualDisplayName = actualDisplayName
    )
}
