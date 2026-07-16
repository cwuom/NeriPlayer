package moe.ouom.neriplayer.data.settings

import androidx.datastore.preferences.core.preferencesOf
import moe.ouom.neriplayer.core.player.model.MAX_PLAYBACK_LOUDNESS_GAIN_MB
import moe.ouom.neriplayer.core.player.model.MAX_PLAYBACK_VOLUME_BALANCE
import moe.ouom.neriplayer.core.player.model.MIN_PLAYBACK_PITCH
import moe.ouom.neriplayer.core.player.model.MIN_PLAYBACK_SPEED
import moe.ouom.neriplayer.core.player.model.PlaybackEqualizerPresetId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPreferenceSnapshotTest {

    @Test
    fun `preferences restore high resolution output setting`() {
        val snapshot = preferencesOf(
            SettingsKeys.PLAYBACK_HIGH_RESOLUTION_OUTPUT_ENABLED to true,
            SettingsKeys.AMLL_LYRICS_ENABLED to true
        ).toPlaybackPreferenceSnapshot()

        assertTrue(snapshot.playbackHighResolutionOutputEnabled)
        assertTrue(snapshot.amllLyricsEnabled)
    }

    @Test
    fun `preferences restore sleep timer finish current setting`() {
        val snapshot = preferencesOf(
            SettingsKeys.PLAYBACK_SLEEP_TIMER_FINISH_CURRENT_ON_EXPIRY to true
        ).toPlaybackPreferenceSnapshot()

        assertTrue(snapshot.sleepTimerFinishCurrentOnExpiry)
    }

    @Test
    fun `sanitized normalizes playback runtime values`() {
        val snapshot = PlaybackPreferenceSnapshot(
            playbackFadeInDurationMs = -100L,
            playbackFadeOutDurationMs = -1L,
            playbackCrossfadeInDurationMs = -2L,
            playbackCrossfadeOutDurationMs = -3L,
            playbackSpeed = 0.1f,
            playbackPitch = 0.1f,
            playbackLoudnessGainMb = 9000,
            playbackVolumeBalance = 2f,
            maxCacheSizeBytes = -1024L
        ).sanitized()

        assertEquals(0L, snapshot.playbackFadeInDurationMs)
        assertEquals(0L, snapshot.playbackFadeOutDurationMs)
        assertEquals(0L, snapshot.playbackCrossfadeInDurationMs)
        assertEquals(0L, snapshot.playbackCrossfadeOutDurationMs)
        assertEquals(MIN_PLAYBACK_SPEED, snapshot.playbackSpeed, 0.0001f)
        assertEquals(MIN_PLAYBACK_PITCH, snapshot.playbackPitch, 0.0001f)
        assertEquals(MAX_PLAYBACK_LOUDNESS_GAIN_MB, snapshot.playbackLoudnessGainMb)
        assertEquals(MAX_PLAYBACK_VOLUME_BALANCE, snapshot.playbackVolumeBalance, 0.0001f)
        assertEquals(0L, snapshot.maxCacheSizeBytes)
    }

    @Test
    fun `toPlaybackSoundConfig preserves equalizer settings`() {
        val snapshot = PlaybackPreferenceSnapshot(
            playbackSpeed = 1.25f,
            playbackPitch = 0.95f,
            playbackLoudnessGainMb = 500,
            playbackVolumeBalance = -0.35f,
            playbackVolumeNormalizationEnabled = true,
            playbackEqualizerEnabled = true,
            playbackEqualizerPreset = PlaybackEqualizerPresetId.POP,
            playbackEqualizerCustomBandLevels = listOf(100, -50, 25)
        )

        val config = snapshot.toPlaybackSoundConfig()

        assertEquals(1.25f, config.speed, 0.0001f)
        assertEquals(0.95f, config.pitch, 0.0001f)
        assertEquals(500, config.loudnessGainMb)
        assertEquals(-0.35f, config.volumeBalance, 0.0001f)
        assertTrue(config.volumeNormalizationEnabled)
        assertTrue(config.equalizerEnabled)
        assertEquals(PlaybackEqualizerPresetId.POP, config.presetId)
        assertEquals(listOf(100, -50, 25), config.customBandLevelsMb)
    }

    @Test
    fun `defaults keep playback progress and disable mixed audio`() {
        val snapshot = PlaybackPreferenceSnapshot()

        assertTrue(snapshot.keepLastPlaybackProgress)
        assertTrue(snapshot.keepPlaybackModeState)
        assertFalse(snapshot.allowMixedPlayback)
        assertFalse(snapshot.playbackFadeIn)
        assertFalse(snapshot.sleepTimerFinishCurrentOnExpiry)
        assertFalse(snapshot.playbackVolumeNormalizationEnabled)
        assertFalse(snapshot.playbackHighResolutionOutputEnabled)
        assertFalse(snapshot.lyriconEnabled)
        assertTrue(snapshot.amllLyricsEnabled)
        assertEquals(1024L * 1024 * 1024, snapshot.maxCacheSizeBytes)
    }
}
