package moe.ouom.neriplayer.core.download

import android.net.Uri
import java.io.File
import java.io.IOException
import moe.ouom.neriplayer.core.download.storage.COVER_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.LYRIC_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeDirectories
import moe.ouom.neriplayer.data.model.download.storage.StorageMutationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic

class ManagedDownloadStorageReferenceResolutionTest {
    private val storage = ManagedDownloadStorage

    @Test
    fun `saf path segments expose the document id of tree and document uris`() {
        with(storage) {
            assertEquals(
                "primary:Music/NeriPlayer/song.flac",
                listOf("tree", "primary:Music", "document", "primary:Music/NeriPlayer/song.flac")
                    .migrationDocumentIdFromSafPath()
            )
            assertEquals(
                "primary:Music/song.flac",
                listOf("document", "primary:Music/song.flac").migrationDocumentIdFromSafPath()
            )
            listOf(
                listOf("tree", "primary:Music"),
                listOf("tree", "primary:Music", "children", "primary:Music/song.flac"),
                listOf("root", "primary:Music", "document", "primary:Music/song.flac"),
                listOf("document"),
                emptyList()
            ).forEach { segments ->
                assertNull(segments.toString(), segments.migrationDocumentIdFromSafPath())
            }
        }
    }

    @Test
    fun `only deleted or already missing references confirm a storage mutation`() {
        with(storage) {
            assertTrue(StorageMutationResult.Deleted.isConfirmedStorageMutation())
            assertTrue(StorageMutationResult.Missing.isConfirmedStorageMutation())
            listOf(
                StorageMutationResult.OutOfScope,
                StorageMutationResult.PermissionLost,
                StorageMutationResult.ProviderFailure(IOException("provider busy")),
                StorageMutationResult.Unsupported("delete")
            ).forEach { result ->
                assertFalse(result.toString(), result.isConfirmedStorageMutation())
            }
        }
    }

    @Test
    fun `promotion size prefers counted bytes and ignores non positive sizes`() {
        assertEquals(2_048L, storage.resolvePendingTreeAudioPromotionExpectedSize(1_024L, 2_048L))
        assertEquals(1_024L, storage.resolvePendingTreeAudioPromotionExpectedSize(1_024L, 0L))
        assertEquals(1_024L, storage.resolvePendingTreeAudioPromotionExpectedSize(1_024L, null))
        assertNull(storage.resolvePendingTreeAudioPromotionExpectedSize(0L, -1L))
        assertNull(storage.resolvePendingTreeAudioPromotionExpectedSize(null, null))
    }

    @Test
    fun `playable uri keeps content references and turns absolute paths into file uris`() {
        val path = "/storage/emulated/0/Music/NeriPlayer/song.flac"
        val fileUri = mock(Uri::class.java)
        doReturn("file://$path").`when`(fileUri).toString()

        mockStatic(Uri::class.java).use { uris ->
            uris.`when`<Uri> { Uri.fromFile(File(path)) }.thenReturn(fileUri)

            assertEquals("file://$path", storage.toPlayableUri(path))
        }
        assertEquals(
            "content://media/external/audio/media/1",
            storage.toPlayableUri("content://media/external/audio/media/1")
        )
        assertNull(storage.toPlayableUri(null))
        assertNull(storage.toPlayableUri("  "))
    }

    @Test
    fun `migration refresh finds an entry only inside its own subdirectory listing`() {
        val rootAudio = entry("song.flac", "/music/NeriPlayer/song.flac")
        val otherAudio = entry("other.flac", "/music/NeriPlayer/other.flac")
        val cover = entry("song.jpg", "/music/NeriPlayer/Covers/song.jpg")
        val lyric = entry("song.lrc", "/music/NeriPlayer/Lyrics/song.lrc")
        val refresh = ManagedDownloadTreeDirectories.ManagedMigrationEntriesRefresh(
            rootEntries = listOf(otherAudio, rootAudio),
            coverEntries = listOf(cover),
            lyricEntries = listOf(lyric),
            isComplete = true
        )

        with(storage) {
            assertEquals(rootAudio, refresh.entryFor(null, rootAudio.copy(sizeBytes = 0L)))
            assertEquals(cover, refresh.entryFor(COVER_SUBDIRECTORY, cover.copy(reference = "/stale/song.jpg")))
            assertEquals(lyric, refresh.entryFor(LYRIC_SUBDIRECTORY, lyric))
            assertNull(refresh.entryFor(COVER_SUBDIRECTORY, rootAudio))
            assertNull(refresh.entryFor("Playlists", rootAudio))
            assertNull(refresh.entryFor(null, entry("missing.flac", "/music/NeriPlayer/missing.flac")))
        }
    }

    private fun entry(name: String, path: String) = ManagedDownloadStorage.StoredEntry(
        name = name,
        reference = path,
        mediaUri = "file://$path",
        localFilePath = path,
        sizeBytes = 10L,
        lastModifiedMs = 1L
    )
}
