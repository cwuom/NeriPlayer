package moe.ouom.neriplayer.ui.component.playback.lan

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R
import moe.ouom.neriplayer.data.model.lan.LanMember
import moe.ouom.neriplayer.data.model.lan.LanRoom
import moe.ouom.neriplayer.data.model.lan.LanSessionState
import moe.ouom.neriplayer.lanltw.session.LanSessionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LanDeviceVolumeNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun memberGrantEnablesTheGroupAndHostSliderButKeepsOtherDevicesReadOnly() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val manager = mock(LanSessionManager::class.java)
        val host = LanMember("host", "Host", host = true, volume = 0.8f)
        val self = LanMember("self", "Self", volume = 0.2f)
        val peer = LanMember("peer", "Peer", volume = 0.3f)
        val room = LanRoom("room", "Test room", listOf(host, self, peer), volume = 0.6f)
        val state = mutableStateOf(LanSessionState(phase = "CONNECTED", synced = true, selfId = self.id, room = room))
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) { LanRoomPanelContent(manager, state.value) }
            }
        }
        fun sliderAt(value: Float) = compose.onNode(SemanticsMatcher("volume $value") {
            it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.current == value
        })
        sliderAt(room.volume).assertIsNotEnabled()
        compose.onNodeWithText(app.getString(R.string.lan_permissions)).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(room = room.copy(permissions = room.permissions.copy(volume = true))) }
        sliderAt(room.volume).assertIsEnabled().performSemanticsAction(SemanticsActions.SetProgress) { it(0.7f) }
        verify(manager).setVolume(0.7f)
        compose.onNodeWithText(app.getString(R.string.lan_device_volume)).performClick()
        sliderAt(host.volume).assertIsEnabled().performSemanticsAction(SemanticsActions.SetProgress) { it(0.85f) }
        verify(manager).setVolume(0.85f, host.id)
        sliderAt(self.volume).assertIsEnabled()
        sliderAt(peer.volume).assertDoesNotExist()
        sliderAt(0.85f).performTouchInput {
            down(Offset(width * 0.85f, centerY))
            moveTo(Offset(width * 0.5f, centerY))
        }
        compose.runOnIdle { state.value = state.value.copy(room = room) }
        sliderAt(self.volume).performTouchInput { up() }
        sliderAt(0.85f).assertDoesNotExist()
        sliderAt(host.volume).assertDoesNotExist()
        sliderAt(self.volume).assertIsEnabled()
        sliderAt(peer.volume).assertDoesNotExist()
        verifyNoMoreInteractions(manager)
    }

    @Test
    fun shortDeviceListResizesInBothDirectionsAndSurvivesRoomUpdates() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val manager = mock(LanSessionManager::class.java)
        val member = LanMember("speaker", "Test speaker", volume = 0.5f)
        val room = LanRoom("room", "Test room", listOf(member))
        val state = mutableStateOf(LanSessionState(phase = "CONNECTED", selfId = member.id, room = room))
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Box(Modifier.testTag("room-panel")) { LanRoomPanelContent(manager, state.value) }
                }
            }
        }
        fun panelHeight() = compose.onNodeWithTag("room-panel").getUnclippedBoundsInRoot().let { it.bottom - it.top }
        val roomHeight = panelHeight()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(app.getString(R.string.lan_device_volume)).performClick()
        compose.mainClock.advanceTimeBy(112)
        val enteringHeight = panelHeight()
        compose.mainClock.advanceTimeBy(800)
        val detailHeight = panelHeight()
        assertTrue(enteringHeight < roomHeight && enteringHeight > detailHeight)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText(member.name).assertIsDisplayed()
        compose.onNodeWithText("50%").assertIsDisplayed()
        compose.runOnIdle {
            state.value = state.value.copy(rttMs = 11.0, room = room.copy(members = listOf(member.copy(volume = 0.75f))))
        }
        compose.onNodeWithText("75%").assertIsDisplayed()
        assertEquals(detailHeight, panelHeight())
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription(app.getString(R.string.lan_back_room)).performClick()
        compose.mainClock.advanceTimeBy(112)
        val returningHeight = panelHeight()
        assertTrue(returningHeight > detailHeight && returningHeight < roomHeight)
        compose.mainClock.advanceTimeBy(800)
        assertEquals(roomHeight, panelHeight())
        compose.onNodeWithText(member.name).assertDoesNotExist()
    }
}
