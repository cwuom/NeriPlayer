package moe.ouom.neriplayer.data.local.media

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalMediaNearbyCoverCacheTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun clearBefore() {
        LocalMediaSupport.clearCoverLookupCache()
    }

    @After
    fun clearAfter() {
        LocalMediaSupport.clearCoverLookupCache()
    }

    @Test
    fun `files without a folder have no nearby cover`() {
        assertNull(LocalMediaSupport.findNearbyCover(null))
        assertNull(LocalMediaSupport.findNearbyCover(File("song.flac")))
    }

    @Test
    fun `song named covers are remembered for the current folder state`() {
        val album = tempFolder.newFolder("album")
        val audio = File(album, "song.flac").apply { writeBytes(ByteArray(4)) }
        val cover = File(album, "song.jpg").apply { writeBytes(byteArrayOf(1)) }

        assertEquals(cover, LocalMediaSupport.findNearbyCover(audio))
        assertEquals(cover.absolutePath, cachedNearbyCover(audio, album))
        assertEquals(cover, LocalMediaSupport.findNearbyCover(audio))
    }

    @Test
    fun `remembered covers that disappeared are not returned`() {
        val album = tempFolder.newFolder("album")
        val audio = File(album, "song.flac").apply { writeBytes(ByteArray(4)) }
        val cover = File(album, "song.jpg").apply { writeBytes(byteArrayOf(1)) }
        assertEquals(cover, LocalMediaSupport.findNearbyCover(audio))
        val folderModifiedAt = album.lastModified()

        cover.delete()
        album.setLastModified(folderModifiedAt)

        assertEquals(cover.absolutePath, cachedNearbyCover(audio, album))
        assertNull(LocalMediaSupport.findNearbyCover(audio))
    }

    private fun cachedNearbyCover(audio: File, album: File): String? =
        synchronized(LocalMediaSupport.nearbyCoverLookupCache) {
            LocalMediaSupport.nearbyCoverLookupCache[LocalMediaSupport.nearbyCoverLookupKey(audio, album, "song")]
        }
}
