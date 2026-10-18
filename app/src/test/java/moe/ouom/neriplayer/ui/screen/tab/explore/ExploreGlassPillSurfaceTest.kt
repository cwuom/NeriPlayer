package moe.ouom.neriplayer.ui.screen.tab.explore

import android.app.Application
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExploreGlassPillSurfaceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun defaultPillKeepsAMinimumTouchTargetAndDispatchesOneClick() {
        var clicks = 0
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ExploreGlassPillSurface(
                        fallbackColor = Color.Black,
                        tintColor = Color.Black,
                        contentColor = Color.White,
                        onClick = { clicks++ }
                    ) {
                        Box(Modifier.size(24.dp, 20.dp))
                    }
                }
            }
        }

        val pill = composeRule.onNode(hasClickAction())
        val pillBounds = pill.fetchSemanticsNode().boundsInRoot
        val root = composeRule.onRoot()
        val rootBounds = root.fetchSemanticsNode().boundsInRoot
        val outsideContent = Offset(
            x = pillBounds.left - with(composeRule.density) { 8.dp.toPx() },
            y = pillBounds.center.y
        ) - rootBounds.topLeft
        // 点击内容边缘外、48 dp 最小触区内，验证实际命中而非语义尺寸
        root.performTouchInput { click(outsideContent) }

        composeRule.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun customPillPreservesItsModifierAndContentColor() {
        var clicks = 0
        var observedContentColor: Color? = null
        composeRule.setContent {
            MaterialTheme {
                ExploreGlassPillSurface(
                    fallbackColor = Color.Black,
                    tintColor = Color.Black,
                    contentColor = Color.Green,
                    onClick = { clicks++ },
                    border = BorderStroke(4.dp, Color.Red),
                    modifier = Modifier.testTag("customPill").size(120.dp, 64.dp)
                ) {
                    val contentColor = LocalContentColor.current
                    SideEffect { observedContentColor = contentColor }
                    Box(Modifier.size(80.dp, 32.dp))
                }
            }
        }

        val pill = composeRule.onNodeWithTag("customPill")
        pill.assertWidthIsEqualTo(120.dp).assertHeightIsEqualTo(64.dp)
        composeRule.runOnIdle { assertEquals(Color.Green, observedContentColor) }

        pill.performClick()
        composeRule.runOnIdle { assertEquals(1, clicks) }
    }
}
