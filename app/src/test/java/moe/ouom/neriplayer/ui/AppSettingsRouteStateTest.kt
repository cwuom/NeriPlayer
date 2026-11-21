package moe.ouom.neriplayer.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.settings.dataStore
import moe.ouom.neriplayer.data.model.settings.appearance.AdvancedBlurQuality
import moe.ouom.neriplayer.data.model.settings.appearance.ThemePreferenceSnapshot
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricFontScales
import moe.ouom.neriplayer.data.model.settings.playback.PlaybackPreferenceSnapshot
import moe.ouom.neriplayer.ui.settings.owner.AppUsbExclusiveSettingsActions
import moe.ouom.neriplayer.ui.settings.route.AppAppearanceSettingsActions
import moe.ouom.neriplayer.ui.settings.route.AppHomeSettingsActions
import moe.ouom.neriplayer.ui.settings.route.appSettingsRouteStateFlow
import moe.ouom.neriplayer.ui.settings.route.initialAppSettingsRouteState
import org.junit.Assert.assertEquals
import org.junit.Test
import moe.ouom.neriplayer.data.model.settings.home.HomeMajorSectionIds
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
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
        val context = ApplicationProvider.getApplicationContext<Application>()
        val repository = SettingsRepository(context)
        val original = context.dataStore.data.first()
        try {
            withTimeout(10_000) {
                val initial = appSettingsRouteStateFlow(repository).first()
                val international = !initial.other.internationalizationEnabled
                val bitPerfect = !initial.playback.usbExclusivePreferences.bitPerfect
                val showBilibili = !initial.other.homeCards.showHomeBilibiliCard
                coroutineScope {
                    AppAppearanceSettingsActions(repository, this) {}
                        .onInternationalizationEnabledChange(international)
                    AppUsbExclusiveSettingsActions(repository, this).onBitPerfectChange(bitPerfect)
                    AppHomeSettingsActions(repository, this).setMajorVisible(HomeMajorSectionIds.BILIBILI, showBilibili)
                }

                val updated = appSettingsRouteStateFlow(repository).first()
                assertEquals(international, updated.other.internationalizationEnabled)
                assertEquals(bitPerfect, updated.playback.usbExclusivePreferences.bitPerfect)
                assertEquals(showBilibili, updated.other.homeCards.showHomeBilibiliCard)
            }
        } finally {
            context.dataStore.updateData { original }
        }
    }
}
