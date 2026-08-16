package moe.ouom.neriplayer.ui.component.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

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
    fun miniPlayerKeepsContainedHeightForLargeSystemFontScale() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
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
        }

        val height = composeRule.onNodeWithTag(MiniPlayerTag)
            .fetchSemanticsNode()
            .boundsInRoot
            .height

        assertEquals(NeriMiniPlayerDefaults.Height.value, height, 0.5f)
    }

    @Test
    fun miniPlayerLongTitleEllipsizesBeforeItBecomesSmallerThanArtist() {
        val titleLayout = AtomicReference<TextLayoutResult?>()
        val artistLayout = AtomicReference<TextLayoutResult?>()

        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                MaterialTheme {
                    Column(Modifier.width(120.dp)) {
                        EllipsizingMiniPlayerText(
                            text = "这是一个特别特别长的歌曲标题",
                            style = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
                            color = Color.Black,
                            maxLineHeightDp = 24f,
                            minVisualFontSizeSp = 10f,
                            onTextLayout = titleLayout::set
                        )
                        AutoSizingMiniPlayerText(
                            text = "Artist",
                            style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
                            color = Color.Black,
                            maxLineHeightDp = 20f,
                            minVisualFontSizeSp = 9f,
                            onTextLayout = artistLayout::set
                        )
                    }
                }
            }
        }

        val resolvedTitleLayout = titleLayout.get()
        val resolvedArtistLayout = artistLayout.get()
        assertNotNull("Title layout was not reported", resolvedTitleLayout)
        assertNotNull("Artist layout was not reported", resolvedArtistLayout)
        assertTrue(resolvedTitleLayout!!.isLineEllipsized(0))
        assertFalse(resolvedArtistLayout!!.isLineEllipsized(0))
        assertTrue(
            resolvedTitleLayout.layoutInput.style.fontSize.value >=
                resolvedArtistLayout.layoutInput.style.fontSize.value
        )
        assertTrue(resolvedTitleLayout.layoutInput.style.fontSize.value <= 8.1f)
        assertTrue(resolvedArtistLayout.layoutInput.style.fontSize.value <= 7.1f)
        assertTrue(resolvedTitleLayout.size.height <= 24)
        assertTrue(resolvedArtistLayout.size.height <= 20)
    }

    @Test
    fun miniPlayerUsesSymmetricHorizontalInsets() {
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
                        .testTag(RootTag)
                ) {
                    NeriMiniPlayer(
                        title = "Song",
                        artist = "Artist",
                        coverUrl = null,
                        isPlaying = false,
                        onPlayPause = {},
                        onPrevious = {},
                        onNext = {},
                        onExpand = {},
                        enableBlur = false
                    )
                }
            }
        }

        val image = composeRule.onNodeWithTag(RootTag).captureToImage()
        val pixels = image.toPixelMap()
        val centerY = image.height / 2
        val surfacePixels = (0 until image.width).filter { x ->
            val pixel = pixels[x, centerY]
            pixel.blue > 0.8f && pixel.red < 0.2f
        }

        assertTrue("MiniPlayer surface was not rendered", surfacePixels.isNotEmpty())
        val leftInset = surfacePixels.first()
        val rightInset = image.width - surfacePixels.last() - 1
        assertTrue(
            "MiniPlayer horizontal insets differ: left=$leftInset, right=$rightInset",
            kotlin.math.abs(leftInset - rightInset) <= 1
        )
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
        const val RootTag = "mini_player_root"
        val TestWidth = 240.dp
    }
}
