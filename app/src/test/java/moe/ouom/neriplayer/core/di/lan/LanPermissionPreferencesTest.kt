package moe.ouom.neriplayer.core.di.lan

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.lan.LanPermissions
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.settings.AutoSettingsSchema
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doAnswer
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq

class LanPermissionPreferencesTest {
    @Test fun oneSettingPreservesIndependentGrantsAcrossPreferenceOwners() = runBlocking {
        val repository = mock(SettingsRepository::class.java)
        val setting = AutoSettingsSchema.playback.lanHostPermissions
        val stored = MutableStateFlow(setting.defaultValue)
        `when`(repository.settingFlow(setting)).thenReturn(stored)
        doAnswer { invocation -> stored.value = invocation.getArgument(1); Unit }
            .`when`(repository).setSetting(eq(setting) ?: setting, anyInt())
        val preferences = LanPermissionPreferences(repository)
        assertEquals(7, setting.defaultValue)
        assertEquals(LanPermissions(), preferences.load())
        for (oldBits in 0..15) {
            stored.value = oldBits
            val progress = oldBits and 1 != 0 && oldBits and 4 != 0
            val expected = LanPermissions(playPause = oldBits and 2 != 0, seek = progress, volume = oldBits and 8 != 0)
            assertEquals("legacy settings $oldBits", expected, preferences.load())
            preferences.save(expected)
            assertEquals(if (progress) oldBits else oldBits and 10, stored.value)
            assertEquals(expected, LanPermissionPreferences(repository).load())
        }
    }
}
