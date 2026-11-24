package moe.ouom.neriplayer.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppStartupGraphRestorationTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun hiddenHomeWaitsForItsLayoutAndKeepsTheResolvedRootAcrossRestoration() {
        val preferred = mutableStateOf("home")
        val available = mutableStateOf<Boolean?>(null)
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            rememberStartupGraphRoute(preferred.value, available.value, false)?.let { Text(it) }
        }
        composeRule.onNodeWithText("home").assertDoesNotExist()
        composeRule.runOnIdle { available.value = false }
        composeRule.onNodeWithText("explore").assertExists()
        composeRule.runOnIdle {
            preferred.value = "library"
            available.value = null
        }
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithText("explore").assertExists()
    }
}
