package moe.ouom.neriplayer.data.settings

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File

class SettingsRepositoryTest {
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
}
