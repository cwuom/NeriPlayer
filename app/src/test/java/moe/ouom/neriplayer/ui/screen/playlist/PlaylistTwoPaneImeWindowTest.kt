package moe.ouom.neriplayer.ui.screen.playlist

import android.app.Application
import android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
import android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 回归 #748: 横屏双栏歌单页的侧栏搜索框不能触发整窗平移 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1280dp-h800dp-land")
class PlaylistTwoPaneImeWindowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Suppress("DEPRECATION")
    private val adjustResize = android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE

    private val windowAdjust: Int
        get() = composeRule.activity.window.attributes.softInputMode and SOFT_INPUT_MASK_ADJUST

    @Test
    fun `two pane playlist resizes for the ime and restores pan when it leaves`() {
        var showPage by mutableStateOf(true)
        composeRule.setContent {
            if (showPage) PlaylistBody(twoPane = true)
        }
        composeRule.waitForIdle()
        assertEquals(adjustResize, windowAdjust)

        showPage = false
        composeRule.waitForIdle()
        assertEquals(SOFT_INPUT_ADJUST_PAN, windowAdjust)
    }

    @Test
    fun `overlapping playlist pages keep resize until the last one leaves`() {
        var showFirst by mutableStateOf(true)
        var showSecond by mutableStateOf(true)
        composeRule.setContent {
            if (showFirst) PlaylistBody(twoPane = true)
            if (showSecond) PlaylistBody(twoPane = true)
        }
        composeRule.waitForIdle()

        showFirst = false
        composeRule.waitForIdle()
        assertEquals(adjustResize, windowAdjust)

        showSecond = false
        composeRule.waitForIdle()
        assertEquals(SOFT_INPUT_ADJUST_PAN, windowAdjust)
    }

    @Test
    fun `single column playlist leaves the window soft input mode alone`() {
        val before = windowAdjust
        composeRule.setContent { PlaylistBody(twoPane = false) }
        composeRule.waitForIdle()
        assertEquals(before, windowAdjust)
    }

    @androidx.compose.runtime.Composable
    private fun PlaylistBody(twoPane: Boolean) {
        PlaylistTwoPaneBody(
            twoPane = twoPane,
            pane = { Text("pane") }
        ) {
            Box { Text("list") }
        }
    }
}
