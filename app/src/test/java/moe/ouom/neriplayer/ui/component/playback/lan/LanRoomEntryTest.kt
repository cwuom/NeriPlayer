package moe.ouom.neriplayer.ui.component.playback.lan

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [32], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LanRoomEntryTest {
    @get:Rule val compose = createComposeRule()
    @Test fun createAndJoinAreVisibleAndActiveRoomUsesManage() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val active = mutableStateOf(false)
        var creates = 0; var joins = 0; var manages = 0
        compose.setContent { MaterialTheme { LanRoomEntryActions(active.value, { creates++ }, { joins++ }, { manages++ }) } }
        compose.onNodeWithText(app.getString(R.string.lan_create)).performClick()
        compose.onNodeWithText(app.getString(R.string.lan_join_room)).performClick()
        compose.runOnIdle { assertEquals(1, creates); assertEquals(1, joins); active.value = true }
        compose.onNodeWithText(app.getString(R.string.lan_manage)).performClick()
        compose.onNodeWithText(app.getString(R.string.lan_create)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, manages) }
    }
}
