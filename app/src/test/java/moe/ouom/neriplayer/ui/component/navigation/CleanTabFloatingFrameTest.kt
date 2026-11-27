package moe.ouom.neriplayer.ui.component.navigation

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CleanTabFloatingFrameTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun swellingLensNeverGrowsTheBarContainer() {
        val lens = mutableStateOf<IntRect?>(IntRect(10, 0, 110, 64))
        var container: IntSize? = null
        var lensCoordinates: LayoutCoordinates? = null
        composeRule.setContent {
            Box(Modifier.onGloballyPositioned { container = it.size }) {
                Box(Modifier.fillMaxWidth().height(64.dp))
                Box(
                    Modifier
                        .floatingFrame { lens.value }
                        .onGloballyPositioned { lensCoordinates = it }
                )
            }
        }
        composeRule.waitForIdle()
        assertNotNull(container)
        val rest = container

        // 按下后透镜比底栏高、并向上溢出，容器尺寸必须保持不变，底栏才不会被顶起
        lens.value = IntRect(0, -14, 140, 78)
        composeRule.waitForIdle()

        assertEquals(rest, container)
        val pressed = lensCoordinates
        assertNotNull(pressed)
        assertEquals(IntSize(140, 92), pressed!!.size)
        assertEquals(-14f, pressed.positionInParent().y, 0f)
    }

    @Test
    fun missingFrameIsNeverPlaced() {
        var placed = false
        composeRule.setContent {
            Box(Modifier.floatingFrame { null }.onGloballyPositioned { placed = true })
        }
        composeRule.waitForIdle()
        // 底栏还没完成布局时透镜不放置，也就不会画在左上角
        assertEquals(false, placed)
    }
}
