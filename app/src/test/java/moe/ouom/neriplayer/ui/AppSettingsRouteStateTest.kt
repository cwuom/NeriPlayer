package moe.ouom.neriplayer.ui

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.model.settings.appearance.AdvancedBlurQuality
import moe.ouom.neriplayer.data.model.settings.appearance.ThemePreferenceSnapshot
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricFontScales
import moe.ouom.neriplayer.data.model.settings.playback.PlaybackPreferenceSnapshot
import moe.ouom.neriplayer.ui.settings.owner.AppUsbExclusiveSettingsActions
import moe.ouom.neriplayer.ui.settings.route.AppAppearanceSettingsActions
import moe.ouom.neriplayer.ui.settings.route.appSettingsRouteStateFlow
import moe.ouom.neriplayer.ui.settings.route.initialAppSettingsRouteState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString
import org.mockito.Mockito.RETURNS_SELF
import java.io.File

class AppSettingsRouteStateTest {
    @Test
    fun initialRouteStateKeepsEveryBootstrapPreferenceSourceIndependent() {
        val theme = ThemePreferenceSnapshot()
        val playback = PlaybackPreferenceSnapshot()
        val fontScales = LyricFontScales(1.25f, 1.15f, 1.3f, 1.2f)
        val initial = initialAppSettingsRouteState(theme, playback, AdvancedBlurQuality.Default, fontScales)
        val changedTheme = initialAppSettingsRouteState(
            theme.copy(dynamicColor = false), playback, AdvancedBlurQuality.Default, fontScales
        )
        val changedPlayback = initialAppSettingsRouteState(
            theme, playback.copy(cloudMusicLyricDefaultOffsetMs = 240L),
            AdvancedBlurQuality.Default, fontScales
        )
        val changedBlurQuality = initialAppSettingsRouteState(
            theme, playback, AdvancedBlurQuality.High, fontScales
        )
        val changedFonts = initialAppSettingsRouteState(
            theme, playback, AdvancedBlurQuality.Default, fontScales.copy(coverTranslation = 0.9f)
        )

        assertEquals(initial.copy(appearance = initial.appearance.copy(
            theme = initial.appearance.theme.copy(dynamicColorEnabled = false)
        )), changedTheme)
        assertEquals(initial.copy(lyrics = initial.lyrics.copy(
            lyricOffsets = initial.lyrics.lyricOffsets.copy(cloudMusicLyricDefaultOffsetMs = 240L)
        )), changedPlayback)
        assertEquals(initial.copy(appearance = initial.appearance.copy(
            visualBlur = initial.appearance.visualBlur.copy(advancedBlurQuality = AdvancedBlurQuality.High)
        )), changedBlurQuality)
        assertEquals(initial.copy(lyrics = initial.lyrics.copy(
            lyricPresentation = initial.lyrics.lyricPresentation.copy(
                lyricFontScales = fontScales.copy(coverTranslation = 0.9f)
            )
        )), changedFonts)
    }

    @Test
    fun internationalizationAndUsbPreferencesReachTheSettingsDomainState() = runBlocking {
        val filesDir = File.createTempFile("neriplayer-settings-route", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(filesDir)
        `when`(context.applicationContext).thenReturn(context)
        val configuration = mock(Configuration::class.java).apply {
            smallestScreenWidthDp = 360
        }
        val resources = mock(Resources::class.java)
        `when`(resources.configuration).thenReturn(configuration)
        `when`(context.resources).thenReturn(resources)
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java, RETURNS_SELF)
        `when`(preferences.edit()).thenReturn(editor)
        `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(preferences)
        val repository = SettingsRepository(context)

        val initial = withTimeout(10_000) { appSettingsRouteStateFlow(repository).first() }
        val nextInternationalization = !initial.other.internationalizationEnabled
        val nextBitPerfect = !initial.playback.usbExclusivePreferences.bitPerfect
        AppAppearanceSettingsActions(repository, this) {}
            .onInternationalizationEnabledChange(nextInternationalization)
        AppUsbExclusiveSettingsActions(repository, this).onBitPerfectChange(nextBitPerfect)

        val updated = withTimeout(10_000) {
            appSettingsRouteStateFlow(repository).first { state ->
                state.other.internationalizationEnabled == nextInternationalization &&
                    state.playback.usbExclusivePreferences.bitPerfect == nextBitPerfect
            }
        }
        assertEquals(nextInternationalization, updated.other.internationalizationEnabled)
        assertEquals(nextBitPerfect, updated.playback.usbExclusivePreferences.bitPerfect)
    }
}
