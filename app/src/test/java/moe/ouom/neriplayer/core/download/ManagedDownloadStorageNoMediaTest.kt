package moe.ouom.neriplayer.core.download

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadMediaScanIsolation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManagedDownloadStorageNoMediaTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `shouldCreateNoMediaMarker targets cover and temporary directories`() {
        assertTrue(ManagedDownloadStorage.shouldCreateNoMediaMarker("Covers"))
        assertTrue(ManagedDownloadStorage.shouldCreateNoMediaMarker("covers"))
        assertTrue(ManagedDownloadStorage.shouldCreateNoMediaMarker(".tmp"))
        assertFalse(ManagedDownloadStorage.shouldCreateNoMediaMarker("Lyrics"))
    }

    @Test
    fun `file directory isolation creates nomedia marker for cover directory`() {
        val coverDirectory = tempFolder.newFolder("Covers")

        ensureManagedMediaScanIsolation("Covers", coverDirectory)

        assertTrue(File(coverDirectory, ".nomedia").exists())
    }

    @Test
    fun `file directory isolation skips nomedia marker for lyric directory`() {
        val lyricDirectory = tempFolder.newFolder("Lyrics")

        ensureManagedMediaScanIsolation("Lyrics", lyricDirectory)

        assertFalse(File(lyricDirectory, ".nomedia").exists())
    }

    @Test
    fun `file directory isolation creates nomedia marker for temporary directory`() {
        val temporaryDirectory = tempFolder.newFolder(".tmp")

        ensureManagedMediaScanIsolation(".tmp", temporaryDirectory)

        assertTrue(File(temporaryDirectory, ".nomedia").exists())
    }

    private fun ensureManagedMediaScanIsolation(subdirectory: String, directory: File) {
        ManagedDownloadMediaScanIsolation.ensureFileDirectory(
            subdirectory = subdirectory,
            directory = directory,
            ensuredMarkers = ConcurrentHashMap()
        )
    }
}
