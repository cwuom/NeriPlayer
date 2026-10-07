package moe.ouom.neriplayer.data.settings.playback

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.playback.PlaybackEqualizerPresetId
import moe.ouom.neriplayer.data.model.settings.playback.PlaybackPreferenceSnapshot
import moe.ouom.neriplayer.data.settings.IsolatedSettingsDataStore
import moe.ouom.neriplayer.data.settings.SettingsKeys
import moe.ouom.neriplayer.data.testing.InMemorySharedPreferencesRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPreferenceSnapshotCacheTest {
    private val preferences = InMemorySharedPreferencesRegistry()
    private val context = IsolatedSettingsDataStore.context(preferences)

    @Test
    fun `suspending read loads settings and refreshes the startup cache`() = runTest {
        IsolatedSettingsDataStore.withEmptySettings(
            context,
            seed = {
                it[SettingsKeys.AUDIO_QUALITY] = "lossless"
                it[SettingsKeys.PLAYBACK_SPEED] = 1.5f
                it[SettingsKeys.KEEP_LAST_PLAYBACK_PROGRESS] = false
            }
        ) {
            val snapshot = readPlaybackPreferenceSnapshot(context)

            assertEquals("lossless", snapshot.audioQuality)
            assertEquals(1.5f, snapshot.playbackSpeed, 0.0001f)
            assertFalse(snapshot.keepLastPlaybackProgress)
            assertEquals(snapshot, readPlaybackPreferenceSnapshotCached(context))
        }
    }

    @Test
    fun `synchronous read serves a ready cache without warming`() {
        persistPlaybackPreferenceSnapshot(
            context,
            PlaybackPreferenceSnapshot(audioQuality = "hires", allowMixedPlayback = true)
        )
        val commits = preferences[PLAYBACK_CACHE].commitCount

        val snapshot = readPlaybackPreferenceSnapshotSync(context)

        assertEquals("hires", snapshot.audioQuality)
        assertTrue(snapshot.allowMixedPlayback)
        assertEquals(commits, preferences[PLAYBACK_CACHE].commitCount)
    }

    @Test
    fun `synchronous read without a cache returns defaults and warms it from settings`() = runTest {
        IsolatedSettingsDataStore.withEmptySettings(
            context,
            seed = {
                it[SettingsKeys.AUDIO_QUALITY] = "standard"
                it[SettingsKeys.PREEMPT_AUDIO_FOCUS] = true
            }
        ) {
            val warmed = CountDownLatch(1)
            preferences[PLAYBACK_CACHE].registerOnSharedPreferenceChangeListener { _, key ->
                if (key == "ready") warmed.countDown()
            }

            assertEquals(PlaybackPreferenceSnapshot(), readPlaybackPreferenceSnapshotSync(context))

            assertTrue(warmed.await(30, TimeUnit.SECONDS))
            val cached = readPlaybackPreferenceSnapshotCached(context)!!
            assertEquals("standard", cached.audioQuality)
            assertTrue(cached.preemptAudioFocus)
        }
    }

    @Test
    fun `cache written by an older schema is ignored`() {
        preferences[PLAYBACK_CACHE].edit()
            .putBoolean("ready", true)
            .putInt("schema_version", 4)
            .putString("audio_quality", "hires")
            .commit()

        assertNull(readPlaybackPreferenceSnapshotCached(context))
    }

    @Test
    fun `cache that never finished writing is ignored`() {
        preferences[PLAYBACK_CACHE].edit()
            .putInt("schema_version", 5)
            .putString("audio_quality", "hires")
            .commit()

        assertNull(readPlaybackPreferenceSnapshotCached(context))
    }

    @Test
    fun `sanitized replaces blank quality and preset values with defaults`() {
        val snapshot = PlaybackPreferenceSnapshot(
            audioQuality = " ",
            youtubeAudioQuality = "",
            biliAudioQuality = "\t",
            playbackEqualizerPreset = "  "
        ).sanitized()

        assertEquals("exhigh", snapshot.audioQuality)
        assertEquals("high", snapshot.youtubeAudioQuality)
        assertEquals("high", snapshot.biliAudioQuality)
        assertEquals(PlaybackEqualizerPresetId.FLAT, snapshot.playbackEqualizerPreset)
    }

    @Test
    fun `sanitized trims explicit quality values`() {
        val snapshot = PlaybackPreferenceSnapshot(
            audioQuality = " lossless ",
            youtubeAudioQuality = " medium",
            biliAudioQuality = "dolby ",
            playbackEqualizerPreset = " ${PlaybackEqualizerPresetId.POP} "
        ).sanitized()

        assertEquals("lossless", snapshot.audioQuality)
        assertEquals("medium", snapshot.youtubeAudioQuality)
        assertEquals("dolby", snapshot.biliAudioQuality)
        assertEquals(PlaybackEqualizerPresetId.POP, snapshot.playbackEqualizerPreset)
    }

    private companion object {
        const val PLAYBACK_CACHE = "playback_snapshot_cache"
    }
}
