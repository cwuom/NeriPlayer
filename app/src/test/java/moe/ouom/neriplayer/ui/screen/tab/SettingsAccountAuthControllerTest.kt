package moe.ouom.neriplayer.ui.screen.tab

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsAccountAuthController
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsAuthDialogState
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsAccountPlatform
import moe.ouom.neriplayer.ui.viewmodel.auth.BiliAuthEvent
import moe.ouom.neriplayer.ui.viewmodel.auth.BiliAuthViewModel
import moe.ouom.neriplayer.ui.viewmodel.auth.YouTubeAuthEvent
import moe.ouom.neriplayer.ui.viewmodel.auth.YouTubeAuthViewModel
import moe.ouom.neriplayer.ui.viewmodel.debug.NeteaseAuthEvent
import moe.ouom.neriplayer.ui.viewmodel.debug.NeteaseAuthViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions

class SettingsAccountAuthControllerTest {
    private val neteaseVm = mock(NeteaseAuthViewModel::class.java)
    private val biliVm = mock(BiliAuthViewModel::class.java)
    private val youtubeVm = mock(YouTubeAuthViewModel::class.java)
    private val dialogs = SettingsAuthDialogState(
        biliTab = mutableIntStateOf(0),
        neteaseTab = mutableIntStateOf(0),
        youtubeTab = mutableIntStateOf(0)
    )
    private val messages = mutableListOf<String?>()
    private val latestMessageAction = mutableStateOf<(String?) -> Unit>({ messages += it })
    private val controller = SettingsAccountAuthController(
        neteaseVm, biliVm, youtubeVm, dialogs, latestMessageAction
    )

    @Test
    fun `platform login opens only its own sheet and resets its selected tab`() {
        listOf(
            SettingsAccountPlatform.Netease,
            SettingsAccountPlatform.Bilibili,
            SettingsAccountPlatform.YouTube
        ).forEach { platform ->
            dialogs.showNeteaseSheet = false
            dialogs.showBiliSheet = false
            dialogs.showYouTubeSheet = false
            dialogs.neteaseTab.intValue = 2
            dialogs.biliTab.intValue = 3
            dialogs.youtubeTab.intValue = 4

            controller.actions.openPlatformLogin(platform)

            assertEquals(platform == SettingsAccountPlatform.Netease, dialogs.showNeteaseSheet)
            assertEquals(platform == SettingsAccountPlatform.Bilibili, dialogs.showBiliSheet)
            assertEquals(platform == SettingsAccountPlatform.YouTube, dialogs.showYouTubeSheet)
            assertEquals(if (platform == SettingsAccountPlatform.Netease) 0 else 2, dialogs.neteaseTab.intValue)
            assertEquals(if (platform == SettingsAccountPlatform.Bilibili) 0 else 3, dialogs.biliTab.intValue)
            assertEquals(if (platform == SettingsAccountPlatform.YouTube) 0 else 4, dialogs.youtubeTab.intValue)
        }
        assertEquals(listOf<String?>(null, null, null), messages)
        verifyNoInteractions(neteaseVm, biliVm, youtubeVm)
    }

    @Test
    fun `platform saved authorization opens only its own management dialog`() {
        listOf(
            SettingsAccountPlatform.Netease,
            SettingsAccountPlatform.Bilibili,
            SettingsAccountPlatform.YouTube
        ).forEach { platform ->
            dialogs.showNeteaseSavedCookieDialog = false
            dialogs.showBiliSavedCookieDialog = false
            dialogs.showYouTubeSavedCookieDialog = false

            controller.actions.openPlatformSavedAuthorization(platform)

            assertEquals(platform == SettingsAccountPlatform.Netease, dialogs.showNeteaseSavedCookieDialog)
            assertEquals(platform == SettingsAccountPlatform.Bilibili, dialogs.showBiliSavedCookieDialog)
            assertEquals(platform == SettingsAccountPlatform.YouTube, dialogs.showYouTubeSavedCookieDialog)
            assertFalse(dialogs.showNeteaseSheet)
            assertFalse(dialogs.showBiliSheet)
            assertFalse(dialogs.showYouTubeSheet)
        }
        assertEquals(listOf<String?>(null, null, null), messages)
        verifyNoInteractions(neteaseVm, biliVm, youtubeVm)
    }

