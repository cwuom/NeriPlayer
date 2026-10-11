package moe.ouom.neriplayer.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppMiniPlayerTabletPositionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val position = MutableStateFlow(1_000L)
    private var visible by mutableStateOf(true)
    private var compositions = 0

    private fun render() {
        composeRule.setContent {
            val current = playbackPositionWhileVisible(visible, position)
            SideEffect { compositions++ }
            Text("position $current")
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `visible mini player follows position updates`() {
        render()
        composeRule.onNodeWithText("position 1000").assertExists()

        position.value = 2_000L
        composeRule.waitForIdle()

        composeRule.onNodeWithText("position 2000").assertExists()
    }

    @Test
    fun `hidden mini player ignores position ticks without recomposing`() {
        render()
        composeRule.runOnIdle { visible = false }
        composeRule.waitForIdle()
        val hiddenCompositions = compositions

        repeat(5) { tick ->
            position.value = 3_000L + tick
            composeRule.waitForIdle()
        }

        assertEquals(hiddenCompositions, compositions)
        composeRule.onNodeWithText("position 1000").assertExists()
    }

    @Test
    fun `showing the mini player again starts from the latest position`() {
        render()
        composeRule.runOnIdle { visible = false }
        composeRule.waitForIdle()
        position.value = 9_000L
        composeRule.waitForIdle()

        composeRule.runOnIdle { visible = true }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("position 9000").assertExists()
    }
}
