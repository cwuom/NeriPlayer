package moe.ouom.neriplayer.ui.component.common

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeRevealOverlayTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun interruptedRevealReleasesTheThemeChangeLock() {
        lateinit var visible: MutableState<Boolean>
        var finishCallbacks = 0
        val autoAdvance = composeRule.mainClock.autoAdvance
        composeRule.mainClock.autoAdvance = false

        try {
            composeRule.setContent {
                visible = remember { mutableStateOf(true) }
                if (visible.value) {
                    ThemeRevealOverlay(
                        snapshot = null,
                        fallbackColor = Color.Black,
                        originInWindow = Offset.Zero,
                        durationMillis = 10_000,
                        onFinished = { finishCallbacks += 1 }
                    )
                }
            }

            composeRule.mainClock.advanceTimeByFrame()
            composeRule.runOnIdle { visible.value = false }
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                assertEquals(1, finishCallbacks)
            }
        } finally {
            composeRule.mainClock.autoAdvance = autoAdvance
        }
    }
}
