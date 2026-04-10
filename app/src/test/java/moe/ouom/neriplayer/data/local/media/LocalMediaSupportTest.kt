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

    @Test
    fun `preferredLocalMediaReference prefers content media uri over direct file path`() {
        val preferred = preferredLocalMediaReference(
            localFilePath = "/storage/emulated/0/Download/Oto music/dependant.ogg",
            mediaUri = "content://media/external/audio/media/42"
        )

        assertEquals("content://media/external/audio/media/42", preferred)
    }

    @Test
    fun `selectQuickLocalMetadata falls back to defaults when query metadata is sparse`() {
        val selection = LocalMediaSupport.selectQuickLocalMetadata(
            title = "Track Name",
            queriedArtist = "   ",
            queriedAlbum = null,
            queriedDurationMs = null,
            unknownArtistLabel = "Unknown Artist",
            defaultAlbumLabel = "Local Files"
        )

        assertEquals("Track Name", selection.title)
        assertEquals("Unknown Artist", selection.artist)
        assertEquals("Local Files", selection.album)
        assertEquals(true, selection.usesFallbackAlbum)
        assertEquals(0L, selection.durationMs)
    }

    @Test
    fun `selectQuickLocalMetadata keeps explicit metadata and clamps negative duration`() {
        val selection = LocalMediaSupport.selectQuickLocalMetadata(
            title = "Track Name",
            queriedArtist = "Artist",
            queriedAlbum = "Album",
            queriedDurationMs = -42L,
            unknownArtistLabel = "Unknown Artist",
            defaultAlbumLabel = "Local Files"
        )

        assertEquals("Artist", selection.artist)
        assertEquals("Album", selection.album)
        assertEquals(false, selection.usesFallbackAlbum)
        assertEquals(0L, selection.durationMs)
    }
}
