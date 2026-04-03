package moe.ouom.neriplayer.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.player.model.PlaybackEqualizerPresetId
import moe.ouom.neriplayer.core.player.model.PlaybackSoundState
import moe.ouom.neriplayer.core.player.model.defaultPlaybackEqualizerBands
import moe.ouom.neriplayer.core.player.model.formatPlaybackGainLabel
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackSoundSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun quickActions_dispatchExpectedCallbacks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val speedEvents = mutableListOf<Pair<Float, Boolean>>()
        val loudnessEvents = mutableListOf<Pair<Int, Boolean>>()
        val presetEvents = mutableListOf<String>()
        var resetCount = 0
        var dismissCount = 0

        composeRule.setContent {
            MaterialTheme {
                PlaybackSoundSheet(
                    state = PlaybackSoundState(
                        presetId = PlaybackEqualizerPresetId.CUSTOM,
                        bands = defaultPlaybackEqualizerBands(),
                        audioSessionId = 7,
                        equalizerAvailable = true,
                        loudnessEnhancerAvailable = true
                    ),
                    onSpeedChange = { value, persist -> speedEvents += value to persist },
                    onPitchChange = { _, _ -> },
                    onLoudnessGainChange = { value, persist -> loudnessEvents += value to persist },
                    onEqualizerEnabledChange = { },
                    onPresetSelected = { presetId -> presetEvents += presetId },
                    onBandLevelChange = { _, _, _ -> },
                    onReset = { resetCount++ },
                    onDismiss = { dismissCount++ }
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 3_000) {
            composeRule.onAllNodesWithText(
                context.getString(R.string.nowplaying_audio_effects_title)
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = 3_000) {
            composeRule.onAllNodesWithText("Custom").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("3.00x").performClick()
        composeRule.onNodeWithText(formatPlaybackGainLabel(1_200)).performScrollTo().performClick()
        composeRule.onNodeWithText("Rock").performScrollTo().performClick()
        composeRule.onNodeWithText(
            context.getString(R.string.nowplaying_audio_effects_reset)
        ).performScrollTo().performClick()
        composeRule.onNodeWithText(
            context.getString(R.string.action_done)
        ).performScrollTo().performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(3.0f to true), speedEvents)
            assertEquals(listOf(1_200 to true), loudnessEvents)
            assertEquals(listOf(PlaybackEqualizerPresetId.ROCK), presetEvents)
            assertEquals(1, resetCount)
            assertEquals(1, dismissCount)
        }
    }
}
