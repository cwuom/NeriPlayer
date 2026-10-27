package moe.ouom.neriplayer.core.player.service.lyrics

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import moe.ouom.neriplayer.common.locale.LanguageManager
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import java.time.Duration
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LiveLyricNotificationLocaleTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val savedLanguage = LanguageManager.getCurrentLanguage(context)
    private val savedLocale = Locale.getDefault()

    @After
    fun restoreLanguage() {
        LanguageManager.setLanguage(context, savedLanguage)
        Locale.setDefault(savedLocale)
    }

    @Test
    fun `lyric updates refresh the existing channel language without resetting user options`() {
        val channelId = "neriplayer_live_lyric_updates_v1"
        val manager = checkNotNull(context.getSystemService(NotificationManager::class.java))
        manager.createNotificationChannel(
            NotificationChannel(channelId, "Existing", NotificationManager.IMPORTANCE_HIGH)
                .apply { enableVibration(true) }
        )
        val bridge = LiveLyricNotificationBridge(context)
        bridge.setEnabled(true)
        for ((index, pair) in listOf(
            LanguageManager.Language.ENGLISH to "Live Lyrics",
            LanguageManager.Language.CHINESE to "Live 歌词",
            LanguageManager.Language.ENGLISH to "Live Lyrics"
        ).withIndex()) {
            LanguageManager.setLanguage(context, pair.first)
            bridge.sendLyric("用户的歌曲", LyricEntry("第${index}句歌词", 0L, 2_000L))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            val channel = checkNotNull(manager.getNotificationChannel(channelId))
            assertEquals(pair.second, channel.name)
            assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
            assertTrue(channel.shouldVibrate())
        }
        bridge.clear()
        shadowOf(Looper.getMainLooper()).idle()
    }
}
