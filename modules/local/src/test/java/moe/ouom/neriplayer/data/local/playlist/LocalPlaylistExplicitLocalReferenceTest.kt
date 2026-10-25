package moe.ouom.neriplayer.data.local.playlist

import com.google.gson.Gson
import java.io.File
import java.io.IOException
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playlist.DISPLAY_ORDER_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.verifyNoInteractions

class LocalPlaylistExplicitLocalReferenceTest : LocalPlaylistRepositoryTestSupport() {
    @Test
    fun `cold singleton lookup remains unknown without creating a repository`() {
        assertNull(LocalPlaylistRepository.peekExplicitLocalFileReference("content://provider/document/song"))
        assertNull(LocalPlaylistRepository.peekExplicitLocalFileReference(""))
    }

    @Test
    fun `manual local member proves its exact preferred reference regardless of remote identity`() {
        val reference = "content://provider/tree/imported/document/Imported.flac"
        val imported = localSong(42).copy(
            mediaUri = reference,
            localFilePath = null,
            channelId = "netease",
            audioId = "42",
            sourceStableKey = "42|netease|"
        )
        val context = mockContext()
        val storage = GuardedStorage(Gson().toJson(listOf(playlist(LocalFilesPlaylist.SYSTEM_ID, imported))))
        val repository = LocalPlaylistRepository.createForTest(
            context = context,
            file = File(tempFolder.root, "explicit_reference.json"),
            storage = storage,
            syncMutationStore = RecordingSyncMutationStore()
        )
        assertTrue(repository.initializationReadyFlow.value)
        storage.rejectAccess = true
        clearInvocations(context)

        assertEquals(true, repository.containsExplicitLocalFileReferenceCached(reference))
        assertEquals(false, repository.containsExplicitLocalFileReferenceCached(reference + "?alias=other"))
        assertEquals(false, repository.containsExplicitLocalFileReferenceCached("content://provider/document/other-42"))
        assertEquals(false, repository.containsExplicitLocalFileReferenceCached(""))

        verifyNoInteractions(context)
    }

    @Test
    fun `non preferred aliases and unrelated playlists cannot prove manual import membership`() {
        val preferred = "content://provider/document/selected"
        val stalePath = "/music/stale-alias.flac"
        val filePath = "/music/selected.flac"
        val fileAlias = "file:///music/selected.flac"
        val ordinaryPlaylistReference = "content://provider/document/ordinary-playlist"
        val referenceCases = listOf(
            Triple(localSong(1).copy(mediaUri = preferred, localFilePath = stalePath), preferred, true),
            Triple(localSong(1).copy(mediaUri = preferred, localFilePath = stalePath), stalePath, false),
            Triple(localSong(2).copy(mediaUri = fileAlias, localFilePath = filePath), filePath, true),
            Triple(localSong(2).copy(mediaUri = fileAlias, localFilePath = filePath), fileAlias, false),
            Triple(localSong(3).copy(mediaUri = "https://example.test/song", localFilePath = null), "https://example.test/song", false)
        )
        referenceCases.forEachIndexed { index, (member, reference, expected) ->
            val storage = RecordingStorage(Gson().toJson(listOf(
                playlist(LocalFilesPlaylist.SYSTEM_ID, member),
                playlist(12L, localSong(4).copy(mediaUri = ordinaryPlaylistReference, localFilePath = null))
                    .copy(name = "Local Files")
            )))
            val repository = LocalPlaylistRepository.createForTest(
                context = mockContext(),
                file = File(tempFolder.root, "preferred_reference_$index.json"),
                storage = storage,
                syncMutationStore = RecordingSyncMutationStore()
            )

            assertEquals(reference, expected, repository.containsExplicitLocalFileReferenceCached(reference))
            assertEquals(false, repository.containsExplicitLocalFileReferenceCached(ordinaryPlaylistReference))
        }
    }

    @Test
    fun `failed initialization leaves even matching cached members unknown without retrying storage`() {
        val reference = "content://provider/document/unconfirmed"
        val member = localSong(42).copy(mediaUri = reference, localFilePath = null)
        val storage = GuardedStorage(Gson().toJson(listOf(playlist(LocalFilesPlaylist.SYSTEM_ID, member))))
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "failed_reference.json"),
            storage = storage,
            normalizePlaylists = { throw IOException("unconfirmed source") },
            syncMutationStore = RecordingSyncMutationStore()
        )
        repository._playlists.value = listOf(playlist(LocalFilesPlaylist.SYSTEM_ID, member))
        storage.rejectAccess = true

        assertNull(repository.containsExplicitLocalFileReferenceCached(reference))
    }

    private fun playlist(id: Long, vararg songs: SongItem) = LocalPlaylist(
        id = id,
        name = if (id == LocalFilesPlaylist.SYSTEM_ID) "Local Files" else "Other",
        songs = songs.toMutableList(),
        modifiedAt = 1_000L,
        songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
    )

    private class GuardedStorage(initial: String) : LocalPlaylistStorage {
        private val delegate = RecordingStorage(primary = initial)
        var rejectAccess = false

        override fun readPrimary(): String? = checked { delegate.readPrimary() }
        override fun readBackup(): String? = checked { delegate.readBackup() }
        override fun readPendingSyncMutation(): String? = checked { delegate.readPendingSyncMutation() }
        override fun quarantinePrimary(): File? = checked { delegate.quarantinePrimary() }

        override fun commit(text: String, rotateBackup: Boolean, replaceBackupWithCommittedPrimary: Boolean) {
            checked { delegate.commit(text, rotateBackup, replaceBackupWithCommittedPrimary) }
        }

        private fun <T> checked(action: () -> T): T {
            check(!rejectAccess) { "cached reference lookup must not touch storage" }
            return action()
        }
    }
}
