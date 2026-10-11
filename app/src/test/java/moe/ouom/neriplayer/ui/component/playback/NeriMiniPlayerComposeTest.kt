package moe.ouom.neriplayer.ui.component.playback

import android.content.Context
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R as CoreCommonR
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class NeriMiniPlayerComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val events = mutableListOf<String>()
    private val seeks = mutableListOf<Long>()

    private fun tabletControls(seekEnabled: Boolean = true) = MiniPlayerTabletControls(
        trackKey = "track",
        positionMs = 60_000L,
        durationMs = 180_000L,
        seekEnabled = seekEnabled,
        shuffleEnabled = true,
        repeatMode = Player.REPEAT_MODE_ONE,
        onSeek = { seeks += it },
        onShuffle = { events += "shuffle" },
        onRepeat = { events += "repeat" },
        onVolume = { events += "volume" },
        onListenTogether = { events += "listenTogether" },
        onQueue = { events += "queue" }
    )

    private fun setPlayer(
        width: Dp? = null,
        isPlaying: Boolean = false,
        playPauseEnabled: Boolean = true,
        coverUrl: String? = null,
        tabletControls: MiniPlayerTabletControls? = null
    ) {
        composeRule.setContent {
            NeriMiniPlayer(
                title = "Song Title",
                artist = "Song Artist",
                coverUrl = coverUrl,
                isPlaying = isPlaying,
                modifier = (if (width != null) Modifier.width(width) else Modifier).testTag("mini"),
                playPauseEnabled = playPauseEnabled,
                onPlayPause = { events += "playPause" },
                onPrevious = { events += "previous" },
                onNext = { events += "next" },
                onExpand = { events += "expand" },
                enableBlur = false,
                tabletControls = tabletControls
            )
        }
    }

    private fun string(id: Int) = context.getString(id)

    @Test
    fun `phone player shows metadata and routes play and expand taps`() {
        setPlayer(isPlaying = true)

        composeRule.onNodeWithText("Song Title").assertExists()
        composeRule.onNodeWithText("Song Artist").assertExists()
        composeRule.onNodeWithContentDescription(string(CoreCommonR.string.lyrics_pause)).assertExists()
        composeRule.onNodeWithTag("miniPlayerPrevious").assertDoesNotExist()

        composeRule.onNodeWithTag("miniPlayerPlayPause").performClick()
        composeRule.onNodeWithTag("miniPlayerMetadata", useUnmergedTree = true).performClick()

        assertEquals(listOf("playPause", "expand"), events)
    }

    @Test
    fun `disabled play button ignores taps`() {
        setPlayer(playPauseEnabled = false, coverUrl = "file:///missing/cover.jpg")

        composeRule.onNodeWithTag("miniPlayerPlayPause").assertIsNotEnabled().performClick()

        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `horizontal swipes skip tracks only past the threshold`() {
        setPlayer()

        composeRule.onNodeWithTag("mini").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("mini").performTouchInput { swipeRight() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("mini").performTouchInput {
            swipeRight(startX = centerX, endX = centerX + 20f)
        }
        composeRule.waitForIdle()

        assertEquals(listOf("next", "previous"), events)
    }

    @Test
    @Config(qualifiers = "sw600dp-w800dp-h1200dp")
    fun `wide screens show dedicated skip buttons`() {
        setPlayer()

        composeRule.onNodeWithTag("miniPlayerPrevious").performClick()
        composeRule.onNodeWithTag("miniPlayerNext").performClick()

        assertEquals(listOf("previous", "next"), events)
    }

    @Test
    @Config(qualifiers = "sw800dp-w1280dp-h800dp")
    fun `full tablet layout exposes every control and seeks from the progress bar`() {
        setPlayer(width = 1_000.dp, tabletControls = tabletControls())

        composeRule.onNodeWithText("01:00 / 03:00").assertExists()
        listOf(
            "miniPlayerShuffle",
            "miniPlayerPrevious",
            "miniPlayerPlayPause",
            "miniPlayerNext",
            "miniPlayerRepeat",
            "miniPlayerVolume",
            "miniPlayerListenTogether",
            "miniPlayerQueue",
            "miniPlayerExpand"
        ).forEach { tag -> composeRule.onNodeWithTag(tag).performClick() }
        composeRule.onNodeWithTag("miniPlayerProgress")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }

        assertEquals(
            listOf(
                "shuffle",
                "previous",
                "playPause",
                "next",
                "repeat",
                "volume",
                "listenTogether",
                "queue",
                "expand"
            ),
            events
        )
        assertEquals(listOf(90_000L), seeks)
    }

    @Test
    @Config(qualifiers = "sw800dp-w1280dp-h800dp")
    fun `overflow tablet layout moves secondary actions into the menu`() {
        setPlayer(width = 520.dp, tabletControls = tabletControls())

        composeRule.onNodeWithTag("miniPlayerShuffle").assertDoesNotExist()
        composeRule.onNodeWithTag("miniPlayerOverflow").performClick()
        composeRule.onNodeWithText(string(CoreCommonR.string.player_shuffle)).performClick()
        composeRule.onNodeWithTag("miniPlayerOverflow").performClick()
        composeRule.onNodeWithText(string(CoreCommonR.string.playlist_queue)).performClick()

        assertEquals(listOf("shuffle", "queue"), events)
    }

    @Test
    @Config(qualifiers = "sw800dp-w1280dp-h800dp")
    fun `minimal tablet layout keeps skipping available from the menu`() {
        setPlayer(width = 400.dp, tabletControls = tabletControls(seekEnabled = false))

        composeRule.onNodeWithTag("miniPlayerPrevious").assertDoesNotExist()
        composeRule.onNodeWithTag("miniPlayerOverflow").performClick()
        composeRule.onNodeWithText(string(CoreCommonR.string.player_previous)).performClick()
        composeRule.onNodeWithTag("miniPlayerOverflow").performClick()
        composeRule.onNodeWithText(string(CoreCommonR.string.player_now_playing)).performClick()
        composeRule.onNodeWithTag("miniPlayerProgress").performClick()

        assertEquals(listOf("previous", "expand"), events)
        assertEquals(emptyList<Long>(), seeks)
    }
}
