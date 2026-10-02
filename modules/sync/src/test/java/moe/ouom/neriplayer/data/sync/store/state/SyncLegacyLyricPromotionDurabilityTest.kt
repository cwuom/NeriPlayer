package moe.ouom.neriplayer.data.sync.store.state

import com.google.gson.reflect.TypeToken
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.testing.MemorySyncPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncLegacyLyricPromotionDurabilityTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `synchronized promotion persists canonical override even when dynamic projection is equal`() {
        assertCanonicalPromotionPersisted(synchronized = true)
    }

    @Test
    fun `local equal promotion persists canonical override without inventing a user mutation`() {
        assertCanonicalPromotionPersisted(synchronized = false)
    }

    private fun assertCanonicalPromotionPersisted(synchronized: Boolean) {
        val preferences = MemorySyncPreferences()
        val directory = temporary.newFolder()
        val files = SyncDeletionStateStorage(preferences.preferences, directory)
        val legacy = SyncSong(id = 7, album = "Netease", matchedLyric = "old text", originalLyric = "baseline")
        assertTrue(files.commitEdit { files.write(this, KEY_LYRIC_OVERRIDES, listOf(legacy)) })
        val originalMarker = preferences.durableValues[KEY_LYRIC_OVERRIDES]
        val storage = SecureTokenStorage(preferences.preferences, directory)
        val projected = storage.getLyricOverrides().single()
        assertEquals(true, projected.lyricSyncEdited)
        if (synchronized) assertTrue(storage.setLyricOverridesIfMutationVersion(0, listOf(projected)))
        else storage.recordLyricOverride(projected)
        assertNotEquals(originalMarker, preferences.durableValues[KEY_LYRIC_OVERRIDES])
        val restartedFiles = SyncDeletionStateStorage(preferences.restart().preferences, directory)
        val type = object : TypeToken<List<SyncSong>>() {}.type
        val persisted = restartedFiles.read<List<SyncSong>>(KEY_LYRIC_OVERRIDES, type).orEmpty().single()
        assertEquals(projected, persisted)
        assertEquals(0L, storage.getSyncMutationVersion())
    }
}
