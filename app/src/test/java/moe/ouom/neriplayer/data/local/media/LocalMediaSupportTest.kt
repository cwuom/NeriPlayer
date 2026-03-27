package moe.ouom.neriplayer.data.local.media

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalMediaSupportTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `prepareShareableFileInDirectory stages arbitrary local file outside download directory`() {
        val sourceFile = tempFolder.newFile("library_track.flac").apply {
            writeText("lossless-audio")
            setLastModified(2_000L)
        }
        val shareDir = File(tempFolder.root, "cache/shared_media_exports").apply { mkdirs() }

        val stagedFile = LocalMediaSupport.prepareShareableFileInDirectory(sourceFile, shareDir)

        assertEquals(shareDir.canonicalPath, stagedFile.parentFile?.canonicalPath)
        assertEquals(
            LocalMediaSupport.shareableStageFileName(sourceFile),
            stagedFile.name
        )
        assertNotEquals(sourceFile.canonicalPath, stagedFile.canonicalPath)
        assertArrayEquals(sourceFile.readBytes(), stagedFile.readBytes())
        assertEquals(sourceFile.lastModified(), stagedFile.lastModified())
    }

    @Test
    fun `prepareShareableFileInDirectory reuses file already staged in share directory`() {
        val shareDir = File(tempFolder.root, "cache/shared_media_exports").apply { mkdirs() }
        val stagedSource = File(shareDir, "track.mp3").apply {
            writeText("already-staged")
            setLastModified(3_000L)
        }

        val preparedFile = LocalMediaSupport.prepareShareableFileInDirectory(stagedSource, shareDir)

        assertEquals(stagedSource.canonicalPath, preparedFile.canonicalPath)
        assertEquals("already-staged", preparedFile.readText())
    }

    @Test
    fun `prepareShareableFileInDirectory refreshes stale staged copy`() {
        val sourceFile = tempFolder.newFile("album_track.mp3").apply {
            writeText("fresh-audio")
            setLastModified(4_000L)
        }
        val shareDir = File(tempFolder.root, "cache/shared_media_exports").apply { mkdirs() }
        val stagedFile = File(shareDir, LocalMediaSupport.shareableStageFileName(sourceFile)).apply {
            writeText("stale-audio")
            setLastModified(1_000L)
        }

        val preparedFile = LocalMediaSupport.prepareShareableFileInDirectory(sourceFile, shareDir)

        assertEquals(stagedFile.canonicalPath, preparedFile.canonicalPath)
        assertEquals("fresh-audio", preparedFile.readText())
        assertEquals(sourceFile.lastModified(), preparedFile.lastModified())
    }

    @Test
    fun `prepareShareableFileInDirectory rejects directory input`() {
        val sourceDir = tempFolder.newFolder("not-a-file")
        val shareDir = File(tempFolder.root, "cache/shared_media_exports").apply { mkdirs() }

        assertThrows(IllegalArgumentException::class.java) {
            LocalMediaSupport.prepareShareableFileInDirectory(sourceDir, shareDir)
        }
    }

    @Test
    fun `resolveContentShareFallbackReference prefers explicit media content uri`() {
        val fallbackUri = resolveContentShareFallbackReference(
            localUri = "file:///storage/emulated/0/Music/song.flac",
            mediaUri = "content://media/external/audio/media/42"
        )

        assertEquals("content://media/external/audio/media/42", fallbackUri)
    }

    @Test
    fun `resolveContentShareFallbackReference falls back to local content uri`() {
        val fallbackUri = resolveContentShareFallbackReference(
            localUri = "content://media/external/audio/media/99",
            mediaUri = "/storage/emulated/0/Music/demo.flac"
        )

        assertEquals("content://media/external/audio/media/99", fallbackUri)
    }

    @Test
    fun `resolveContentShareFallbackReference returns null when no content uri is available`() {
        val fallbackUri = resolveContentShareFallbackReference(
            localUri = "file:///storage/emulated/0/Music/song.flac",
            mediaUri = "/storage/emulated/0/Music/demo.flac"
        )

        assertNull(fallbackUri)
    }
}
