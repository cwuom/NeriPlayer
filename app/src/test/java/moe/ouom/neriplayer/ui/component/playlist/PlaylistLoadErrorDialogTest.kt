package moe.ouom.neriplayer.ui.component.playlist

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import java.util.Locale
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.locale.LanguageManager
import moe.ouom.neriplayer.activity.LocalizedAppContent
import moe.ouom.neriplayer.data.model.playlist.PlaylistLoadFailure
import moe.ouom.neriplayer.platform.bilibili.api.client.BiliHttpStatusException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import moe.ouom.neriplayer.R as AppR

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class PlaylistLoadErrorDialogTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `retained failure resolves in the current language without classifying translated text`() {
        val failure = playlistLoadFailure(IOException("private diagnostic"))
        val message = playlistLoadErrorText(failure)
        val english = resourcesFor("en")
        val chinese = resourcesFor("zh-CN")

        assertEquals(english.getString(AppR.string.playlist_error_network), message.resolve(english))
        assertEquals(chinese.getString(AppR.string.playlist_error_network), message.resolve(chinese))
        assertFalse(message.resolve(chinese).contains("private diagnostic"))
    }

    @Test
    fun `offline failure keeps its guidance after the language changes`() {
        val message = playlistLoadErrorText(PlaylistLoadFailure.Offline)
        val english = resourcesFor("en")
        val chinese = resourcesFor("zh-CN")

        assertEquals(english.getString(AppR.string.playlist_error_offline_no_cache), message.resolve(english))
        assertEquals(chinese.getString(AppR.string.playlist_error_offline_no_cache), message.resolve(chinese))
    }

    private fun resourcesFor(tag: String) = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
    ).resources

    @Test
    fun `an open failure dialog changes language while keeping the same failure`() {
        val language = mutableStateOf(LanguageManager.Language.CHINESE)
        val failure = PlaylistLoadFailure.Offline
        composeRule.setContent {
            LocalizedAppContent(language.value) {
                MaterialTheme { PlaylistLoadErrorDialog(failure, onRetry = {}, onBack = {}) }
            }
        }

        composeRule.onNodeWithText(resourcesFor("zh").getString(AppR.string.playlist_error_offline_no_cache)).assertExists()
        composeRule.runOnIdle { language.value = LanguageManager.Language.ENGLISH }
        composeRule.onNodeWithText(resourcesFor("en").getString(AppR.string.playlist_error_offline_no_cache)).assertExists()
    }

    @Test
    fun `http risk control has a localized summary without a server document`() {
        val expected = context.getString(AppR.string.playlist_error_bili_risk_control)

        assertEquals(expected, playlistLoadErrorText(playlistLoadFailure(BiliHttpStatusException(412))).resolve(context.resources))
    }

    @Test
    fun `other http errors retain only the status code`() {
        val expected = context.getString(AppR.string.playlist_error_http, 503)

        assertEquals(expected, playlistLoadErrorText(playlistLoadFailure(IOException("wrapper", BiliHttpStatusException(503)))).resolve(context.resources))
    }

    @Test
    fun `known offline cache guidance survives the screen boundary`() {
        val message = context.getString(AppR.string.playlist_error_offline_no_cache)
        assertEquals(message, playlistLoadErrorText(PlaylistLoadFailure.Offline).resolve(context.resources))
    }

    @Test
    fun `network and unknown failures never echo exception or response text`() {
        assertEquals(
            context.getString(AppR.string.playlist_error_network),
            playlistLoadErrorText(playlistLoadFailure(IOException("private address and diagnostic"))).resolve(context.resources)
        )
        val unknown = playlistLoadErrorText(playlistLoadFailure(IllegalStateException("<html>private diagnostic</html>"))).resolve(context.resources)
        assertEquals(context.getString(AppR.string.playlist_error_unknown), unknown)
        assertFalse(unknown.contains("private diagnostic"))
    }

    @Test
    fun `dialog exposes retry and return as separate explicit actions`() {
        var retries = 0
        var returns = 0
        val message = playlistLoadFailure(IOException("<html>private diagnostic</html>", BiliHttpStatusException(412)))
        composeRule.setContent {
            MaterialTheme {
                PlaylistLoadErrorDialog(message, onRetry = { retries++ }, onBack = { returns++ })
            }
        }

        composeRule.onNodeWithText(playlistLoadErrorText(message).resolve(context.resources)).assertExists()
        composeRule.onNodeWithText("private diagnostic", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.action_retry)).performClick()
        composeRule.runOnIdle { assertEquals(1, retries); assertEquals(0, returns) }
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.action_back)).performClick()
        composeRule.runOnIdle { assertEquals(1, retries); assertEquals(1, returns) }
    }
}
