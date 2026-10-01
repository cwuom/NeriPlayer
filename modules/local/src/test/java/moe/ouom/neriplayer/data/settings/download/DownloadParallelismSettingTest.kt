package moe.ouom.neriplayer.data.settings.download

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.settings.AutoSettingSpecRepository
import moe.ouom.neriplayer.data.settings.AutoSettingsSchema
import moe.ouom.neriplayer.data.settings.autoSettingFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.RETURNS_SELF
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File

class DownloadParallelismSettingTest {
    @Test
    fun hostReceivesNormalizedParallelismAfterThePreferenceIsStored() {
        val filesDir = File.createTempFile("neriplayer-setting-parallelism", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        val context = mock(Context::class.java)
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java, RETURNS_SELF)
        `when`(context.filesDir).thenReturn(filesDir)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.getSharedPreferences("bootstrap_settings_snapshot", Context.MODE_PRIVATE))
            .thenReturn(preferences)
        `when`(preferences.edit()).thenReturn(editor)
        val observedValues = mutableListOf<Pair<Int, Int>>()
        val repository = AutoSettingSpecRepository(context) { publishedValue ->
            val storedValue = runBlocking {
                context.autoSettingFlow(AutoSettingsSchema.download.downloadParallelism).first()
            }
            observedValues += publishedValue to storedValue
        }

        runBlocking {
            repository.setDownloadParallelism(0)
            repository.setDownloadParallelism(Int.MAX_VALUE)
        }

        assertEquals(listOf(1 to 1, 8 to 8), observedValues)
    }
}
