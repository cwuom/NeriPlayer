package moe.ouom.neriplayer.platform.youtube.settings

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Locale
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeContentLabel
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicRequestLocale
import moe.ouom.neriplayer.platform.youtube.api.protocol.YouTubeCatalogSource
import moe.ouom.neriplayer.platform.youtube.api.protocol.YouTubeMusicLocaleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class YouTubeCatalogConfigurationTest {
    @Test
    fun requestsReadPersistedSettingsAtStartupAndChangesWithoutRestarting() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YouTubeContentSettings.initialize(context)
        val original = YouTubeContentSettings.preferences.value
        val mainland = Locale.forLanguageTag("en-CN")
        try {
            context.getSharedPreferences("youtube_content", Context.MODE_PRIVATE).edit {
                putString("language", "ja")
                putString("country", "CA")
                putBoolean("hide_explicit", true)
                putBoolean("hide_music_videos", false)
            }
            YouTubeContentSettings.initialize(context)
            YouTubeCatalogSource.bind({ YouTubeContentSettings.preferences.value }, YouTubeContentSettings::systemLocale)
            assertEquals(YouTubeMusicRequestLocale("ja", "CA"), YouTubeMusicLocaleResolver.preferred(mainland))
            assertEquals(listOf(YouTubeMusicRequestLocale("ja", "CA")), YouTubeMusicLocaleResolver.requestCandidates())
            assertTrue(YouTubeCatalogSource.policy.hides(YouTubeContentLabel.EXPLICIT))
            assertFalse(YouTubeCatalogSource.policy.hides(YouTubeContentLabel.MUSIC_VIDEO))

            val updated = YouTubeContentPreferences(hideMusicVideos = true)
            YouTubeContentSettings.publish(updated)
            assertEquals(YouTubeMusicRequestLocale("zh-CN", "JP"), YouTubeMusicLocaleResolver.preferred(mainland))
            assertEquals(updated.resolveLocale(YouTubeContentSettings.systemLocale()), YouTubeMusicLocaleResolver.preferred())
            assertFalse(YouTubeCatalogSource.policy.hides(YouTubeContentLabel.EXPLICIT))
            assertTrue(YouTubeCatalogSource.policy.hides(YouTubeContentLabel.MUSIC_VIDEO))
        } finally {
            YouTubeContentSettings.publish(original)
        }
    }
}
