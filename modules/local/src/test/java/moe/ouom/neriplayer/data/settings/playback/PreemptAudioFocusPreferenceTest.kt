package moe.ouom.neriplayer.data.settings.playback

import androidx.datastore.preferences.core.preferencesOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.settings.playback.PlaybackPreferenceSnapshot
import moe.ouom.neriplayer.data.settings.IsolatedSettingsDataStore
import moe.ouom.neriplayer.data.settings.SettingsKeys
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.testing.InMemorySharedPreferencesRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreemptAudioFocusPreferenceTest {
    private val preferences = InMemorySharedPreferencesRegistry()
    private val context = IsolatedSettingsDataStore.context(preferences)

    @Test
    fun `missing preference enables preempt audio focus in startup snapshots`() {
        assertTrue(PlaybackPreferenceSnapshot().preemptAudioFocus)
        assertTrue(preferencesOf().toPlaybackPreferenceSnapshot().preemptAudioFocus)
    }

    @Test
    fun `snapshot preserves explicit preempt audio focus choices`() {
        listOf(false, true).forEach { enabled ->
            val snapshot = preferencesOf(
                SettingsKeys.PREEMPT_AUDIO_FOCUS to enabled
            ).toPlaybackPreferenceSnapshot()

            assertEquals(enabled, snapshot.preemptAudioFocus)
        }
    }

    @Test
    fun `current cache without preference enables preempt audio focus`() {
        preferences["playback_snapshot_cache"].edit()
            .putBoolean("ready", true)
            .putInt("schema_version", 5)
            .commit()

        assertTrue(readPlaybackPreferenceSnapshotCached(context)!!.preemptAudioFocus)
    }

    @Test
    fun `cached explicit opt out survives synchronous startup reads`() {
        persistPlaybackPreferenceSnapshot(context, PlaybackPreferenceSnapshot(preemptAudioFocus = false))

        assertFalse(readPlaybackPreferenceSnapshotSync(context).preemptAudioFocus)
    }

    @Test
    fun `repository enables missing preference and retains opt out after recreation`() = runTest {
        IsolatedSettingsDataStore.withEmptySettings(context) {
            val repository = SettingsRepository(context)

            assertTrue(repository.preemptAudioFocusFlow.first())
            assertTrue(readPlaybackPreferenceSnapshot(context).preemptAudioFocus)
            assertTrue(readPlaybackPreferenceSnapshotCached(context)!!.preemptAudioFocus)

            repository.setPreemptAudioFocus(false)

            assertFalse(SettingsRepository(context).preemptAudioFocusFlow.first())
            assertFalse(readPlaybackPreferenceSnapshotSync(context).preemptAudioFocus)
            assertFalse(readPlaybackPreferenceSnapshot(context).preemptAudioFocus)

            repository.setPreemptAudioFocus(true)

            assertTrue(SettingsRepository(context).preemptAudioFocusFlow.first())
            assertTrue(readPlaybackPreferenceSnapshotSync(context).preemptAudioFocus)
        }
    }
}
