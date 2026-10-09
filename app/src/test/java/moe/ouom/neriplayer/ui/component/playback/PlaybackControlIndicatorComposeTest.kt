package moe.ouom.neriplayer.ui.component.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackControlIndicatorComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val waitingSpinner = hasStateDescription("Waiting")

    private var isPlaying by mutableStateOf(false)
    private var isWaiting by mutableStateOf(false)
    private var isMuted by mutableStateOf(false)

    private fun setIndicator() {
        composeRule.setContent {
            PlaybackControlIndicator(
                isPlaying = isPlaying,
                isPlaybackWaiting = isWaiting,
                playContentDescription = "Play",
                pauseContentDescription = "Pause",
                waitingContentDescription = "Waiting",
                isAudioRouteMuted = isMuted,
                restoreVolumeContentDescription = "Restore volume"
            )
        }
    }

    @Test
    fun `indicator follows play pause and muted route states`() {
        setIndicator()
        composeRule.onNodeWithContentDescription("Play").assertExists()

        isPlaying = true
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Pause").assertExists()
        composeRule.onNodeWithContentDescription("Play").assertDoesNotExist()

        isMuted = true
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Restore volume").assertExists()
    }

    @Test
    fun `waiting spinner appears only after the buffering grace delay`() {
        composeRule.mainClock.autoAdvance = false
        setIndicator()

        isWaiting = true
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.onNodeWithContentDescription("Play").assertExists()
        composeRule.onNode(waitingSpinner).assertDoesNotExist()

        composeRule.mainClock.advanceTimeBy(1_500L)
        composeRule.onNode(waitingSpinner).assertExists()

        isWaiting = false
        composeRule.mainClock.advanceTimeBy(2_000L)
        composeRule.onNode(waitingSpinner).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Play").assertExists()
    }

    @Test
    fun `short buffering never shows the spinner`() {
        composeRule.mainClock.autoAdvance = false
        setIndicator()

        isWaiting = true
        composeRule.mainClock.advanceTimeBy(400L)
        isWaiting = false
        composeRule.mainClock.advanceTimeBy(2_000L)

        composeRule.onNode(waitingSpinner).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Play").assertExists()
    }
}
