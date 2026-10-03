package moe.ouom.neriplayer.data.sync.store

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlayDeletion
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.sync.identity.stableKey
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.state.GITHUB_PREFS_NAME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SyncDurableStateAndroidTest {
    @Test
    fun encryptedMarkersAndFsyncedGenerationsReopenAndRejectCorruption() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "sync-v3-durable-${UUID.randomUUID()}"
        val directory = File(base.noBackupFilesDir, namespace)
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this

            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("$namespace-$name", mode)

            override fun getNoBackupFilesDir(): File = directory
        }
        try {
            val edit = SyncSong(id = 7, album = "netease", matchedLyric = "用户歌词", lyricSyncEdited = true, lyricSyncRevision = 20)
            val reset = edit.copy(matchedLyric = null, lyricSyncEdited = false, lyricSyncRevision = 21)
            val writer = SecureTokenStorage(context)
            writer.recordLyricOverride(edit)
            val mutation = writer.getSyncMutationVersion()
            assertTrue(writer.setLyricOverridesIfMutationVersion(mutation, listOf(reset)))
            assertTrue(writer.setPlaylistDeletionStateIfMutationVersion(mutation, listOf(
                SyncPlaylist(id = 8, modifiedAt = 30, isDeleted = true)
            )))
            val deletion = SyncRecentPlayDeletion(songId = 7, album = "Netease", deletedAt = 40, deviceId = "synthetic")
            assertTrue(writer.setDeletionStateIfMutationVersion(mutation, listOf(deletion), emptyList()))

            val reopened = SecureTokenStorage(context)
            assertEquals(mutation, reopened.getSyncMutationVersion())
            assertEquals(mapOf(8L to 30L), reopened.getDeletedPlaylistTimestamps())
            assertEquals(listOf(deletion.copy(album = "netease")), reopened.getRecentPlayDeletions())
            assertEquals(21L, reopened.getLyricOverrides().single().lyricSyncRevision)
            assertEquals(false, reopened.getLyricOverrides().single().lyricSyncEdited)
            assertNull(reopened.getLyricOverrides().single().matchedLyric)
            assertEquals(reopened.getLyricOverrides(), reopened.getLyricOverridesForIdentityKeys(setOf(reset.stableKey())))

            val currentLyrics = File(directory, "sync-deletions").listFiles().orEmpty().single { file ->
                file.extension == "json" && file.readText().contains("\"lyricSyncRevision\":21")
            }
            currentLyrics.appendText(" ")
            assertThrows(IllegalStateException::class.java) { SecureTokenStorage(context).getLyricOverrides() }
            assertThrows(IllegalStateException::class.java) {
                SecureTokenStorage(context).getLyricOverridesForIdentityKeys(setOf(reset.stableKey()))
            }
            assertEquals(mapOf(8L to 30L), reopened.getDeletedPlaylistTimestamps())
        } finally {
            base.deleteSharedPreferences("$namespace-$GITHUB_PREFS_NAME")
            directory.deleteRecursively()
        }
    }
}
