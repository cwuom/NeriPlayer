package moe.ouom.neriplayer.ui.component.playback.lan

import android.app.Application
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LanGroupVolumeSliderTest {
    @get:Rule val compose = createComposeRule()
    private val roomVolume = mutableFloatStateOf(0.25f)
    private val enabled = mutableStateOf(true)
    private val sent = mutableListOf<Float>()
    private val scroll = ScrollState(0)
    private val slider get() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))

    private fun showSlider() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(240.dp).height(180.dp)) {
                    Column(Modifier.verticalScroll(scroll)) {
                        LanGroupVolumeSlider(roomVolume.floatValue, enabled.value, sent::add)
                        Spacer(Modifier.height(480.dp))
                    }
                }
            }
        }
    }

    private fun shownVolume() = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current

    @Test
    fun tapLongPressAndSmallMotionDoNotChangeTheRoom() {
        showSlider()
        slider.performTouchInput {
            click(Offset(width * 0.9f, centerY))
            longClick(Offset(width * 0.8f, centerY))
            down(center)
            moveBy(Offset(1f, 1f))
            up()
        }
        assertEquals(0.25f, shownVolume(), 0f)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun horizontalDragPreviewsLocallyAndCommitsOnceOnRelease() {
        showSlider()
        slider.performTouchInput {
            down(Offset(width * 0.2f, centerY))
            moveTo(Offset(width * 0.65f, centerY))
        }
        val preview = shownVolume()
        assertTrue(preview > 0.5f)
        assertTrue(sent.isEmpty())
        compose.runOnIdle { roomVolume.floatValue = 0.15f }
        assertEquals(preview, shownVolume(), 0f)
        slider.performTouchInput { up() }
        assertEquals(listOf(preview), sent)
        assertEquals(preview, shownVolume(), 0f)
        compose.runOnIdle { roomVolume.floatValue = 0.4f }
        assertEquals(0.4f, shownVolume(), 0f)
    }

    @Test
    fun cancellingDragRestoresLatestRoomValueWithoutSending() {
        showSlider()
        slider.performTouchInput {
            down(Offset(width * 0.2f, centerY))
            moveTo(Offset(width * 0.7f, centerY))
        }
        assertTrue(shownVolume() > 0.5f)
        compose.runOnIdle { roomVolume.floatValue = 0.1f }
        slider.performTouchInput { cancel() }
        assertEquals(0.1f, shownVolume(), 0f)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun revokingPermissionDuringDragDiscardsPreviewAndKeepsShowingRoomUpdates() {
        showSlider()
        slider.performTouchInput {
            down(Offset(width * 0.2f, centerY))
            moveTo(Offset(width * 0.7f, centerY))
        }
        assertTrue(shownVolume() > 0.5f)
        compose.runOnIdle { enabled.value = false; roomVolume.floatValue = 0.1f }
        slider.assertIsNotEnabled()
        assertEquals(0.1f, shownVolume(), 0f)
        slider.performTouchInput { up() }
        val setProgress = slider.fetchSemanticsNode().config.getOrNull(SemanticsActions.SetProgress)?.action
        compose.runOnIdle { setProgress?.invoke(0.9f) }
        slider.performTouchInput {
            down(center)
            moveBy(Offset(width * 0.4f, 0f))
            up()
        }
        assertTrue(sent.isEmpty())
        assertEquals(0.1f, shownVolume(), 0f)
        compose.runOnIdle { enabled.value = true }
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0.6f) }
        assertEquals(listOf(0.6f), sent)
    }

    @Test
    fun mostlyVerticalDragScrollsTheRoomWithoutChangingVolume() {
        showSlider()
        slider.performTouchInput {
            down(center)
            moveBy(Offset(width * 0.05f, -height * 2f))
            moveBy(Offset(0f, -height.toFloat()))
            up()
        }
        compose.runOnIdle { assertTrue(scroll.value > 0) }
        assertEquals(0.25f, shownVolume(), 0f)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun accessibilityAndKeyboardCanStillAdjustTheRoom() {
        showSlider()
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0.6f) }
        assertEquals(listOf(0.6f), sent)
        slider.performSemanticsAction(SemanticsActions.RequestFocus)
        slider.performKeyInput { pressKey(Key.DirectionRight) }
        assertEquals(2, sent.size)
        assertTrue(sent.last() > 0.6f)
    }
}
