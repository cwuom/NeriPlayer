package moe.ouom.neriplayer.core.download

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManagedDownloadStorageNoMediaTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `shouldCreateNoMediaMarker only targets cover directory`() {
        assertTrue(ManagedDownloadStorage.shouldCreateNoMediaMarker("Covers"))
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

    private fun ensureManagedMediaScanIsolation(subdirectory: String, directory: File) {
        val method = ManagedDownloadStorage::class.java.getDeclaredMethod(
            "ensureManagedMediaScanIsolation",
            String::class.java,
            File::class.java
        )
        method.isAccessible = true
        method.invoke(ManagedDownloadStorage, subdirectory, directory)
    }
}
