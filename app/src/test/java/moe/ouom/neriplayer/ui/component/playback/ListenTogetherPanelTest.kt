package moe.ouom.neriplayer.ui.component.playback

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R as CoreR
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ListenTogetherPanelTest {
    @get:Rule
    val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val cloudLabel get() = context.getString(CoreR.string.listen_together_tab_cloud)
    private val lanLabel get() = context.getString(CoreR.string.listen_together_tab_lan)

    @Test
    fun cloudAndLanTabsKeepBothInputDraftsWhenSwitching() {
        compose.setContent {
            NeriTheme(false, false, false, "6750A4", uiStyle = UiStyle.Clean, animateColors = false) {
                ListenTogetherTabs(
                    lanActive = false,
                    cloudRoomId = null,
                    cloudContent = {
                        var nickname by rememberSaveable { mutableStateOf("Cloud nickname") }
                        BasicTextField(nickname, { nickname = it }, Modifier.testTag("cloud-nickname"))
                    },
                    lanContent = {
                        var address by rememberSaveable { mutableStateOf("") }
                        BasicTextField(address, { address = it }, Modifier.testTag("lan-address"))
                    }
                )
            }
        }
        compose.onNodeWithText(cloudLabel).assertIsSelected()
        compose.onNodeWithTag("cloud-nickname").performTextReplacement("Draft nickname")
        compose.onNodeWithText(lanLabel).performClick().assertIsSelected()
        compose.onNodeWithTag("cloud-nickname").assertDoesNotExist()
        compose.onNodeWithTag("lan-address").performTextReplacement("192.168.1.2:39001")
        compose.onNodeWithText(cloudLabel).performClick()
        compose.onNodeWithTag("cloud-nickname").assertTextEquals("Draft nickname")
        compose.onNodeWithText(lanLabel).performClick()
        compose.onNodeWithTag("lan-address").assertTextEquals("192.168.1.2:39001")
    }

    @Test
    fun activeLanRoomOpensOnLanAndAllowsOpeningCloudControls() {
        showTabs(lanActive = true)
        compose.onNodeWithText(lanLabel).assertIsSelected()
        compose.onNodeWithText("LAN room controls").assertExists()
        compose.onNodeWithText(cloudLabel).performClick().assertIsSelected()
        compose.onNodeWithText(lanLabel).assertIsNotSelected()
        compose.onNodeWithText("Cloud room controls").assertExists()
    }

    @Test
    fun lanShortcutOpensLanBeforeAnyRoomIsActive() {
        showTabs(initialTab = ListenTogetherTab.Lan)
        compose.onNodeWithText(lanLabel).assertIsSelected()
        compose.onNodeWithText("LAN room controls").assertExists()
    }

    @Test
    fun newlyActiveRoomSelectsItsControls() {
        val lanActive = mutableStateOf(false)
        val cloudRoomId = mutableStateOf<String?>(null)
        compose.setContent {
            NeriTheme(false, false, false, "6750A4", uiStyle = UiStyle.Clean, animateColors = false) {
                ListenTogetherTabs(
                    lanActive = lanActive.value,
                    cloudRoomId = cloudRoomId.value,
                    cloudContent = { Text("Cloud room controls") },
                    lanContent = { Text("LAN room controls") }
                )
            }
        }
        compose.runOnIdle { lanActive.value = true }
        compose.onNodeWithText(lanLabel).assertIsSelected()
        compose.runOnIdle {
            lanActive.value = false
            cloudRoomId.value = "cloud-room"
        }
        compose.onNodeWithText(cloudLabel).assertIsSelected()
    }

    @Test
    fun repeatedSettingsSearchOpensTheRequestedTabEvenWithAnActiveRoom() {
        val requestId = mutableStateOf(1)
        compose.setContent {
            NeriTheme(false, false, false, "6750A4", uiStyle = UiStyle.Clean, animateColors = false) {
                ListenTogetherTabs(
                    lanActive = true,
                    cloudRoomId = null,
                    requestedTab = ListenTogetherTab.Cloud,
                    requestId = requestId.value,
                    cloudContent = { Text("Cloud room controls") },
                    lanContent = { Text("LAN room controls") }
                )
            }
        }
        compose.onNodeWithText(cloudLabel).assertIsSelected()
        compose.onNodeWithText(lanLabel).performClick().assertIsSelected()
        compose.runOnIdle { requestId.value++ }
        compose.onNodeWithText(cloudLabel).assertIsSelected()
    }

    private fun showTabs(
        lanActive: Boolean = false,
        initialTab: ListenTogetherTab = ListenTogetherTab.Cloud
    ) {
        compose.setContent {
            NeriTheme(false, false, false, "6750A4", uiStyle = UiStyle.Material, animateColors = false) {
                Column {
                    ListenTogetherTabs(
                        lanActive = lanActive,
                        cloudRoomId = null,
                        initialTab = initialTab,
                        cloudContent = { Text("Cloud room controls") },
                        lanContent = { Text("LAN room controls") }
                    )
                }
            }
        }
    }
}
