package moe.ouom.neriplayer.data.settings

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsRepository
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsSections
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsSwitchBindings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File

class AutoSettingsSwitchBindingsTest {
    @Test
    fun switchBindingsExcludeSettingsThatRequireBusinessSetters() {
        val keyNames = AutoSettingsSwitchBindings.entries.map { it.setting.keyName }.toSet()

        assertTrue("haptic_feedback_enabled" in keyNames)
        assertTrue("bili_sponsor_block_enabled" in keyNames)
        assertFalse("dynamic_color" in keyNames)
        assertFalse("advanced_blur_enabled" in keyNames)
        assertFalse("dynamic_island_lyrics_enabled" in keyNames)
        assertFalse("usb_exclusive_playback" in keyNames)
    }

    @Test
    fun sectionBindingsKeepSettingsInTheirDeclaredOrder() {
        val bindings = AutoSettingsSwitchBindings.inSection(AutoSettingsSections.general)
        val keyNames = bindings.map { it.setting.keyName }

        assertTrue(keyNames.indexOf("haptic_feedback_enabled") >= 0)
        assertTrue(keyNames.indexOf("always_record_logs_enabled") >= 0)
        assertTrue(
            keyNames.indexOf("haptic_feedback_enabled") <
                keyNames.indexOf("always_record_logs_enabled")
        )
        assertFalse("bili_sponsor_block_enabled" in keyNames)
        assertTrue(AutoSettingsSwitchBindings.inSection("missing").isEmpty())
    }

    @Test
    fun bindingReadsAndWritesTheSamePreferenceAsTheTypedRepository() {
        val filesDir = File.createTempFile("neriplayer-setting-binding", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(filesDir)
        `when`(context.applicationContext).thenReturn(context)
        val repository = AutoSettingsRepository(context)
        val binding = AutoSettingsSwitchBindings.entries.single {
            it.setting.keyName == "external_bluetooth_lyrics_enabled"
        }

        runBlocking {
            assertTrue(binding.defaultValue)
            assertTrue(binding.read(repository).first())

            binding.write(repository, false)
            assertFalse(repository.externalBluetoothLyricsEnabledFlow.first())

            repository.setExternalBluetoothLyricsEnabled(true)
            assertEquals(true, binding.read(repository).first())
        }
    }
}
