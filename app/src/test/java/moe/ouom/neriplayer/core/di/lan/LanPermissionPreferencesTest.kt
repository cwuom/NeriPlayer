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
        val stored = MutableStateFlow(0)
        `when`(repository.settingFlow(setting)).thenReturn(stored)
        doAnswer { invocation -> stored.value = invocation.getArgument(1); Unit }
            .`when`(repository).setSetting(eq(setting) ?: setting, anyInt())
        val preferences = LanPermissionPreferences(repository)
        assertEquals(LanPermissions(), preferences.load())
        for ((value, bits) in listOf(LanPermissions(true, false, true) to 5,
            LanPermissions(false, true, false) to 2, LanPermissions() to 0)) {
            preferences.save(value)
            assertEquals(bits, stored.value)
            assertEquals(value, LanPermissionPreferences(repository).load())
        }
    }
}
