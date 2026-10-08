package moe.ouom.neriplayer.ui.component.playback

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WaveformSliderGestureTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val started = mutableListOf<Float>()
    private val changed = mutableListOf<Float>()
    private var finished = 0
    private var canceled = 0

    private fun setSlider(enabled: Boolean) {
        composeRule.setContent {
            WaveformSlider(
                value = 0.1f,
                onValueChange = { changed += it },
                onValueChangeFinished = { finished++ },
                onValueChangeStarted = { started += it },
                onValueChangeCanceled = { canceled++ },
                isPlaying = false,
                enabled = enabled,
                modifier = Modifier.width(300.dp).testTag("slider")
            )
        }
    }

    @Test
    fun `tapping seeks once to the tapped fraction`() {
        setSlider(enabled = true)

        composeRule.onNodeWithTag("slider").performTouchInput {
            click(Offset(width * 0.75f, centerY))
        }

        assertEquals(1, started.size)
        assertEquals(0.75f, started.single(), 0.02f)
        assertEquals(1, finished)
        assertTrue(changed.isEmpty())
        assertEquals(0, canceled)
    }

    @Test
    fun `disabled slider ignores taps`() {
        setSlider(enabled = false)

        composeRule.onNodeWithTag("slider").performTouchInput {
            click(Offset(width * 0.5f, centerY))
        }

        assertTrue(started.isEmpty())
        assertEquals(0, finished)
    }

    @Test
    fun `dragging still previews and finishes once`() {
        setSlider(enabled = true)

        composeRule.onNodeWithTag("slider").performTouchInput {
            swipeRight(startX = width * 0.2f, endX = width * 0.8f)
        }

        assertEquals(1, started.size)
        assertTrue(changed.isNotEmpty())
        assertEquals(0.8f, changed.last(), 0.05f)
        assertEquals(1, finished)
    }

    @Test
    fun `fraction is clamped and unavailable before layout`() {
        assertEquals(0f, sliderFractionAt(-10f, 100f))
        assertEquals(1f, sliderFractionAt(150f, 100f))
        assertEquals(0.25f, sliderFractionAt(25f, 100f))
        assertNull(sliderFractionAt(10f, 0f))
    }
}
