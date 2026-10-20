package moe.ouom.neriplayer.ui.component.playlist

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.platform.bilibili.api.client.BiliHttpStatusException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class PlaylistLoadErrorDialogTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `http risk control has a localized summary without a server document`() {
        val raw = "Network error: HTTP 412: <html>private diagnostic</html>"
        val expected = context.getString(CoreCommonR.string.playlist_error_bili_risk_control)

        assertEquals(expected, playlistLoadErrorMessage(context, BiliHttpStatusException(412)))
        assertEquals(expected, playlistLoadErrorMessage(context, raw))
    }

    @Test
    fun `other http errors retain only the status code`() {
        val expected = context.getString(CoreCommonR.string.playlist_error_http, 503)

        assertEquals(expected, playlistLoadErrorMessage(context, IOException("wrapper", BiliHttpStatusException(503))))
        assertEquals(expected, playlistLoadErrorMessage(context, "Load failed: HTTP 503: private diagnostic"))
    }

    @Test
    fun `known offline cache guidance survives the screen boundary`() {
        val message = context.getString(CoreCommonR.string.playlist_error_offline_no_cache)
        assertEquals(message, playlistLoadErrorMessage(context, message))
    }

    @Test
    fun `network and unknown failures never echo exception or response text`() {
        assertEquals(
            context.getString(CoreCommonR.string.playlist_error_network),
            playlistLoadErrorMessage(context, IOException("private address and diagnostic"))
        )
        val unknown = playlistLoadErrorMessage(context, "<html>private diagnostic</html>")
        assertEquals(context.getString(CoreCommonR.string.playlist_error_unknown), unknown)
        assertFalse(unknown.contains("private diagnostic"))
        assertEquals(unknown, playlistLoadErrorMessage(context, IllegalStateException("<html>private diagnostic</html>")))
    }

    @Test
    fun `dialog exposes retry and return as separate explicit actions`() {
        var retries = 0
        var returns = 0
        val message = playlistLoadErrorMessage(context, "HTTP 412: <html>private diagnostic</html>")
        composeRule.setContent {
            MaterialTheme {
                PlaylistLoadErrorDialog(message, onRetry = { retries++ }, onBack = { returns++ })
            }
        }

        composeRule.onNodeWithText(message).assertExists()
        composeRule.onNodeWithText("private diagnostic", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.action_retry)).performClick()
        composeRule.runOnIdle { assertEquals(1, retries); assertEquals(0, returns) }
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.action_back)).performClick()
        composeRule.runOnIdle { assertEquals(1, retries); assertEquals(1, returns) }
    }
}
