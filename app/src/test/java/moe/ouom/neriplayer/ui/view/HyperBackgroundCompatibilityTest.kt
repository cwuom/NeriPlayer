package moe.ouom.neriplayer.ui.view

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [28], application = Application::class)
class HyperBackgroundCompatibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun unsupportedAndroidVersionLeavesTheBackdropWithoutAnAndroidView() {
        composeRule.setContent {
            Box(Modifier.size(192.dp).testTag("backdrop-host")) {
                HyperBackground(
                    modifier = Modifier.fillMaxSize().testTag("runtime-shader-view"),
                    isDark = true,
                    coverUrl = null
                )
            }
        }

        composeRule.onNodeWithTag("backdrop-host").assertIsDisplayed()
        composeRule.onNodeWithTag("runtime-shader-view").assertDoesNotExist()
    }
}
