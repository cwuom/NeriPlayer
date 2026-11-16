package moe.ouom.neriplayer.ui.screen.tab.settings.component.usb

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveNativeState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class UsbExclusiveSettingsRefreshTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val pageOwner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }
    private val playbackEnabled = mutableStateOf(true)
    private val nativeStates = MutableStateFlow(UsbExclusiveNativeState(opened = true, streaming = true))
    private val refreshes = mutableListOf<Boolean>()

    @Test
    fun `reporting frame progress renders without triggering another diagnostic refresh`() {
        render {
            if (refreshes.size == 1) {
                nativeStates.value = nativeStates.value.copy(completedAudioFrames = 48L)
            }
        }

        composeRule.onNodeWithText("frames: 48").assertExists()
        composeRule.runOnIdle { assertEquals(listOf(true), refreshes) }
    }

    @Test
    fun `diagnostics refresh immediately on resume and toggle then every second while resumed`() {
        render()
        composeRule.runOnIdle { assertEquals(listOf(true), refreshes) }

        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.runOnIdle { assertEquals(listOf(true, true), refreshes) }

        composeRule.runOnIdle {
            playbackEnabled.value = false
            Snapshot.sendApplyNotifications()
        }
        advanceComposition()
        composeRule.runOnIdle { assertEquals(listOf(true, true, false), refreshes) }

        composeRule.runOnIdle {
            pageOwner.lifecycle.currentState = Lifecycle.State.CREATED
            playbackEnabled.value = true
            Snapshot.sendApplyNotifications()
        }
        composeRule.mainClock.advanceTimeBy(2_000)
        composeRule.runOnIdle { assertEquals(listOf(true, true, false), refreshes) }

        composeRule.runOnIdle { pageOwner.lifecycle.currentState = Lifecycle.State.RESUMED }
        advanceComposition()
        composeRule.runOnIdle { assertEquals(listOf(true, true, false, true), refreshes) }
    }

    private fun render(afterRefresh: () -> Unit = {}) {
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread { pageOwner.lifecycle.currentState = Lifecycle.State.RESUMED }
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides pageOwner) {
                val nativeState = observeUsbExclusiveSettingsState(nativeStates, playbackEnabled.value) {
                    refreshes += playbackEnabled.value
                    afterRefresh()
                }
                Text("frames: ${nativeState.completedAudioFrames}")
            }
        }
        advanceComposition()
    }

    private fun advanceComposition() {
        composeRule.mainClock.advanceTimeBy(64)
        composeRule.waitForIdle()
    }
}
