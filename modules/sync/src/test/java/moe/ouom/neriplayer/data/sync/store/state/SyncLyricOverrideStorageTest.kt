package moe.ouom.neriplayer.data.sync.store.state

import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistSongDeletion
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlayDeletion
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.testing.MemorySyncPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncLyricOverrideStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `committed overrides notify other storage handles without notifying unrelated directories`() {
        val prefs = MemorySyncPreferences()
        val directory = temporary.newFolder()
        val writer = SecureTokenStorage(prefs.preferences, directory)
        val reader = SecureTokenStorage(prefs.preferences, directory)
        val unrelated = SecureTokenStorage(MemorySyncPreferences().preferences, temporary.newFolder())
        val before = reader.lyricOverridesVersion.value
        val unrelatedBefore = unrelated.lyricOverridesVersion.value
        writer.recordLyricOverride(edited())
        assertEquals(before + 1, reader.lyricOverridesVersion.value)
        assertEquals("custom", reader.getLyricOverrides().single().matchedLyric)
        assertEquals(unrelatedBefore, unrelated.lyricOverridesVersion.value)
        writer.recordLyricOverride(edited())
        assertEquals(before + 1, reader.lyricOverridesVersion.value)
        assertTrue(writer.setLyricOverridesIfMutationVersion(1, listOf(edited().copy(lyricSyncEdited = false, lyricSyncRevision = 21))))
        assertEquals(before + 2, reader.lyricOverridesVersion.value)
        assertEquals(false, reader.getLyricOverrides().single().lyricSyncEdited)
        assertEquals(1L, writer.getSyncMutationVersion())
    }

    @Test
    fun `rejected and failed commits never notify observers`() {
        val prefs = MemorySyncPreferences()
        val directory = temporary.newFolder()
        val writer = SecureTokenStorage(prefs.preferences, directory)
        writer.recordLyricOverride(edited())
        val before = writer.lyricOverridesVersion.value
        val reset = edited().copy(lyricSyncEdited = false, lyricSyncRevision = 21)
        assertFalse(writer.setLyricOverridesIfMutationVersion(0, listOf(reset)))
        prefs.failNextCommit = true
        assertThrows(IllegalStateException::class.java) { writer.setLyricOverridesIfMutationVersion(1, listOf(reset)) }
        prefs.failNextCommit = true
        assertThrows(IllegalStateException::class.java) { writer.recordLyricOverride(reset) }
        assertEquals(before, writer.lyricOverridesVersion.value)
        assertEquals(false, writer.getLyricOverrides().single().lyricSyncEdited)
        assertEquals(true, SecureTokenStorage(prefs.restart().preferences, directory).getLyricOverrides().single().lyricSyncEdited)
    }

    @Test
    fun `memory only storage notifications share one preference namespace`() {
        val prefs = MemorySyncPreferences()
        val writer = SecureTokenStorage(prefs.preferences)
        val reader = SecureTokenStorage(prefs.preferences)
        val unrelated = SecureTokenStorage(MemorySyncPreferences().preferences)
        val before = reader.lyricOverridesVersion.value
        val unrelatedBefore = unrelated.lyricOverridesVersion.value
        writer.recordLyricOverride(edited())
        assertEquals(before + 1, reader.lyricOverridesVersion.value)
        assertEquals(unrelatedBefore, unrelated.lyricOverridesVersion.value)
    }

    @Test
    fun `reset survives container removal and old override replay`() {
        val prefs = MemorySyncPreferences()
        val directory = temporary.newFolder()
        val store = SecureTokenStorage(prefs.preferences, directory)
        store.recordLyricOverride(edited())
        store.recordLyricOverride(edited().copy(matchedLyric = null, lyricSyncEdited = false, lyricSyncRevision = 21))
        store.recordLyricOverride(edited())
        store.recordLyricOverride(edited().copy(matchedLyric = "same revision stale edit", lyricSyncRevision = 21))
        assertEquals(2L, store.getSyncMutationVersion())
        assertTrue(store.setLyricOverridesIfMutationVersion(2, emptyList()))
        assertFalse(store.setLyricOverridesIfMutationVersion(1, listOf(edited())))
        store.addRecentPlayDeletions(emptyList())
        store.addPlaylistUsageDeletion("local:7")

        val saved = SecureTokenStorage(prefs.preferences, directory).getLyricOverrides().single()
        assertEquals(21L, saved.lyricSyncRevision)
        assertEquals(false, saved.lyricSyncEdited)
        assertNull(saved.matchedLyric)
    }

    @Test
    fun `failed reset commit cannot replace the durable override`() {
        val prefs = MemorySyncPreferences()
        val directory = temporary.newFolder()
        val store = SecureTokenStorage(prefs.preferences, directory)
        store.recordLyricOverride(edited())
        prefs.failNextCommit = true
        assertThrows(IllegalStateException::class.java) {
            store.recordLyricOverride(edited().copy(matchedLyric = null, lyricSyncEdited = false, lyricSyncRevision = 21))
        }
        assertNull(store.getLyricOverrides().single().matchedLyric)
        assertEquals(2L, store.getSyncMutationVersion())
        val restarted = SecureTokenStorage(prefs.restart().preferences, directory)
        assertEquals("custom", restarted.getLyricOverrides().single().matchedLyric)
        assertEquals(1L, restarted.getSyncMutationVersion())
    }

    @Test
    fun `synchronized reset persists only under the captured mutation version`() {
        val prefs = MemorySyncPreferences()
        val directory = temporary.newFolder()
        val store = SecureTokenStorage(prefs.preferences, directory)
        store.recordLyricOverride(edited())
        val reset = edited().copy(matchedLyric = null, lyricSyncEdited = false, lyricSyncRevision = 21)

        assertFalse(store.setLyricOverridesIfMutationVersion(0, listOf(reset)))
        assertEquals("custom", store.getLyricOverrides().single().matchedLyric)
        assertTrue(store.setLyricOverridesIfMutationVersion(1, listOf(reset)))

        val reopened = SecureTokenStorage(prefs.preferences, directory)
        val saved = reopened.getLyricOverrides().single()
        assertEquals(21L, saved.lyricSyncRevision)
        assertEquals(false, saved.lyricSyncEdited)
        assertNull(saved.matchedLyric)
        assertEquals(1L, reopened.getSyncMutationVersion())
        assertTrue(reopened.setLyricOverridesIfMutationVersion(1, listOf(edited())))
        assertEquals(saved, reopened.getLyricOverrides().single())
    }

    @Test
    fun `failed guarded reset commit preserves the override and mutation version`() {
        val prefs = MemorySyncPreferences()
        val directory = temporary.newFolder()
        val store = SecureTokenStorage(prefs.preferences, directory)
        store.recordLyricOverride(edited())
        val marker = prefs.values[KEY_LYRIC_OVERRIDES]
        prefs.failNextCommit = true

        assertThrows(IllegalStateException::class.java) {
            store.setLyricOverridesIfMutationVersion(
                1,
                listOf(edited().copy(matchedLyric = null, lyricSyncEdited = false, lyricSyncRevision = 21))
            )
        }

        assertNotEquals(marker, prefs.values[KEY_LYRIC_OVERRIDES])
        assertEquals(marker, prefs.durableValues[KEY_LYRIC_OVERRIDES])
        assertNull(store.getLyricOverrides().single().matchedLyric)
        val reopened = SecureTokenStorage(prefs.restart().preferences, directory)
        assertEquals("custom", reopened.getLyricOverrides().single().matchedLyric)
        assertEquals(1L, reopened.getSyncMutationVersion())
    }

    @Test
    fun `failed guarded reset retries the same generation and notifies shared observers exactly once`() {
        val prefs = MemorySyncPreferences()
        val directory = temporary.newFolder()
        val writer = SecureTokenStorage(prefs.preferences, directory)
        val observer = SecureTokenStorage(prefs.preferences, directory)
        writer.recordLyricOverride(edited())
        val before = observer.lyricOverridesVersion.value
        val reset = edited().copy(matchedLyric = null, lyricSyncEdited = false, lyricSyncRevision = 21)
        prefs.failNextCommit = true
        assertThrows(IllegalStateException::class.java) { writer.setLyricOverridesIfMutationVersion(1, listOf(reset)) }
        val pendingMarker = prefs.values[KEY_LYRIC_OVERRIDES]
        val pendingFiles = directory.listFiles().orEmpty().map { it.name }.toSet()
        assertEquals(before, observer.lyricOverridesVersion.value)
        assertEquals("custom", SecureTokenStorage(prefs.restart().preferences, directory).getLyricOverrides().single().matchedLyric)

        prefs.failNextCommit = true
        assertThrows(IllegalStateException::class.java) { observer.setLyricOverridesIfMutationVersion(1, listOf(reset)) }
        assertEquals(before, observer.lyricOverridesVersion.value)
        assertEquals(pendingMarker, prefs.values[KEY_LYRIC_OVERRIDES])
        assertEquals(pendingFiles, directory.listFiles().orEmpty().map { it.name }.toSet())

        assertTrue(observer.setLyricOverridesIfMutationVersion(1, listOf(reset)))
        assertEquals(emptySet<String>(), prefs.commits.last())
        assertEquals(pendingMarker, prefs.durableValues[KEY_LYRIC_OVERRIDES])
        assertEquals(before + 1, observer.lyricOverridesVersion.value)
        assertEquals(pendingFiles, directory.listFiles().orEmpty().map { it.name }.toSet())
        writer.recordLyricOverride(reset)
        assertTrue(writer.setLyricOverridesIfMutationVersion(1, listOf(reset)))
        assertEquals(before + 1, observer.lyricOverridesVersion.value)
        val restarted = SecureTokenStorage(prefs.restart().preferences, directory)
        assertNull(restarted.getLyricOverrides().single().matchedLyric)
        assertEquals(false, restarted.getLyricOverrides().single().lyricSyncEdited)
        assertEquals(1L, restarted.getSyncMutationVersion())
    }

    @Test
    fun `same local override retry confirms pending mutation without generating another file`() {
        val prefs = MemorySyncPreferences()
        val directory = temporary.newFolder()
        val writer = SecureTokenStorage(prefs.preferences, directory)
        prefs.failNextCommit = true
        assertThrows(IllegalStateException::class.java) { writer.recordLyricOverride(edited()) }
        val marker = prefs.values[KEY_LYRIC_OVERRIDES]
        assertEquals(0L, writer.lyricOverridesVersion.value)
        assertTrue(SecureTokenStorage(prefs.restart().preferences, directory).getLyricOverrides().isEmpty())

        writer.recordLyricOverride(edited())
        assertEquals(emptySet<String>(), prefs.commits.last())
        assertEquals(marker, prefs.durableValues[KEY_LYRIC_OVERRIDES])
        assertEquals(1, directory.listFiles().orEmpty().size)
        assertEquals(1L, writer.lyricOverridesVersion.value)
        val restarted = SecureTokenStorage(prefs.restart().preferences, directory)
        assertEquals("custom", restarted.getLyricOverrides().single().matchedLyric)
        assertEquals(1L, restarted.getSyncMutationVersion())
    }

    @Test
    fun `clearing github configuration preserves permanent reset causal state and tombstones`() {
        val prefs = MemorySyncPreferences(mapOf(KEY_PLAY_HISTORY_UPDATE_MODE to "BATCHED", "other_account" to "retained"))
        val directory = temporary.newFolder()
        val store = SecureTokenStorage(prefs.preferences, directory)
        store.saveDeviceId("device-a")
        val token = store.nextSyncCausalTokens(1).single()
        store.recordLyricOverride(edited())
        store.recordLyricOverride(edited().copy(matchedLyric = null, lyricSyncEdited = false, lyricSyncRevision = 21))
        store.addDeletedPlaylistId(7)
        store.addPlaylistSongDeletions(listOf(SyncPlaylistSongDeletion(
            playlistId = 8, songId = 7, album = "netease", deletedAt = 25,
            removedMembershipTokens = listOf(token)
        )))
        store.addRecentPlayDeletions(listOf(SyncRecentPlayDeletion(songId = 7, album = "netease", deletedAt = 25)))
        store.addPlaylistUsageDeletion("local:7", 25)
        val durableState = prefs.values.toMap()
        store.saveToken("test-token")
        store.saveRepository("test-owner", "test-repository")
        store.saveLastSyncTime(99)
        store.saveLastRemoteSha("test-sha")
        store.setAutoSyncEnabled(false)
        store.setDataSaverMode(false)
        store.setTokenWarningDismissed(true)

        store.clearAll()

        assertFalse(store.isConfigured())
        assertNull(store.getToken())
        assertNull(store.getRepoOwner())
        assertNull(store.getRepoName())
        assertEquals(0L, store.getLastSyncTime())
        assertNull(store.getLastRemoteSha())
        assertEquals(durableState, prefs.values.toMap() - "sync_configuration_generation")
        assertEquals(setOf("sync_configuration_generation"), prefs.values.keys - durableState.keys)
        val reopened = SecureTokenStorage(prefs.preferences, directory)
        assertEquals("device-a", reopened.getDeviceId())
        assertEquals(21L, reopened.getLyricOverrides().single().lyricSyncRevision)
        assertEquals(false, reopened.getLyricOverrides().single().lyricSyncEdited)
        assertEquals(listOf(token), reopened.getPlaylistSongDeletions().single().removedMembershipTokens)
        assertEquals(store.getDeletedPlaylistTimestamps(), reopened.getDeletedPlaylistTimestamps())
        assertEquals(store.getRecentPlayDeletions(), reopened.getRecentPlayDeletions())
        assertEquals(store.getPlaylistUsageDeletions(), reopened.getPlaylistUsageDeletions())
        assertEquals((durableState.getValue(KEY_SYNC_CAUSAL_COUNTER) as Long) + 1, reopened.nextSyncCausalTokens(1).single().counter)
    }

    private fun edited() = SyncSong(
        id = 7, album = "netease", matchedLyric = "custom",
        lyricSyncEdited = true, lyricSyncRevision = 20
    )
}
