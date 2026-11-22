package moe.ouom.neriplayer.ui.screen.tab.settings.listentogether

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R
import moe.ouom.neriplayer.core.di.AppContainer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsListenTogetherSectionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun defaultSettingsOpenCloudAndSearchShowsTheRequestedRoomControls() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        if (!AppContainer.isInitialized()) AppContainer.initialize(app)
        val searchTarget = mutableStateOf<String?>(null)
        compose.setContent {
            val controller = rememberSettingsListenTogetherController(
                AppContainer.listenTogetherPreferences, AppContainer.listenTogetherApi, AppContainer.listenTogetherSessionManager
            ) {}
            val target = searchTarget.value
            if (target == null) {
                SettingsListenTogetherSection(controller)
            } else {
                SettingsListenTogetherSection(controller, Modifier.testTag("search-result"), target, 1)
            }
        }
        compose.onNodeWithText(app.getString(R.string.listen_together_tab_cloud)).assertIsSelected()
        compose.onNodeWithText(app.getString(R.string.listen_together_join_room)).assertExists()
        compose.runOnIdle { searchTarget.value = "manual:lan_listen_together" }
        compose.onNodeWithTag("search-result").assertExists()
        compose.onNodeWithText(app.getString(R.string.listen_together_tab_lan)).assertIsSelected()
        compose.onNodeWithText(app.getString(R.string.lan_create)).assertExists()
        compose.runOnIdle { searchTarget.value = "manual:listen_together_nickname" }
        compose.onNodeWithText(app.getString(R.string.listen_together_tab_cloud)).assertIsSelected()
        compose.onNodeWithText(app.getString(R.string.listen_together_join_room)).assertExists()
    }
}
