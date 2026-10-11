package moe.ouom.neriplayer.data.settings

import moe.ouom.neriplayer.data.model.settings.lyrics.FloatingLyricsPreferences
import android.content.Context
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.settings.appearance.ThemePreferenceSnapshot
import moe.ouom.neriplayer.data.model.settings.playback.PlaybackPreferenceSnapshot
import moe.ouom.neriplayer.data.settings.appearance.ThemeDefaults
import moe.ouom.neriplayer.data.settings.appearance.readThemePreferenceSnapshotSync
import moe.ouom.neriplayer.data.settings.bootstrap.readBootstrapSettingsSnapshotSync
import moe.ouom.neriplayer.data.settings.playback.readPlaybackPreferenceSnapshotCached
import moe.ouom.neriplayer.data.testing.InMemorySharedPreferencesRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRepositorySnapshotWriteThroughTest {

    @Test
    fun `floating lyric layout choices survive repository recreation and enable toggles`() = withIsolatedSettings { context, repository, _ ->
        val expected = FloatingLyricsPreferences(
            enabled = true, sentenceCount = 3, longLineMode = "wrap", maxWidthDp = 300f,
            landscapeMaxWidthDp = 960f
        ).normalized()
        repository.setFloatingLyricsPreferences(expected)
        assertEquals(expected, SettingsRepository(context).floatingLyricsPreferencesFlow.first())
        repository.setFloatingLyricsEnabled(false)
        assertEquals(expected.copy(enabled = false), repository.floatingLyricsPreferencesFlow.first())
    }

    @Test
    fun `theme toggles persist to settings and the startup theme cache`() = withIsolatedSettings { context, repository, _ ->
        repository.setDynamicColor(false)
        repository.setForceDark(true)
        repository.setFollowSystemDark(false)

        assertFalse(repository.dynamicColorFlow.first())
        assertTrue(repository.forceDarkFlow.first())
        assertFalse(repository.followSystemDarkFlow.first())
        assertEquals(
            ThemePreferenceSnapshot(dynamicColor = false, forceDark = true, followSystemDark = false),
            readThemePreferenceSnapshotSync(context)
        )
    }

    @Test
    fun `theme mode updates dark flags and keeps the cached dynamic color choice`() =
        withIsolatedSettings { context, repository, preferences ->
            repository.setThemeMode(followSystemDark = false, forceDark = true)

            assertFalse(repository.followSystemDarkFlow.first())
            assertTrue(repository.forceDarkFlow.first())
            assertEquals(
                ThemePreferenceSnapshot(dynamicColor = true, forceDark = true, followSystemDark = false),
                readThemePreferenceSnapshotSync(context)
            )

            repository.setDynamicColor(false)
            repository.setThemeMode(followSystemDark = true, forceDark = false)

            assertEquals(
                ThemePreferenceSnapshot(dynamicColor = false, forceDark = false, followSystemDark = true),
                readThemePreferenceSnapshotSync(context)
            )
            assertEquals(false, preferences["theme_snapshot_cache"].snapshot()["dynamic_color"])
        }

    @Test
    fun `custom palette colors are normalized deduplicated and removable`() = withIsolatedSettings { context, repository, _ ->
        assertEquals(ThemeDefaults.PRESET_COLORS, repository.themeColorPaletteFlow.first())

        repository.addThemePaletteColor(" #12ab34 ")
        repository.addThemePaletteColor("12AB34")
        repository.addThemePaletteColor("not-a-color")
        repository.addThemePaletteColor("#0061a4")

        assertEquals(ThemeDefaults.PRESET_COLORS + "12AB34", repository.themeColorPaletteFlow.first())

        repository.removeThemePaletteColor("0061A4")
        repository.removeThemePaletteColor("??")
        assertEquals(ThemeDefaults.PRESET_COLORS + "12AB34", repository.themeColorPaletteFlow.first())

        repository.removeThemePaletteColor("#12ab34")

        assertEquals(ThemeDefaults.PRESET_COLORS, repository.themeColorPaletteFlow.first())
        assertNull(context.dataStore.data.first()[SettingsKeys.THEME_COLOR_PALETTE])
    }

    @Test
    fun `corrupt stored palette falls back to presets before adding a color`() = withIsolatedSettings { context, repository, _ ->
        context.dataStore.edit { it[SettingsKeys.THEME_COLOR_PALETTE] = "nope, ,#zzzzzz" }

        assertEquals(ThemeDefaults.PRESET_COLORS, repository.themeColorPaletteFlow.first())

        repository.addThemePaletteColor("abcdef")

        assertEquals(ThemeDefaults.PRESET_COLORS + "ABCDEF", repository.themeColorPaletteFlow.first())
        assertEquals(
            (ThemeDefaults.PRESET_COLORS + "ABCDEF").joinToString(","),
            context.dataStore.data.first()[SettingsKeys.THEME_COLOR_PALETTE]
        )
    }

    @Test
    fun `playback toggles write through to the playback snapshot cache`() = withIsolatedSettings { context, repository, _ ->
        val toggles = listOf(
            PlaybackToggle(
                "mobileDataFollowDefaultAudioQuality",
                repository::setMobileDataFollowDefaultAudioQuality,
                repository.mobileDataFollowDefaultAudioQualityFlow,
                PlaybackPreferenceSnapshot::mobileDataFollowDefaultAudioQuality
            ),
            PlaybackToggle(
                "lyricon",
                repository::setLyriconEnabled,
                repository.lyriconEnabledFlow,
                PlaybackPreferenceSnapshot::lyriconEnabled
            ),
            PlaybackToggle(
                "fadeIn",
                repository::setPlaybackFadeIn,
                repository.playbackFadeInFlow,
                PlaybackPreferenceSnapshot::playbackFadeIn
            ),
            PlaybackToggle(
                "crossfadeNext",
                repository::setPlaybackCrossfadeNext,
                repository.playbackCrossfadeNextFlow,
                PlaybackPreferenceSnapshot::playbackCrossfadeNext
            ),
            PlaybackToggle(
                "sleepTimerFinishCurrent",
                repository::setSleepTimerFinishCurrentOnExpiry,
                repository.sleepTimerFinishCurrentOnExpiryFlow,
                PlaybackPreferenceSnapshot::sleepTimerFinishCurrentOnExpiry
            ),
            PlaybackToggle(
                "equalizer",
                repository::setPlaybackEqualizerEnabled,
                repository.playbackEqualizerEnabledFlow,
                PlaybackPreferenceSnapshot::playbackEqualizerEnabled
            ),
            PlaybackToggle(
                "volumeNormalization",
                repository::setPlaybackVolumeNormalizationEnabled,
                repository.playbackVolumeNormalizationEnabledFlow,
                PlaybackPreferenceSnapshot::playbackVolumeNormalizationEnabled
            ),
            PlaybackToggle(
                "highResolutionOutput",
                repository::setPlaybackHighResolutionOutputEnabled,
                repository.playbackHighResolutionOutputEnabledFlow,
                PlaybackPreferenceSnapshot::playbackHighResolutionOutputEnabled
            ),
            PlaybackToggle(
                "keepLastProgress",
                repository::setKeepLastPlaybackProgress,
                repository.keepLastPlaybackProgressFlow,
                PlaybackPreferenceSnapshot::keepLastPlaybackProgress
            ),
            PlaybackToggle(
                "rememberLongFormProgress",
                repository::setRememberLongFormPlaybackProgress,
                repository.rememberLongFormPlaybackProgressFlow,
                PlaybackPreferenceSnapshot::rememberLongFormPlaybackProgress
            ),
            PlaybackToggle(
                "keepModeState",
                repository::setKeepPlaybackModeState,
                repository.keepPlaybackModeStateFlow,
                PlaybackPreferenceSnapshot::keepPlaybackModeState
            ),
            PlaybackToggle(
                "neteaseAutoSourceSwitch",
                repository::setNeteaseAutoSourceSwitch,
                repository.neteaseAutoSourceSwitchFlow,
                PlaybackPreferenceSnapshot::neteaseAutoSourceSwitch
            ),
            PlaybackToggle(
                "neteaseLocalSourceFallback",
                repository::setNeteaseLocalSourceFallback,
                repository.neteaseLocalSourceFallbackFlow,
                PlaybackPreferenceSnapshot::neteaseLocalSourceFallback
            ),
            PlaybackToggle(
                "stopOnBluetoothDisconnect",
                repository::setStopOnBluetoothDisconnect,
                repository.stopOnBluetoothDisconnectFlow,
                PlaybackPreferenceSnapshot::stopOnBluetoothDisconnect
            ),
            PlaybackToggle(
                "usbExclusive",
                repository::setUsbExclusivePlayback,
                repository.usbExclusivePlaybackFlow,
                PlaybackPreferenceSnapshot::usbExclusivePlayback
            ),
            PlaybackToggle(
                "allowMixedPlayback",
                repository::setAllowMixedPlayback,
                repository.allowMixedPlaybackFlow,
                PlaybackPreferenceSnapshot::allowMixedPlayback
            ),
            PlaybackToggle(
                "preemptAudioFocus",
                repository::setPreemptAudioFocus,
                repository.preemptAudioFocusFlow,
                PlaybackPreferenceSnapshot::preemptAudioFocus
            )
        )

        toggles.forEach { toggle ->
            val flipped = !toggle.flow.first()

            toggle.set(flipped)

            assertEquals(toggle.name, flipped, toggle.flow.first())
            val cached = readPlaybackPreferenceSnapshotCached(context)
            assertNotNull(toggle.name, cached)
            assertEquals(toggle.name, flipped, toggle.cached(cached!!))
        }
    }

    @Test
    fun `combined netease fallback switch updates both source fallbacks`() = withIsolatedSettings { context, repository, _ ->
        repository.setNeteasePlaybackSourceFallback(true)

        assertTrue(repository.neteaseAutoSourceSwitchFlow.first())
        assertTrue(repository.neteaseLocalSourceFallbackFlow.first())
        val enabled = readPlaybackPreferenceSnapshotCached(context)!!
        assertTrue(enabled.neteaseAutoSourceSwitch)
        assertTrue(enabled.neteaseLocalSourceFallback)

        repository.setNeteasePlaybackSourceFallback(false)

        assertFalse(repository.neteaseAutoSourceSwitchFlow.first())
        assertFalse(repository.neteaseLocalSourceFallbackFlow.first())
        val disabled = readPlaybackPreferenceSnapshotCached(context)!!
        assertFalse(disabled.neteaseAutoSourceSwitch)
        assertFalse(disabled.neteaseLocalSourceFallback)
    }

    @Test
    fun `custom equalizer levels are stored and cleared with the snapshot`() = withIsolatedSettings { context, repository, _ ->
        repository.setPlaybackEqualizerCustomBandLevels(listOf(120, -60, 0))

        assertEquals(listOf(120, -60, 0), repository.playbackEqualizerCustomBandLevelsFlow.first())
        assertEquals(
            "120,-60,0",
            context.dataStore.data.first()[SettingsKeys.PLAYBACK_EQUALIZER_CUSTOM_BAND_LEVELS]
        )
        assertEquals(
            listOf(120, -60, 0),
            readPlaybackPreferenceSnapshotCached(context)!!.playbackEqualizerCustomBandLevels
        )

        repository.setPlaybackEqualizerCustomBandLevels(emptyList())

        assertEquals(emptyList<Int>(), repository.playbackEqualizerCustomBandLevelsFlow.first())
        assertNull(context.dataStore.data.first()[SettingsKeys.PLAYBACK_EQUALIZER_CUSTOM_BAND_LEVELS])
        assertEquals(
            emptyList<Int>(),
            readPlaybackPreferenceSnapshotCached(context)!!.playbackEqualizerCustomBandLevels
        )
    }

    @Test
    fun `bypass proxy writes the bootstrap snapshot used before DataStore loads`() =
        withIsolatedSettings { context, repository, _ ->
            assertTrue(repository.bypassProxyFlow.first())

            repository.setBypassProxy(false)

            assertFalse(repository.bypassProxyFlow.first())
            assertFalse(readBootstrapSettingsSnapshotSync(context).bypassProxy)

            repository.setBypassProxy(true)

            assertTrue(readBootstrapSettingsSnapshotSync(context).bypassProxy)
        }

    @Test
    fun `download directory keeps label only for a configured directory`() = withIsolatedSettings { context, repository, _ ->
        val treeUri = "content://com.android.externalstorage.documents/tree/primary%3AMusic"

        repository.setDownloadDirectory("$treeUri/", "Music")

        assertEquals(treeUri, repository.downloadDirectoryUriFlow.first())
        assertEquals("Music", repository.downloadDirectoryLabelFlow.first())
        readBootstrapSettingsSnapshotSync(context).let { snapshot ->
            assertEquals(treeUri, snapshot.downloadDirectoryUri)
            assertEquals("Music", snapshot.downloadDirectoryLabel)
        }

        repository.setDownloadDirectory(treeUri, "   ")

        assertEquals(treeUri, repository.downloadDirectoryUriFlow.first())
        assertNull(repository.downloadDirectoryLabelFlow.first())
        assertNull(readBootstrapSettingsSnapshotSync(context).downloadDirectoryLabel)

        repository.setDownloadDirectory(treeUri, "Music")
        repository.setDownloadDirectory(null, null)

        assertNull(repository.downloadDirectoryUriFlow.first())
        assertNull(repository.downloadDirectoryLabelFlow.first())
        readBootstrapSettingsSnapshotSync(context).let { snapshot ->
            assertNull(snapshot.downloadDirectoryUri)
            assertNull(snapshot.downloadDirectoryLabel)
        }
    }

    private class PlaybackToggle(
        val name: String,
        val set: suspend (Boolean) -> Unit,
        val flow: Flow<Boolean>,
        val cached: (PlaybackPreferenceSnapshot) -> Boolean
    )

    private fun withIsolatedSettings(
        block: suspend (Context, SettingsRepository, InMemorySharedPreferencesRegistry) -> Unit
    ) = runTest {
        val preferences = InMemorySharedPreferencesRegistry()
        val context = IsolatedSettingsDataStore.context(preferences)
        IsolatedSettingsDataStore.withEmptySettings(context) {
            block(context, SettingsRepository(context), preferences)
        }
    }
}
