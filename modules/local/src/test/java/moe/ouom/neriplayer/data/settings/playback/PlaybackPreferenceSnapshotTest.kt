package moe.ouom.neriplayer.data.settings.playback

import moe.ouom.neriplayer.data.model.settings.playback.PlaybackPreferenceSnapshot

import moe.ouom.neriplayer.data.settings.SettingsKeys
import moe.ouom.neriplayer.data.settings.storage.CacheSizePolicy
import androidx.datastore.preferences.core.preferencesOf
import moe.ouom.neriplayer.data.model.playback.MAX_PLAYBACK_LOUDNESS_GAIN_MB
import moe.ouom.neriplayer.data.model.playback.MAX_PLAYBACK_VOLUME_BALANCE
import moe.ouom.neriplayer.data.model.playback.MIN_PLAYBACK_PITCH
import moe.ouom.neriplayer.data.model.playback.MIN_PLAYBACK_SPEED
import moe.ouom.neriplayer.data.model.playback.PlaybackEqualizerPresetId
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsPresetIds
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsSettings
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsSettingsCodec
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
    fun `stored audio effects json takes precedence over legacy equalizer keys`() {
        val stored = AudioEffectsSettingsCodec.encode(AudioEffectsSettings(powerMode = "eco"))
        val preferences = preferencesOf(
            SettingsKeys.AUDIO_EFFECTS_SETTINGS to stored,
            SettingsKeys.PLAYBACK_EQUALIZER_ENABLED to true,
            SettingsKeys.PLAYBACK_EQUALIZER_PRESET to PlaybackEqualizerPresetId.ROCK
        )

        assertEquals(stored, preferences.toPlaybackPreferenceSnapshot().audioEffectsSettingsJson)
        val effects = preferences.toAudioEffectsSettings()
        assertFalse(effects.main.enabled)
        assertEquals("eco", effects.powerMode)
        assertTrue(preferencesOf(SettingsKeys.PLAYBACK_EQUALIZER_ENABLED to true).toAudioEffectsSettings().main.enabled)
    }

    @Test
    fun `preferences restore sleep timer finish current setting`() {
        val snapshot = preferencesOf(
            SettingsKeys.PLAYBACK_SLEEP_TIMER_FINISH_CURRENT_ON_EXPIRY to true
        ).toPlaybackPreferenceSnapshot()

        assertTrue(snapshot.sleepTimerFinishCurrentOnExpiry)
    }

    @Test
    fun `empty preferences enable playback fade by default`() {
        val snapshot = preferencesOf().toPlaybackPreferenceSnapshot()

        assertTrue(snapshot.playbackFadeIn)
        assertTrue(snapshot.playbackCrossfadeNext)
    }

    @Test
    fun `explicitly disabled playback fades remain disabled`() {
        val snapshot = preferencesOf(
            SettingsKeys.PLAYBACK_FADE_IN to false,
            SettingsKeys.PLAYBACK_CROSSFADE_NEXT to false
        ).toPlaybackPreferenceSnapshot()

        assertFalse(snapshot.playbackFadeIn)
        assertFalse(snapshot.playbackCrossfadeNext)
    }

    @Test
    fun `preferences restore long form playback progress setting`() {
        val snapshot = preferencesOf(
            SettingsKeys.REMEMBER_LONG_FORM_PLAYBACK_PROGRESS to false
        ).toPlaybackPreferenceSnapshot()

        assertFalse(snapshot.rememberLongFormPlaybackProgress)
    }

    @Test
    fun `preferences preserve explicit playback source fallback choices`() {
        val snapshot = preferencesOf(
            SettingsKeys.NETEASE_AUTO_SOURCE_SWITCH to true,
            SettingsKeys.NETEASE_LOCAL_SOURCE_FALLBACK to true
        ).toPlaybackPreferenceSnapshot()

        assertTrue(snapshot.neteaseAutoSourceSwitch)
        assertTrue(snapshot.neteaseLocalSourceFallback)
    }

    @Test
    fun `preferences preserve unlimited cache setting`() {
        val snapshot = preferencesOf(
            SettingsKeys.MAX_CACHE_SIZE_BYTES to CacheSizePolicy.UNLIMITED_CACHE_SIZE_BYTES
        ).toPlaybackPreferenceSnapshot()

        assertEquals(CacheSizePolicy.UNLIMITED_CACHE_SIZE_BYTES, snapshot.maxCacheSizeBytes)
    }

    @Test
    fun `preferences preserve external lyric offsets and default them to zero`() {
        val defaults = preferencesOf().toPlaybackPreferenceSnapshot()
        assertEquals(0L, defaults.kugouLyricDefaultOffsetMs)
        assertEquals(0L, defaults.lrclibLyricDefaultOffsetMs)
        assertEquals(0L, defaults.amllTtmlLyricDefaultOffsetMs)

        val snapshot = preferencesOf(
            SettingsKeys.KUGOU_LYRIC_DEFAULT_OFFSET_MS to 150L,
            SettingsKeys.LRCLIB_LYRIC_DEFAULT_OFFSET_MS to -200L,
            SettingsKeys.AMLL_TTML_LYRIC_DEFAULT_OFFSET_MS to 300L
        ).toPlaybackPreferenceSnapshot()
        assertEquals(150L, snapshot.kugouLyricDefaultOffsetMs)
        assertEquals(-200L, snapshot.lrclibLyricDefaultOffsetMs)
        assertEquals(300L, snapshot.amllTtmlLyricDefaultOffsetMs)
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
    fun `legacy equalizer settings migrate into audio effects while playback parameters stay`() {
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
        assertEquals(-0.35f, config.volumeBalance, 0.0001f)
        assertTrue(config.volumeNormalizationEnabled)

        val effects = snapshot.toAudioEffectsSettings()
        assertTrue(effects.main.enabled)
        assertEquals(AudioEffectsPresetIds.CUSTOM, effects.main.presetId)
        assertEquals(-1f, effects.main.sound.equalizerBandsDb.first(), 0.01f)
        assertEquals(5f, effects.main.sound.outputGainDb, 0.001f)

        val stored = snapshot.copy(audioEffectsSettingsJson = AudioEffectsSettingsCodec.encode(AudioEffectsSettings()))
        assertFalse(stored.toAudioEffectsSettings().main.enabled)
    }

    @Test
    fun `defaults keep playback progress and disable mixed audio`() {
        val snapshot = PlaybackPreferenceSnapshot()

        assertTrue(snapshot.keepLastPlaybackProgress)
        assertTrue(snapshot.rememberLongFormPlaybackProgress)
        assertTrue(snapshot.keepPlaybackModeState)
        assertFalse(snapshot.neteaseAutoSourceSwitch)
        assertFalse(snapshot.neteaseLocalSourceFallback)
        assertFalse(snapshot.allowMixedPlayback)
        assertTrue(snapshot.playbackFadeIn)
        assertTrue(snapshot.playbackCrossfadeNext)
        assertFalse(snapshot.sleepTimerFinishCurrentOnExpiry)
        assertFalse(snapshot.playbackVolumeNormalizationEnabled)
        assertFalse(snapshot.playbackHighResolutionOutputEnabled)
        assertFalse(snapshot.lyriconEnabled)
        assertTrue(snapshot.amllLyricsEnabled)
        assertEquals(1024L * 1024 * 1024, snapshot.maxCacheSizeBytes)
    }
}
