package moe.ouom.neriplayer.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TouchInputBlockersTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun touchBlockerPreventsBlankOverlayTapsFromLeakingToBackground() {
        var backgroundClicks by mutableIntStateOf(0)
        var foregroundClicks by mutableIntStateOf(0)

        composeRule.setContent {
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .testTag("host")
            ) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable { backgroundClicks++ }
                )

                Box(modifier = Modifier.matchParentSize()) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .blockUnderlyingTouches()
                    )

                    Button(
                        onClick = { foregroundClicks++ },
                        modifier = Modifier
                            .align(Alignment.Center)
                            .testTag("foreground")
                    ) {
                        Text("Foreground")
                    }
                }
            }
        }

        composeRule.onNodeWithTag("host").performTouchInput {
            down(Offset(10f, 10f))
            up()
        }

        composeRule.runOnIdle {
            assertEquals(0, backgroundClicks)
            assertEquals(0, foregroundClicks)
        }

        composeRule.onNodeWithTag("foreground").performClick()

        composeRule.runOnIdle {
            assertEquals(0, backgroundClicks)
            assertEquals(1, foregroundClicks)
        }
    }
}
