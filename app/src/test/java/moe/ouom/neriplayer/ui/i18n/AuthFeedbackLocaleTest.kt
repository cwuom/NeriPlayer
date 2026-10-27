package moe.ouom.neriplayer.ui.i18n

import android.app.Application
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsAccountAuthController
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsAuthDialogState
import moe.ouom.neriplayer.ui.viewmodel.auth.BiliAuthEvent
import moe.ouom.neriplayer.ui.viewmodel.auth.BiliAuthViewModel
import moe.ouom.neriplayer.ui.viewmodel.auth.YouTubeAuthEvent
import moe.ouom.neriplayer.ui.viewmodel.auth.YouTubeAuthViewModel
import moe.ouom.neriplayer.ui.viewmodel.debug.NeteaseAuthEvent
import moe.ouom.neriplayer.ui.viewmodel.debug.NeteaseAuthViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(AndroidJUnit4::class)
@Config(sdk = [28, 32], application = Application::class)
class AuthFeedbackLocaleTest {
    @Test
    fun retainedAuthenticationErrorsUseCurrentLanguageWithoutRepeatingAccountActions() {
        val netease = mock(NeteaseAuthViewModel::class.java)
        val bili = mock(BiliAuthViewModel::class.java)
        val youtube = mock(YouTubeAuthViewModel::class.java)
        val dialogs = SettingsAuthDialogState(
            mutableIntStateOf(1), mutableIntStateOf(2), mutableIntStateOf(1)
        )
        var retained: UiText? = null
        val controller = SettingsAccountAuthController(
            netease, bili, youtube, dialogs,
            mutableStateOf<(UiText?) -> Unit>({ retained = it })
        )
        controller.openNeteaseAtTab(2)
        val error = UiText.Resource(CoreCommonR.string.auth_cookie_empty)
        val events = listOf<() -> Unit>(
            { controller.onNeteaseEvent(NeteaseAuthEvent.ShowSnack(error)) },
            { controller.onBiliEvent(BiliAuthEvent.ShowSnack(error)) },
            { controller.onYouTubeEvent(YouTubeAuthEvent.ShowSnack(error)) }
        )

        events.forEach { deliver ->
            deliver()
            assertEquals("Cookie is empty", retained?.resolve(resources("en")))
            assertEquals("Cookie 为空", retained?.resolve(resources("zh")))
            assertEquals("Cookie is empty", retained?.resolve(resources("en")))
            assertTrue(dialogs.showNeteaseSheet)
            assertEquals(2, dialogs.neteaseTab.intValue)
        }
        verifyNoInteractions(netease, bili, youtube)
    }

    private fun resources(language: String): Resources {
        val application = ApplicationProvider.getApplicationContext<Application>()
        return application.createConfigurationContext(
            Configuration().apply { setLocale(Locale.forLanguageTag(language)) }
        ).resources
    }
}
