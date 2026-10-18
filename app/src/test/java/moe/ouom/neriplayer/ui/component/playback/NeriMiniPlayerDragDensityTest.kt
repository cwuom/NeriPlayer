package moe.ouom.neriplayer.ui.component.playback

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp")
class NeriMiniPlayerDragDensityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun slowUpwardSwipeDoesNotBecomeAFlingOnMdpi() = slowSwipe(density = 1f)

    @Test
    fun slowUpwardSwipeDoesNotBecomeAFlingOnXxhdpi() = slowSwipe(density = 3f)

    private fun slowSwipe(density: Float) {
        var expansions = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density)) {
                NeriMiniPlayer(
                    title = "Song",
                    artist = "Artist",
                    coverUrl = null,
                    isPlaying = false,
                    modifier = Modifier.testTag("mini"),
                    onPlayPause = {},
                    onPrevious = {},
                    onNext = {},
                    onExpand = { expansions++ },
                    enableBlur = false
                )
            }
        }
        composeRule.onNodeWithTag("mini").performTouchInput {
            val start = center
            down(start)
            repeat(10) { step ->
                moveTo(start + Offset(0f, -(step + 1) * 5f * density), delayMillis = 10)
            }
            up()
        }

        composeRule.runOnIdle { assertEquals(0, expansions) }
    }
}
