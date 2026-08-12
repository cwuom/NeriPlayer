package moe.ouom.neriplayer.ui.screen.artist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class YouTubeMusicCreatorScrollStateTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun creatorRoundTripRestoresVerticalAndHorizontalPositions() {
        lateinit var showPlaylist: (Boolean) -> Unit
        lateinit var scrollVertical: (Int, Int) -> Unit
        lateinit var scrollHorizontal: (Int, Int) -> Unit
        lateinit var readPositions: () -> ScrollPositions
        composeRule.setContent {
            CreatorScrollStateFixture(
                onShowPlaylistChange = { showPlaylist = it },
                onScrollVertical = { scrollVertical = it },
                onScrollHorizontal = { scrollHorizontal = it },
                onReadPositions = { readPositions = it }
            )
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle { scrollHorizontal(8, 13) }
        composeRule.waitForIdle()
        composeRule.runOnIdle { scrollVertical(12, 17) }
        composeRule.waitForIdle()
        composeRule.runOnIdle { showPlaylist(true) }
        composeRule.waitForIdle()
        composeRule.runOnIdle { showPlaylist(false) }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            val positions = readPositions()
            assertEquals(12, positions.verticalIndex)
            assertEquals(17, positions.verticalOffset)
            assertEquals(8, positions.horizontalIndex)
            assertEquals(13, positions.horizontalOffset)
        }
    }
}

private data class ScrollPositions(
    val verticalIndex: Int,
    val verticalOffset: Int,
    val horizontalIndex: Int,
    val horizontalOffset: Int
)

@Composable
private fun CreatorScrollStateFixture(
    onShowPlaylistChange: ((Boolean) -> Unit) -> Unit,
    onScrollVertical: (((Int, Int) -> Unit)) -> Unit,
    onScrollHorizontal: (((Int, Int) -> Unit)) -> Unit,
    onReadPositions: (() -> ScrollPositions) -> Unit
) {
    var playlistVisible by remember { mutableStateOf(false) }
    val stateHolder = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()

    SideEffect {
        onShowPlaylistChange { visible -> playlistVisible = visible }
    }
    if (playlistVisible) {
        Box(Modifier.fillMaxSize())
        return
    }

    stateHolder.SaveableStateProvider("creator") {
        val verticalState = rememberSaveable(saver = LazyListState.Saver) {
            LazyListState()
        }
        val horizontalState = rememberSaveable(saver = LazyListState.Saver) {
            LazyListState()
        }
        SideEffect {
            onScrollVertical { index, offset ->
                scope.launch { verticalState.scrollToItem(index, offset) }
            }
            onScrollHorizontal { index, offset ->
                scope.launch { horizontalState.scrollToItem(index, offset) }
            }
            onReadPositions {
                ScrollPositions(
                    verticalIndex = verticalState.firstVisibleItemIndex,
                    verticalOffset = verticalState.firstVisibleItemScrollOffset,
                    horizontalIndex = horizontalState.firstVisibleItemIndex,
                    horizontalOffset = horizontalState.firstVisibleItemScrollOffset
                )
            }
        }
        Column(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                state = verticalState
            ) {
                item(key = "horizontal-section") {
                    LazyRow(state = horizontalState, modifier = Modifier.height(96.dp)) {
                        items((0..30).toList()) {
                            Box(Modifier.width(96.dp).height(96.dp))
                        }
                    }
                }
                items((0..30).toList()) { item ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                        )
                }
            }
        }
    }
}