    @Test
    fun `unavailable qq platform does not open a dialog or clear an existing message`() {
        messages += "retained"
        dialogs.neteaseTab.intValue = 2
        dialogs.biliTab.intValue = 3
        dialogs.youtubeTab.intValue = 4

        controller.actions.openPlatformLogin(SettingsAccountPlatform.QqMusic)
        controller.actions.openPlatformSavedAuthorization(SettingsAccountPlatform.QqMusic)

        assertFalse(dialogs.showNeteaseSheet)
        assertFalse(dialogs.showBiliSheet)
        assertFalse(dialogs.showYouTubeSheet)
        assertFalse(dialogs.showNeteaseSavedCookieDialog)
        assertFalse(dialogs.showBiliSavedCookieDialog)
        assertFalse(dialogs.showYouTubeSavedCookieDialog)
        assertEquals(2, dialogs.neteaseTab.intValue)
        assertEquals(3, dialogs.biliTab.intValue)
        assertEquals(4, dialogs.youtubeTab.intValue)
        assertEquals(listOf("retained"), messages)
        verifyNoInteractions(neteaseVm, biliVm, youtubeVm)
    }

    @Test
    fun `login events own their sheet and success state`() {
        controller.actions.openNeteaseAtTab(2)
        controller.actions.openNeteaseSavedCookieDialog()
        controller.onNeteaseEvent(NeteaseAuthEvent.ShowSnack("netease error"))
        controller.onNeteaseEvent(NeteaseAuthEvent.AskConfirmSend("***1234"))
        assertEquals(2, dialogs.neteaseTab.intValue)
        assertTrue(dialogs.showNeteaseConfirmDialog)
        assertEquals("***1234", dialogs.confirmPhoneMasked)

        controller.onNeteaseEvent(NeteaseAuthEvent.LoginSuccess)
        assertFalse(dialogs.showNeteaseSheet)
        assertFalse(dialogs.showNeteaseSavedCookieDialog)
        assertEquals(CoreCommonR.string.settings_netease_login_success, dialogs.loginSuccessTitleRes)
        assertEquals(listOf(null, null, "netease error", null), messages)
        verify(neteaseVm).refreshAuthHealth()
        controller.actions.dismissLoginSuccess()
        assertEquals(null, dialogs.loginSuccessTitleRes)
    }

    @Test
    fun `bili and youtube actions keep their own tabs and credentials`() {
        controller.actions.openBiliAtTab(1)
        controller.actions.openBiliSavedCookieDialog()
        controller.onBiliEvent(BiliAuthEvent.ShowSnack("bili error"))
        controller.onBiliEvent(BiliAuthEvent.LoginSuccess)
        assertEquals(1, dialogs.biliTab.intValue)
        assertFalse(dialogs.showBiliSheet)
        assertFalse(dialogs.showBiliSavedCookieDialog)
        assertEquals(CoreCommonR.string.settings_bili_login_success, dialogs.loginSuccessTitleRes)
        verify(biliVm).refreshAuthHealth()

        controller.actions.openYouTubeAtTab(2)
        controller.actions.openYouTubeSavedCookieDialog()
        controller.onYouTubeEvent(YouTubeAuthEvent.ShowSnack("youtube error"))
        controller.onYouTubeEvent(YouTubeAuthEvent.LoginSuccess)
        assertEquals(2, dialogs.youtubeTab.intValue)
        assertFalse(dialogs.showYouTubeSheet)
        assertFalse(dialogs.showYouTubeSavedCookieDialog)
        assertEquals(CoreCommonR.string.settings_youtube_login_success, dialogs.loginSuccessTitleRes)
        assertEquals(listOf(null, null, "bili error", null, null, null, "youtube error", null), messages)
        verify(youtubeVm).refreshAuthHealth()
    }

    @Test
    fun `saved credential logout closes only its own dialog`() {
        controller.actions.openNeteaseSavedCookieDialog()
        controller.actions.openBiliSavedCookieDialog()
        controller.actions.openYouTubeSavedCookieDialog()

        controller.actions.logoutNetease()
        controller.actions.logoutBili()
        controller.actions.logoutYouTube()

        assertFalse(dialogs.showNeteaseSavedCookieDialog)
        assertFalse(dialogs.showBiliSavedCookieDialog)
        assertFalse(dialogs.showYouTubeSavedCookieDialog)
        verify(neteaseVm).clearCookies()
        verify(biliVm).clearCookies()
        verify(youtubeVm).clearAuth()
    }

    @Test
    fun `event callback uses the latest inline message recipient`() {
        val replacementMessages = mutableListOf<String?>()
        latestMessageAction.value = { replacementMessages += it }

        controller.onYouTubeEvent(YouTubeAuthEvent.ShowSnack("updated"))

        assertTrue(messages.isEmpty())
        assertEquals(listOf("updated"), replacementMessages)
    }
}
