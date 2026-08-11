package moe.ouom.neriplayer.ui.screen.playlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistModernVisualColorsProviderLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun fixedSearchSlotDoesNotConsumeTheSongListViewport() {
        var rootHeight = 0
        var searchSlotHeight = 0
        var songListHeight = 0

        composeRule.setContent {
            MaterialTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { coordinates ->
                            rootHeight = coordinates.size.height
                        }
                ) {
                    PlaylistModernVisualColorsProvider(
                        coverUrl = null,
                        offlineMode = true
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp)
                                .onGloballyPositioned { coordinates ->
                                    searchSlotHeight = coordinates.size.height
                                }
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .onGloballyPositioned { coordinates ->
                                songListHeight = coordinates.size.height
                            }
                    )
                }
            }
        }

        composeRule.waitForIdle()

        assertTrue("test root was not laid out", rootHeight > 0)
        assertTrue("search slot was not laid out", searchSlotHeight > 0)
        assertTrue(
            "search slot consumed the whole viewport: root=$rootHeight, " +
                "slot=$searchSlotHeight",
            searchSlotHeight < rootHeight
        )
        assertTrue(
            "song list viewport was consumed by the search slot",
            songListHeight > 0
        )
    }
}
