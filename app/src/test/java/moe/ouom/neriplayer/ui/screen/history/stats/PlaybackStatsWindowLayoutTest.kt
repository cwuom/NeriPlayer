package moe.ouom.neriplayer.ui.screen.history.stats

import android.content.Context
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.util.currentWindowWidthDp
import moe.ouom.neriplayer.util.platform.PHONE_SMALLEST_SCREEN_WIDTH_DP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class PlaybackStatsWindowLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `tablet layout starts at a 720dp window`() {
        assertFalse(usePlaybackStatsTabletLayout(719.dp))
        assertTrue(usePlaybackStatsTabletLayout(720.dp))
        assertTrue(usePlaybackStatsTabletLayout(1280.dp))
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-land")
    fun `wide phone window uses the tablet layout even though the device is a phone`() {
        var smallestScreenWidthDp = 0
        var tablet = false
        composeRule.setContent {
            smallestScreenWidthDp = LocalConfiguration.current.smallestScreenWidthDp
            tablet = usePlaybackStatsTabletLayout(currentWindowWidthDp())
        }

        composeRule.runOnIdle {
            assertTrue(smallestScreenWidthDp < PHONE_SMALLEST_SCREEN_WIDTH_DP)
            assertTrue(tablet)
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `portrait phone window keeps the phone layout`() {
        var tablet = true
        composeRule.setContent {
            tablet = usePlaybackStatsTabletLayout(currentWindowWidthDp())
        }

        composeRule.runOnIdle { assertFalse(tablet) }
    }

    @Test
    fun `top bar actions are labelled and the sort menu reports the chosen mode`() {
        var sortMode = StatsSortMode.PLAY_COUNT
        var backCount = 0
        var clearCount = 0
        composeRule.setContent {
            PlaybackStatsTopBar(
                sortMode = sortMode,
                onSortModeChange = { sortMode = it },
                onBack = { backCount++ },
                onClearRequest = { clearCount++ }
            )
        }

        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.action_back))
            .performClick()
        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.stats_clear_title))
            .performClick()
        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.stats_sort_play_count))
            .performClick()
        StatsSortMode.entries.forEach { mode ->
            composeRule.onNodeWithText(context.getString(mode.labelRes)).assertIsDisplayed()
        }
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.stats_sort_recent))
            .performClick()

        assertEquals(1, backCount)
        assertEquals(1, clearCount)
        assertEquals(StatsSortMode.RECENT, sortMode)
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.stats_sort_first_played))
            .assertDoesNotExist()
    }
}
