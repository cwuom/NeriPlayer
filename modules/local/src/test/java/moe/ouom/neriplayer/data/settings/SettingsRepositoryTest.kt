package moe.ouom.neriplayer.data.settings

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.settings.lyrics.BluetoothMetadataMode
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File

class SettingsRepositoryTest {
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
