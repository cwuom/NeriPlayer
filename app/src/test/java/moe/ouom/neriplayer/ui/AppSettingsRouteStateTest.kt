package moe.ouom.neriplayer.ui

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.data.settings.SettingsRepository
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
    fun internationalizationAndUsbPreferencesReachTheSettingsDomainState() = runBlocking {
        val filesDir = File.createTempFile("neriplayer-settings-route", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(filesDir)
        `when`(context.applicationContext).thenReturn(context)
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
