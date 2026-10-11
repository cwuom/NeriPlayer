package moe.ouom.neriplayer.ui.component.playback

import android.app.Application
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R as CoreCommonR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlaybackTitleAndBadgeComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val titleStyle = TextStyle(fontSize = 20.sp)

    @Test
    fun `source badge shows the icon and label of each playback source`() {
        var source by mutableStateOf(PlaybackSourceType.NETEASE)
        composeRule.setContent { PlaybackSourceBadge(source = source) }
        val expected = listOf(
            Triple(PlaybackSourceType.NETEASE, CoreCommonR.string.cd_netease, CoreCommonR.string.nowplaying_netease_cloud),
            Triple(PlaybackSourceType.BILIBILI, CoreCommonR.string.cd_bilibili, CoreCommonR.string.nowplaying_bilibili),
            Triple(PlaybackSourceType.YOUTUBE_MUSIC, CoreCommonR.string.common_youtube, CoreCommonR.string.nowplaying_youtube_music),
            Triple(PlaybackSourceType.LOCAL, CoreCommonR.string.local_files, CoreCommonR.string.local_files)
        )

        expected.forEach { (type, iconDescription, label) ->
            source = type
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription(context.getString(iconDescription)).assertExists()
            composeRule.onNodeWithText(context.getString(label)).assertExists()
        }
    }

    @Test
    fun `title without marquee stays put`() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            NowPlayingSongTitle(
                text = LONG_TITLE,
                marqueeEnabled = false,
                style = titleStyle,
                modifier = Modifier.width(80.dp)
            )
        }
        composeRule.mainClock.advanceTimeBy(100L)
        val start = titleLeft()

        composeRule.mainClock.advanceTimeBy(3_000L)

        assertEquals(start, titleLeft(), 0f)
    }

    @Test
    fun `marquee title that fits does not move`() {
        setMarqueeTitle("Short")
        val start = titleLeft("Short")

        composeRule.mainClock.advanceTimeBy(3_000L)

        assertEquals(start, titleLeft("Short"), 0f)
    }

    @Test
    fun `marquee title that overflows scrolls after the initial pause`() {
        setMarqueeTitle(LONG_TITLE)
        val start = titleLeft()

        composeRule.mainClock.advanceTimeBy(500L)
        assertEquals(start, titleLeft(), 0f)
        composeRule.mainClock.advanceTimeBy(2_000L)
        val scrolled = titleLeft()

        assertTrue("title should scroll left: $start -> $scrolled", scrolled < start)
    }

    private fun setMarqueeTitle(text: String) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            NowPlayingSongTitle(
                text = text,
                marqueeEnabled = true,
                style = titleStyle,
                modifier = Modifier.width(240.dp)
            )
        }
        composeRule.mainClock.advanceTimeBy(100L)
    }

    private fun titleLeft(text: String = LONG_TITLE): Float =
        composeRule.onNodeWithText(text).fetchSemanticsNode().positionInRoot.x

    private companion object {
        const val LONG_TITLE = "An exceptionally long song title that cannot fit on one line"
    }
}
