package moe.ouom.neriplayer.data.settings.download

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.settings.AutoSettingsSchema
import moe.ouom.neriplayer.data.settings.autoSettingFlow
import moe.ouom.neriplayer.data.settings.setAutoSetting
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.RETURNS_SELF
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class DownloadFollowPlaybackAudioQualitySettingTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `changing quality follow mode updates persisted settings and the ready startup mirror`() = runBlocking {
        val filesDir = temporaryFolder.root
        val context = mock(Context::class.java)
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java, RETURNS_SELF)
        `when`(context.filesDir).thenReturn(filesDir)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.getSharedPreferences("bootstrap_settings_snapshot", Context.MODE_PRIVATE))
            .thenReturn(preferences)
        `when`(preferences.getBoolean("ready", false)).thenReturn(true)
        `when`(preferences.edit()).thenReturn(editor)

        context.setAutoSetting(AutoSettingsSchema.download.downloadNeteaseAudioQuality, "lossless")
        context.setAutoSetting(AutoSettingsSchema.download.downloadYouTubeAudioQuality, "very_high")
        context.setAutoSetting(AutoSettingsSchema.download.downloadBiliAudioQuality, "dolby")

        listOf(false, true).forEach { followsPlaybackQuality ->
            setDownloadFollowPlaybackAudioQuality(context, followsPlaybackQuality)

            assertEquals(
                followsPlaybackQuality,
                context.autoSettingFlow(AutoSettingsSchema.download.downloadFollowPlaybackAudioQuality).first()
            )
            verify(editor).putBoolean("download_follow_playback_audio_quality", followsPlaybackQuality)
            assertEquals(
                "lossless",
                context.autoSettingFlow(AutoSettingsSchema.download.downloadNeteaseAudioQuality).first()
            )
            assertEquals(
                "very_high",
                context.autoSettingFlow(AutoSettingsSchema.download.downloadYouTubeAudioQuality).first()
            )
            assertEquals(
                "dolby",
                context.autoSettingFlow(AutoSettingsSchema.download.downloadBiliAudioQuality).first()
            )
        }
    }
}
