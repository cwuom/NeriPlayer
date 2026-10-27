package moe.ouom.neriplayer.ui.i18n

import android.app.Application
import android.content.res.Configuration
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.R as AppR
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.api.sync.github.GitHubRateLimitException
import moe.ouom.neriplayer.ui.viewmodel.syncFailureText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(AndroidJUnit4::class)
@Config(sdk = [28, 32], application = Application::class)
class UiTextTest {
    @Test
    fun storedMessageUsesTheResourcesAtDisplayTime() {
        val message = UiText.Resource(CoreCommonR.string.action_confirm)

        assertEquals("Confirm", message.resolve(resources("en")))
        assertEquals("确定", message.resolve(resources("zh")))
        assertEquals("Confirm", message.resolve(resources("en")))
    }

    @Test
    fun nestedAppMessagesAreTranslatedTogetherWithTheirParameters() {
        val message = UiText.Resource(
            CoreCommonR.string.error_search_failed,
            listOf(UiText.Resource(AppR.string.playlist_error_network))
        )

        assertEquals(
            "Search failed: Unable to connect to the platform. Check your connection and retry.",
            message.resolve(resources("en"))
        )
        assertEquals("搜索失败: 无法连接到平台，请检查网络后重试", message.resolve(resources("zh")))
    }

    @Test
    fun quantityAndArgumentsAreResolvedUnderTheSelectedLanguage() {
        val single = UiText.Plural(AppR.plurals.home_song_count_format, 1, listOf(1))
        val multiple = UiText.Plural(AppR.plurals.home_song_count_format, 2, listOf(2))

        assertEquals("1 song", single.resolve(resources("en")))
        assertEquals("2 songs", multiple.resolve(resources("en")))
        assertEquals("1首", single.resolve(resources("zh")))
        assertEquals("2首", multiple.resolve(resources("zh")))
    }

    @Test
    fun userAndProviderTextIsPreservedVerbatim() {
        val raw = UiText.Literal("用户曲名 / Song")

        assertEquals("用户曲名 / Song", raw.resolve(resources("en")))
        assertEquals("用户曲名 / Song", raw.resolve(resources("zh")))
    }

    @Test
    fun rateLimitFeedbackKeepsSingularEnglishAndTranslatesNestedDetails() {
        val text = syncFailureText(
            GitHubRateLimitException(429, 0L, true, "internal diagnostic"),
            CoreCommonR.string.github_sync_failed_message
        )

        assertTrue(text.resolve(resources("en")).contains("about 1 second. The next attempt"))
        assertTrue(text.resolve(resources("zh")).contains("约 1 秒后可重试，稍后重试"))
        val multiple = UiText.Plural(
            CoreCommonR.plurals.github_rate_limit_message, 2, listOf(2L, "")
        )
        assertTrue(multiple.resolve(resources("en")).contains("about 2 seconds."))
    }

    private fun resources(language: String): Resources {
        val application = ApplicationProvider.getApplicationContext<Application>()
        return application.createConfigurationContext(
            Configuration().apply { setLocale(Locale.forLanguageTag(language)) }
        ).resources
    }
}
