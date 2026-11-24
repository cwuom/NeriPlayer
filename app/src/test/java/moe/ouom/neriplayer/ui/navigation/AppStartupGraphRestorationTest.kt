package moe.ouom.neriplayer.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.settings.navigation.StartDestinationSnapshot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppStartupGraphRestorationTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun hiddenHomeWaitsForItsLayoutAndKeepsTheResolvedRootAcrossRestoration() {
        val configured = mutableStateOf<String?>(null)
        val cached = mutableStateOf<StartDestinationSnapshot?>(StartDestinationSnapshot("home"))
        val available = mutableStateOf<Boolean?>(null)
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            rememberStartupNavigationRoutes(configured.value, cached.value, available.value, false)?.let {
                Text("root=${it.graphRoot},preferred=${it.preferred}")
            }
        }
        composeRule.onNodeWithText("root=", substring = true).assertDoesNotExist()
        composeRule.runOnIdle { available.value = false }
        composeRule.onNodeWithText("root=explore,preferred=home").assertExists()
        composeRule.runOnIdle {
            configured.value = "library"
            available.value = null
        }
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithText("root=explore,preferred=library").assertExists()
        composeRule.runOnIdle {
            configured.value = null
            cached.value = null
        }
        composeRule.onNodeWithText("root=explore,preferred=explore").assertExists()
    }
}
