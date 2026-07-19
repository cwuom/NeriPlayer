package moe.ouom.neriplayer.ui.component.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NeriMiniPlayerLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun miniPlayerKeepsFullAvailableWidth() {
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    NeriMiniPlayer(
                        title = "Song",
                        artist = "Artist",
                        coverUrl = null,
                        isPlaying = false,
                        modifier = Modifier.testTag(MiniPlayerTag),
                        onPlayPause = {},
                        onPrevious = {},
                        onNext = {},
                        onExpand = {},
                        enableBlur = false
                    )
                }
            }
        }

        val width = composeRule.onNodeWithTag(MiniPlayerTag)
            .fetchSemanticsNode()
            .boundsInRoot
            .width
        val rootWidth = composeRule.onRoot()
            .fetchSemanticsNode()
            .boundsInRoot
            .width

        assertTrue("MiniPlayer width collapsed to $width/$rootWidth", width > rootWidth * 0.8f)
    }

    @Test
    fun miniPlayerKeepsSquareBottomCornersForBottomBarConnection() {
        composeRule.setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    background = Color.Red,
                    secondaryContainer = Color.Blue
                )
            ) {
                Box(
                    modifier = Modifier
                        .size(TestWidth, NeriMiniPlayerDefaults.Height)
                        .background(Color.Red)
                ) {
                    NeriMiniPlayer(
                        title = "Song",
                        artist = "Artist",
                        coverUrl = null,
                        isPlaying = false,
                        modifier = Modifier.testTag(MiniPlayerTag),
                        onPlayPause = {},
                        onPrevious = {},
                        onNext = {},
                        onExpand = {},
                        enableBlur = false
                    )
                }
            }
        }

        val image = composeRule.onNodeWithTag(MiniPlayerTag).captureToImage()
        val pixels = image.toPixelMap()
        val edgeOffset = (image.height / NeriMiniPlayerDefaults.Height.value.toInt())
            .coerceAtLeast(2)
        val bottomCorner = pixels[
            edgeOffset,
            image.height - edgeOffset - 1
        ]

        assertTrue(
            "MiniPlayer bottom corner no longer connects to the bottom bar: $bottomCorner",
            bottomCorner.blue > 0.8f && bottomCorner.red < 0.2f
        )
    }

    private companion object {
        const val MiniPlayerTag = "mini_player"
        val TestWidth = 240.dp
    }
}
