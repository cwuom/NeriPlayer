package moe.ouom.neriplayer.ui.component.playback.lan

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.data.model.lan.LanPermissions
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
    @Test fun compactPermissionsToggleIndependently() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val permissions = mutableStateOf(LanPermissions())
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(200.dp)) {
                    LanPermissionsRow(permissions.value) { permissions.value = it }
                }
            }
        }
        compose.onAllNodes(hasClickAction()).assertCountEquals(3)
        val play = compose.onNodeWithText(app.getString(R.string.lan_permission_play_short))
        val seek = compose.onNodeWithText(app.getString(R.string.lan_permission_seek_short))
        val volume = compose.onNodeWithText(app.getString(R.string.lan_permission_volume_short))
        play.assertIsSelected()
        seek.assertIsSelected()
        volume.assertIsDisplayed().assertIsNotSelected()
        volume.performClick().assertIsSelected()
        seek.performClick().assertIsNotSelected()
        compose.runOnIdle { assertEquals(LanPermissions(seek = false, volume = true), permissions.value) }
        play.performClick().assertIsNotSelected()
        seek.performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(LanPermissions(playPause = false, volume = true), permissions.value) }
        play.performClick().assertIsSelected()
        volume.performClick().assertIsNotSelected()
        compose.runOnIdle { assertEquals(LanPermissions(), permissions.value) }
    }
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
