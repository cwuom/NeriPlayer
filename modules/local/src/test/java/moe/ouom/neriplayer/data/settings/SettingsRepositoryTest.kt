package moe.ouom.neriplayer.data.settings

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.settings.lyrics.BluetoothMetadataMode
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricFontScaleTarget
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricFontScales
import moe.ouom.neriplayer.data.model.settings.playback.NowPlayingControlPlacement
import moe.ouom.neriplayer.data.model.settings.playback.PlaybackControlLayoutPreferences
import moe.ouom.neriplayer.data.model.settings.playback.PlaybackControlSize
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File

class SettingsRepositoryTest {
    @Test
    fun displayDefaultsAndFlowsUseTheSameDevicePolicy() {
        listOf(599, 600).forEach { width ->
            val expectedPlacement = if (width >= 600) {
                NowPlayingControlPlacement.BOTTOM_WITH_PROGRESS
            } else {
                NowPlayingControlPlacement.LOWER
            }
            val expectedScales = if (width >= 600) {
                LyricFontScales(1.25f, 1.15f, 1.25f, 1.15f)
            } else {
                LyricFontScales(1.0f, 1.0f, 1.0f, 1.0f)
            }

            withEmptyDisplaySettings(width) { context ->
                val repository = SettingsRepository(context)
                assertEquals(expectedPlacement, repository.defaultPlaybackControlLayoutPreferences.nowPlayingPlacement)
                assertEquals(expectedScales, repository.defaultLyricFontScales)
                assertEquals(
                    repository.defaultPlaybackControlLayoutPreferences,
                    repository.playbackControlLayoutPreferencesFlow.first()
                )
                assertEquals(repository.defaultLyricFontScales, repository.lyricFontScalesFlow.first())
            }
        }
    }

    @Test
    fun bluetoothMetadataDefaultsToSongAndLyricsAndPreservesSavedModes() {
        val filesDir = File.createTempFile("neriplayer-settings-bluetooth", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(filesDir)
        `when`(context.applicationContext).thenReturn(context)
        val repository = SettingsRepository(context)
        val generatedRepository = AutoSettingsRepository(context)

        runBlocking {
            assertEquals(BluetoothMetadataMode.SongAndLyrics, repository.bluetoothMetadataModeFlow.first())
            assertEquals("song_and_lyrics", generatedRepository.bluetoothMetadataModeFlow.first())
            repository.setSetting(AutoSettingsSchema.lyrics.bluetoothMetadataMode, "unknown")
            assertEquals(BluetoothMetadataMode.SongAndLyrics, repository.bluetoothMetadataModeFlow.first())

            for (mode in BluetoothMetadataMode.entries) {
                repository.setBluetoothMetadataMode(mode)
                assertEquals(mode, repository.bluetoothMetadataModeFlow.first())
                assertEquals(mode.storageValue, generatedRepository.bluetoothMetadataModeFlow.first())
            }
        }
    }

    @Test
    fun tabletDisplayDefaultsDoNotReplacePersistedChoices() {
        val preferences = PlaybackControlLayoutPreferences(
            nowPlayingPlacement = NowPlayingControlPlacement.LOWER,
            nowPlayingSize = PlaybackControlSize.SMALL,
            lyricsSize = PlaybackControlSize.LARGE
        )

        withEmptyDisplaySettings(smallestScreenWidthDp = 800) { context ->
            val repository = SettingsRepository(context)
            repository.setPlaybackControlLayoutPreferences(preferences)
            repository.setLyricFontScale(LyricFontScaleTarget.COVER_LYRIC, 1.0f)

            val reloaded = SettingsRepository(context)
            assertEquals(preferences, reloaded.playbackControlLayoutPreferencesFlow.first())
            assertEquals(
                LyricFontScales(1.0f, 1.15f, 1.25f, 1.15f),
                reloaded.lyricFontScalesFlow.first()
            )
        }
    }

    @Test
    fun tabletDisplayDefaultsPreserveTheLegacyUniformScale() {
        withEmptyDisplaySettings(smallestScreenWidthDp = 600) { context ->
            val repository = SettingsRepository(context)
            repository.setLyricFontScale(1.0f)
            assertEquals(LyricFontScales(1.0f, 1.0f, 1.0f, 1.0f), repository.lyricFontScalesFlow.first())
            repository.setLyricFontScale(LyricFontScaleTarget.COVER_TRANSLATION, 0.8f)
            assertEquals(LyricFontScales(1.0f, 0.8f, 1.0f, 1.0f), repository.lyricFontScalesFlow.first())
        }
    }

    @Test
    fun playbackFadeSettingsDefaultToEnabledWhenUnset() {
        val filesDir = File.createTempFile("neriplayer-settings-fade", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(filesDir)
        `when`(context.applicationContext).thenReturn(context)
        val repository = SettingsRepository(context)

        runBlocking {
            assertTrue(repository.playbackFadeInFlow.first())
            assertTrue(repository.playbackCrossfadeNextFlow.first())
        }
    }

    @Test
    fun enablingDynamicIslandLyricsTurnsOnBluetoothLyricsAndTranslation() {
        val filesDir = File.createTempFile("neriplayer-settings", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(filesDir)
        `when`(context.applicationContext).thenReturn(context)
        val repository = SettingsRepository(context)

        runBlocking {
            repository.setExternalBluetoothLyricsEnabled(false)
            repository.setExternalBluetoothTranslationEnabled(false)
            repository.setDynamicIslandLyricsEnabled(true)

            assertTrue(repository.externalBluetoothLyricsEnabledFlow.first())
            assertTrue(repository.externalBluetoothTranslationEnabledFlow.first())
            assertTrue(repository.dynamicIslandLyricsEnabledFlow.first())
        }
    }

    private fun withEmptyDisplaySettings(
        smallestScreenWidthDp: Int,
        assertions: suspend (Context) -> Unit
    ) = runBlocking {
        val context = displaySettingsContext(smallestScreenWidthDp)
        // 顶层 DataStore 委托会跨 mock Context 共享实例，测试前清空并在结束时恢复完整偏好
        val saved = context.dataStore.data.first()
        try {
            context.dataStore.updateData { emptyPreferences() }
            assertions(context)
        } finally {
            context.dataStore.updateData { saved }
        }
    }

    private fun displaySettingsContext(smallestScreenWidthDp: Int): Context {
        val filesDir = File.createTempFile("neriplayer-display-settings", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        val configuration = mock(Configuration::class.java).apply {
            this.smallestScreenWidthDp = smallestScreenWidthDp
        }
        val resources = mock(Resources::class.java)
        `when`(resources.configuration).thenReturn(configuration)
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(filesDir)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.resources).thenReturn(resources)
        return context
    }
}
