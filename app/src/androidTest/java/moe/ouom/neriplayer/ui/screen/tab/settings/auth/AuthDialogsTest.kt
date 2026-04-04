package moe.ouom.neriplayer.ui.screen.tab.settings.auth

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.viewmodel.auth.BiliAuthViewModel
import moe.ouom.neriplayer.ui.viewmodel.auth.YouTubeAuthViewModel
import moe.ouom.neriplayer.ui.viewmodel.debug.NeteaseAuthViewModel
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthDialogsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    private val targetContext
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val application
        get() = targetContext.applicationContext as Application

    @Test
    fun neteaseSheet_switchesTabsAndShowsExpectedInputs() {
        val context = targetContext
        val vm = NeteaseAuthViewModel(application)

        composeRule.setContent {
            MaterialTheme {
                Box {
                    SettingsNeteaseAuthDialogs(
                        showSheet = true,
                        initialTab = 0,
                        onDismissSheet = { },
                        inlineMsg = context.getString(R.string.auth_cookie_saved),
                        onInlineMsgChange = { },
                        showConfirmDialog = false,
                        confirmPhoneMasked = null,
                        onDismissConfirmDialog = { },
                        vm = vm,
                        showCookieDialog = false,
                        cookieText = "",
                        onDismissCookieDialog = { },
                        onBrowserLogin = { }
                    )
                }
            }
        }

        waitForText(context.getString(R.string.login_browser))
        composeRule.onNodeWithText(context.getString(R.string.login_browser)).performClick()
        waitForText(context.getString(R.string.settings_netease_login_browser_hint))
        waitForText(context.getString(R.string.login_start_browser))

        composeRule.onNodeWithText(context.getString(R.string.login_paste_cookie)).performClick()
        waitForText(context.getString(R.string.login_paste_cookie_hint))
        waitForText(context.getString(R.string.login_save_cookie))

        composeRule.onNodeWithText(context.getString(R.string.login_verification_code)).performClick()
        waitForText(context.getString(R.string.settings_phone_number_hint))
        waitForText(context.getString(R.string.login_sms_code))
        waitForText(context.getString(R.string.login_send_code))
        waitForText(context.getString(R.string.login_title))
    }

    @Test
    fun biliSavedCookieDialog_continueActionOpensBrowserTab() {
        val context = targetContext
        val vm = BiliAuthViewModel(application)
        val openedTabs = mutableListOf<Int>()
        var dismissedCount = 0

        composeRule.setContent {
            MaterialTheme {
                Box {
                    SettingsBiliAuthDialogs(
                        showSheet = false,
                        initialTab = 0,
                        onDismissSheet = { },
                        inlineMsg = null,
                        onInlineMsgChange = { },
                        vm = vm,
                        showCookieDialog = false,
                        cookieText = "",
                        onDismissCookieDialog = { },
                        showSavedCookieDialog = true,
                        onDismissSavedCookieDialog = { dismissedCount++ },
                        onOpenSheetAtTab = { openedTabs += it },
                        onLogout = { },
                        onBrowserLogin = { }
                    )
                }
            }
        }

        waitForText(context.getString(R.string.settings_saved_cookie_continue))
        composeRule.onNodeWithText(
            context.getString(R.string.settings_saved_cookie_continue)
        ).performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(0), openedTabs)
            assertEquals(1, dismissedCount)
        }
    }

    @Test
    fun biliSavedCookieDialog_logoutActionInvokesCallback() {
        val context = targetContext
        val vm = BiliAuthViewModel(application)
        var dismissedCount = 0
        var logoutCount = 0

        composeRule.setContent {
            MaterialTheme {
                Box {
                    SettingsBiliAuthDialogs(
                        showSheet = false,
                        initialTab = 0,
                        onDismissSheet = { },
                        inlineMsg = null,
                        onInlineMsgChange = { },
                        vm = vm,
                        showCookieDialog = false,
                        cookieText = "",
                        onDismissCookieDialog = { },
                        showSavedCookieDialog = true,
                        onDismissSavedCookieDialog = { dismissedCount++ },
                        onOpenSheetAtTab = { },
                        onLogout = { logoutCount++ },
                        onBrowserLogin = { }
                    )
                }
            }
        }

        waitForText(context.getString(R.string.settings_saved_cookie_logout))
        composeRule.onNodeWithText(
            context.getString(R.string.settings_saved_cookie_logout)
        ).performClick()

        composeRule.runOnIdle {
            assertEquals(1, logoutCount)
            assertEquals(1, dismissedCount)
        }
    }

    @Test
    fun neteaseSavedCookieDialog_continueAndLogoutActionsWork() {
        val context = targetContext
        val vm = NeteaseAuthViewModel(application)
        val openedTabs = mutableListOf<Int>()
        var dismissedCount = 0
        var logoutCount = 0

        composeRule.setContent {
            MaterialTheme {
                Box {
                    SettingsNeteaseAuthDialogs(
                        showSheet = false,
                        initialTab = 0,
                        onDismissSheet = { },
                        inlineMsg = null,
                        onInlineMsgChange = { },
                        showConfirmDialog = false,
                        confirmPhoneMasked = null,
                        onDismissConfirmDialog = { },
                        vm = vm,
                        showCookieDialog = false,
                        cookieText = "",
                        onDismissCookieDialog = { },
                        showSavedCookieDialog = true,
                        onDismissSavedCookieDialog = { dismissedCount++ },
                        onOpenSheetAtTab = { openedTabs += it },
                        onLogout = { logoutCount++ },
                        onBrowserLogin = { }
                    )
                }
            }
        }

        waitForText(context.getString(R.string.settings_saved_cookie_continue))
        composeRule.onNodeWithText(
            context.getString(R.string.settings_saved_cookie_continue)
        ).performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(0), openedTabs)
            assertEquals(1, dismissedCount)
        }

        dismissedCount = 0
        openedTabs.clear()

        composeRule.setContent {
            MaterialTheme {
                Box {
                    SettingsNeteaseAuthDialogs(
                        showSheet = false,
                        initialTab = 0,
                        onDismissSheet = { },
                        inlineMsg = null,
                        onInlineMsgChange = { },
                        showConfirmDialog = false,
                        confirmPhoneMasked = null,
                        onDismissConfirmDialog = { },
                        vm = vm,
                        showCookieDialog = false,
                        cookieText = "",
                        onDismissCookieDialog = { },
                        showSavedCookieDialog = true,
                        onDismissSavedCookieDialog = { dismissedCount++ },
                        onOpenSheetAtTab = { },
                        onLogout = { logoutCount++ },
                        onBrowserLogin = { }
                    )
                }
            }
        }

        waitForText(context.getString(R.string.settings_saved_cookie_logout))
        composeRule.onNodeWithText(
            context.getString(R.string.settings_saved_cookie_logout)
        ).performClick()

        composeRule.runOnIdle {
            assertEquals(1, logoutCount)
            assertEquals(1, dismissedCount)
        }
    }

    @Test
    fun youtubeSheet_switchesToCookieImportTabAndShowsInput() {
        val context = targetContext
        val vm = YouTubeAuthViewModel(application)

        composeRule.setContent {
            MaterialTheme {
                Box {
                    SettingsYouTubeAuthDialogs(
                        showSheet = true,
                        initialTab = 0,
                        onDismissSheet = { },
                        inlineMsg = context.getString(R.string.auth_cookie_saved),
                        onInlineMsgChange = { },
                        vm = vm,
                        showCookieDialog = false,
                        cookieText = "",
                        onDismissCookieDialog = { },
                        onBrowserLogin = { }
                    )
                }
            }
        }

        waitForText(context.getString(R.string.settings_youtube_login_browser_hint))
        composeRule.onNodeWithText(context.getString(R.string.login_paste_cookie)).performClick()
        waitForText(context.getString(R.string.login_paste_youtube_cookie_hint))
        waitForText(context.getString(R.string.login_save_cookie))
    }

    @Test
    fun youtubeSavedCookieDialog_continueAndLogoutActionsWork() {
        val context = targetContext
        val vm = YouTubeAuthViewModel(application)
        val openedTabs = mutableListOf<Int>()
        var dismissedCount = 0
        var logoutCount = 0

        composeRule.setContent {
            MaterialTheme {
                Box {
                    SettingsYouTubeAuthDialogs(
                        showSheet = false,
                        initialTab = 0,
                        onDismissSheet = { },
                        inlineMsg = null,
                        onInlineMsgChange = { },
                        vm = vm,
                        showCookieDialog = false,
                        cookieText = "",
                        onDismissCookieDialog = { },
                        showSavedCookieDialog = true,
                        onDismissSavedCookieDialog = { dismissedCount++ },
                        onOpenSheetAtTab = { openedTabs += it },
                        onLogout = { logoutCount++ },
                        onBrowserLogin = { }
                    )
                }
            }
        }

        waitForText(context.getString(R.string.settings_saved_cookie_continue))
        composeRule.onNodeWithText(
            context.getString(R.string.settings_saved_cookie_continue)
        ).performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(0), openedTabs)
            assertEquals(1, dismissedCount)
        }

        dismissedCount = 0
        openedTabs.clear()

        composeRule.setContent {
            MaterialTheme {
                Box {
                    SettingsYouTubeAuthDialogs(
                        showSheet = false,
                        initialTab = 0,
                        onDismissSheet = { },
                        inlineMsg = null,
                        onInlineMsgChange = { },
                        vm = vm,
                        showCookieDialog = false,
                        cookieText = "",
                        onDismissCookieDialog = { },
                        showSavedCookieDialog = true,
                        onDismissSavedCookieDialog = { dismissedCount++ },
                        onOpenSheetAtTab = { },
                        onLogout = { logoutCount++ },
                        onBrowserLogin = { }
                    )
                }
            }
        }

        waitForText(context.getString(R.string.settings_saved_cookie_logout))
        composeRule.onNodeWithText(
            context.getString(R.string.settings_saved_cookie_logout)
        ).performClick()

        composeRule.runOnIdle {
            assertEquals(1, logoutCount)
            assertEquals(1, dismissedCount)
        }
    }

    @Test
    fun biliSheet_switchesToCookieImportTabAndShowsInput() {
        val context = targetContext
        val vm = BiliAuthViewModel(application)

        composeRule.setContent {
            MaterialTheme {
                Box {
                    SettingsBiliAuthDialogs(
                        showSheet = true,
                        initialTab = 0,
                        onDismissSheet = { },
                        inlineMsg = context.getString(R.string.auth_cookie_saved),
                        onInlineMsgChange = { },
                        vm = vm,
                        showCookieDialog = false,
                        cookieText = "",
                        onDismissCookieDialog = { },
                        onBrowserLogin = { }
                    )
                }
            }
        }

        waitForText(context.getString(R.string.settings_bili_login_browser_hint))
        composeRule.onNodeWithText(context.getString(R.string.login_paste_cookie)).performClick()
        waitForText(context.getString(R.string.login_paste_bili_cookie_hint))
        waitForText(context.getString(R.string.login_save_cookie))
    }

    @Test
    fun cookieTextDialog_showsPlaceholderAndDismisses() {
        val context = targetContext
        var dismissedCount = 0

        composeRule.setContent {
            MaterialTheme {
                Box {
                    CookieTextDialog(
                        title = context.getString(R.string.login_success),
                        cookieText = "",
                        onDismiss = { dismissedCount++ }
                    )
                }
            }
        }

        waitForText(context.getString(R.string.settings_empty_placeholder))
        waitForText(context.getString(R.string.action_ok))
        composeRule.onNodeWithText(context.getString(R.string.action_ok)).performClick()

        composeRule.runOnIdle {
            assertEquals(1, dismissedCount)
        }
    }

    @Test
    fun cookieTextDialog_showsRawCookieText() {
        val context = targetContext

        composeRule.setContent {
            MaterialTheme {
                Box {
                    CookieTextDialog(
                        title = context.getString(R.string.login_success),
                        cookieText = "MUSIC_U=abc; __csrf=def",
                        onDismiss = { }
                    )
                }
            }
        }

        waitForText("MUSIC_U=abc; __csrf=def")
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 3_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
