package moe.ouom.neriplayer.ui.screen.artist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicCreatorDetail
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicCreatorHeader
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicCreatorItem
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicCreatorItemType
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicCreatorSection
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicCreatorSummary
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.screen.host.youtubeMusicCreatorDetailStateKey
import moe.ouom.neriplayer.ui.viewmodel.artist.YouTubeMusicCreatorDetailUiState
import moe.ouom.neriplayer.ui.viewmodel.artist.YouTubeMusicCreatorDetailViewModel
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
            CreatorDetailNavigationFixture(
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
private fun CreatorDetailNavigationFixture(
    onShowPlaylistChange: ((Boolean) -> Unit) -> Unit,
    onScrollVertical: (((Int, Int) -> Unit)) -> Unit,
    onScrollHorizontal: (((Int, Int) -> Unit)) -> Unit,
    onReadPositions: (() -> ScrollPositions) -> Unit
) {
    var playlistVisible by remember { mutableStateOf(false) }
    val creator = remember {
        YouTubeMusicCreatorSummary(
            browseId = "UCdemoCreator",
            title = "Demo Creator",
            subtitle = "Artist",
            coverUrl = ""
        )
    }
    val detail = remember { creatorDetailFixture(creator) }
    val stateHolder = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    var horizontalState by remember { mutableStateOf<LazyListState?>(null) }

    androidx.compose.runtime.SideEffect {
        onShowPlaylistChange { visible -> playlistVisible = visible }
    }
    if (playlistVisible) {
        Box(Modifier.fillMaxSize())
        return
    }

    stateHolder.SaveableStateProvider(
        key = youtubeMusicCreatorDetailStateKey(creator)
    ) {
        val verticalState = rememberSaveable(saver = LazyListState.Saver) {
            LazyListState()
        }
        val sectionStateHolder = rememberSaveableStateHolder()
        androidx.compose.runtime.SideEffect {
            onScrollVertical { index, offset ->
                scope.launch { verticalState.scrollToItem(index, offset) }
            }
            onReadPositions {
                ScrollPositions(
                    verticalIndex = verticalState.firstVisibleItemIndex,
                    verticalOffset = verticalState.firstVisibleItemScrollOffset,
                    horizontalIndex = horizontalState?.firstVisibleItemIndex ?: 0,
                    horizontalOffset = horizontalState?.firstVisibleItemScrollOffset ?: 0
                )
            }
        }
        MaterialTheme {
            YouTubeMusicCreatorDetailScreen(
                creator = creator,
                onPlaylistClick = { playlistVisible = true },
                detailViewModelFactory = CreatorDetailTestViewModelFactory(detail),
                onSectionListState = { key, state ->
                    if (key == youtubeMusicCreatorSectionScrollStateKey(
                            creator.browseId,
                            detail.sections.first()
                        )
                    ) {
                        horizontalState = state
                        onScrollHorizontal { index, offset ->
                            scope.launch { state.scrollToItem(index, offset) }
                        }
                    }
                }
            )
        }
    }
}

private class CreatorDetailTestViewModelFactory(
    private val detail: YouTubeMusicCreatorDetail
) : ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(
        modelClass: Class<T>,
        extras: CreationExtras
    ): T {
        val application = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
            ?: error("application is required")
        return modelClass.cast(YouTubeMusicCreatorDetailViewModel(
            application = application,
            loadDetail = { detail }
        ))
    }
}

private fun creatorDetailFixture(
    creator: YouTubeMusicCreatorSummary
): YouTubeMusicCreatorDetail {
    val horizontalItems = (0..30).map { index ->
        YouTubeMusicCreatorItem(
            type = if (index == 0) {
                YouTubeMusicCreatorItemType.Creator
            } else {
                YouTubeMusicCreatorItemType.Playlist
            },
            title = "Item $index",
            subtitle = creator.title,
            coverUrl = "",
            browseId = "item-$index"
        )
    }
    val horizontalSection = YouTubeMusicCreatorSection(
        title = "Fans also like",
        items = horizontalItems
    )
    val sections = listOf(horizontalSection) + (1..31).map { index ->
        YouTubeMusicCreatorSection(
            title = "Section $index",
            items = listOf(
                YouTubeMusicCreatorItem(
                    type = YouTubeMusicCreatorItemType.Playlist,
                    title = "Section item $index",
                    subtitle = creator.title,
                    coverUrl = "",
                    browseId = "section-item-$index"
                )
            )
        )
    }
    return YouTubeMusicCreatorDetail(
        header = YouTubeMusicCreatorHeader(
            browseId = creator.browseId,
            title = creator.title,
            subtitle = creator.subtitle,
            coverUrl = creator.coverUrl
        ),
        sections = sections
    )
}
