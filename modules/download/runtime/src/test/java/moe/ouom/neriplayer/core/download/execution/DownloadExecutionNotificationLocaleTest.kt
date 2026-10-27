package moe.ouom.neriplayer.core.download.execution

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import moe.ouom.neriplayer.common.locale.LanguageManager
import moe.ouom.neriplayer.core.download.execution.notification.DOWNLOAD_EXECUTION_NOTIFICATION_CHANNEL_ID
import moe.ouom.neriplayer.core.download.execution.notification.DownloadExecutionNotificationSnapshot
import moe.ouom.neriplayer.core.download.execution.notification.buildDownloadExecutionNotification
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DownloadExecutionNotificationLocaleTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val savedLanguage = LanguageManager.getCurrentLanguage(context)
    private val savedLocale = Locale.getDefault()

    @After
    fun restoreLanguage() {
        LanguageManager.setLanguage(context, savedLanguage)
        Locale.setDefault(savedLocale)
    }

    @Test
    fun `existing download host localizes every rebuilt notification after language changes`() {
        val snapshot = DownloadExecutionNotificationSnapshot(
            totalSongs = 3,
            completedSongs = 1,
            remainingSongs = 2,
            currentSong = "用户的歌曲",
            hasWork = true
        )
        val manager = checkNotNull(context.getSystemService(NotificationManager::class.java))
        for ((language, title, channel) in listOf(
            Triple(LanguageManager.Language.ENGLISH, "Downloading", "Downloads"),
            Triple(LanguageManager.Language.CHINESE, "下载中", "下载任务"),
            Triple(LanguageManager.Language.ENGLISH, "Downloading", "Downloads")
        )) {
            LanguageManager.setLanguage(context, language)
            val notification = buildDownloadExecutionNotification(context, snapshot)
            assertEquals(title, notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals("用户的歌曲", notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
            val notificationChannel = checkNotNull(
                manager.getNotificationChannel(DOWNLOAD_EXECUTION_NOTIFICATION_CHANNEL_ID)
            )
            assertEquals(channel, notificationChannel.name)
        }
    }
}
