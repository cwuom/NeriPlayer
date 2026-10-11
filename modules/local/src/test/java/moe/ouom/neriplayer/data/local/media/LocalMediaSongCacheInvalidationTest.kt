package moe.ouom.neriplayer.data.local.media

import moe.ouom.neriplayer.data.model.SongItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalMediaSongCacheInvalidationTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var album: File
    private lateinit var other: File

    @Before
    fun cacheTwoFolders() {
        LocalMediaSupport.clearCoverLookupCache()
        album = tempFolder.newFolder("album").also { File(it, "cover.jpg").writeBytes(byteArrayOf(1)) }
        other = tempFolder.newFolder("other").also { File(it, "folder.png").writeBytes(byteArrayOf(2)) }
        assertEquals(File(album, "cover.jpg"), LocalMediaSupport.findDirectoryCover(album))
        assertEquals(File(other, "folder.png"), LocalMediaSupport.findDirectoryCover(other))
        assertEquals(listOf(File(album, "cover.jpg")), LocalMediaSupport.directoryFileIndex(album).files)
        assertEquals(listOf(File(other, "folder.png")), LocalMediaSupport.directoryFileIndex(other).files)
    }

    @After
    fun clearCaches() {
        LocalMediaSupport.clearCoverLookupCache()
    }

    @Test
    fun `local songs drop the cached cover and file index of their own folder`() {
        LocalMediaSupport.invalidateSongAssetCaches(song(localFilePath = File(album, "song.flac").absolutePath))

        assertEquals(setOf(LocalMediaSupport.directoryCoverLookupKey(other)), directoryCoverKeys())
        assertEquals(setOf(other.absolutePath), directoryIndexKeys())
    }

    @Test
    fun `songs without a local folder keep every folder cache`() {
        LocalMediaSupport.invalidateSongAssetCaches(song(mediaUri = "content://media/external/audio/media/9"))

        assertEquals(
            setOf(LocalMediaSupport.directoryCoverLookupKey(album), LocalMediaSupport.directoryCoverLookupKey(other)),
            directoryCoverKeys()
        )
        assertEquals(setOf(album.absolutePath, other.absolutePath), directoryIndexKeys())
    }

    @Test
    fun `blank or parentless local paths keep every folder cache`() {
        LocalMediaSupport.invalidateSongAssetCaches(song(localFilePath = " "))
        LocalMediaSupport.invalidateSongAssetCaches(song(localFilePath = "song.flac"))

        assertEquals(
            setOf(LocalMediaSupport.directoryCoverLookupKey(album), LocalMediaSupport.directoryCoverLookupKey(other)),
            directoryCoverKeys()
        )
        assertEquals(setOf(album.absolutePath, other.absolutePath), directoryIndexKeys())
    }

    private fun directoryCoverKeys(): Set<String> =
        synchronized(LocalMediaSupport.directoryCoverLookupCache) {
            LocalMediaSupport.directoryCoverLookupCache.keys.toSet()
        }

    private fun directoryIndexKeys(): Set<String> =
        synchronized(LocalMediaSupport.directoryFileIndexCache) {
            LocalMediaSupport.directoryFileIndexCache.keys.toSet()
        }

    private fun song(localFilePath: String? = null, mediaUri: String? = null) = SongItem(
        id = 9L,
        name = "Song",
        artist = "Artist",
        album = "Album",
        albumId = 0L,
        durationMs = 1_000L,
        coverUrl = null,
        mediaUri = mediaUri,
        localFilePath = localFilePath
    )
}
