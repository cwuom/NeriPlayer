package moe.ouom.neriplayer.ui.screen.artist

import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange
import androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAncestors
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicCreatorDetail
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicCreatorHeader
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicCreatorItem
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicCreatorItemType
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicCreatorSection
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicCreatorSummary
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.screen.host.youtubeMusicCreatorDetailStateKey
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
        composeRule.setContent {
            CreatorDetailNavigationFixture()
        }

        waitForInitialCreatorContent()
        composeRule.onNodeWithText("Item 0")
            .onAncestors()
            .filterToOne(horizontalLazyListMatcher)
            .performScrollToIndex(8)
        composeRule.waitUntil(timeoutMillis = SCROLL_STATE_TIMEOUT_MS) {
            horizontalScrollOffset() > 0f
        }
        val horizontalScrollOffsetBeforeNavigation = horizontalScrollOffset()

        composeRule.onNode(verticalLazyListMatcher).performScrollToIndex(12)
        composeRule.waitUntil(timeoutMillis = SCROLL_STATE_TIMEOUT_MS) {
            verticalScrollOffset() > 0f
        }
        val verticalScrollOffsetBeforeNavigation = verticalScrollOffset()

        composeRule.waitUntil(timeoutMillis = SCROLL_STATE_TIMEOUT_MS) {
            composeRule.onAllNodesWithText("Section item 11")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Section item 11").performClick()
        composeRule.waitUntil(timeoutMillis = SCROLL_STATE_TIMEOUT_MS) {
            composeRule.onAllNodesWithText(FIXTURE_BACK_LABEL)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(FIXTURE_BACK_LABEL).performClick()

        waitForCreatorList()
        composeRule.waitUntil(timeoutMillis = SCROLL_STATE_TIMEOUT_MS) {
            verticalScrollOffset() == verticalScrollOffsetBeforeNavigation
        }
        assertEquals(
            verticalScrollOffsetBeforeNavigation,
            verticalScrollOffset(),
            0f
        )

        composeRule.onNode(verticalLazyListMatcher).performScrollToIndex(0)
        composeRule.waitUntil(timeoutMillis = SCROLL_STATE_TIMEOUT_MS) {
            verticalScrollOffset() == 0f
        }
        assertEquals(
            horizontalScrollOffsetBeforeNavigation,
            horizontalScrollOffset(),
            0f
        )
    }

    private fun waitForInitialCreatorContent() {
        composeRule.waitUntil(timeoutMillis = SCROLL_STATE_TIMEOUT_MS) {
            composeRule.onAllNodesWithText("Item 0")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForCreatorList() {
        composeRule.waitUntil(timeoutMillis = SCROLL_STATE_TIMEOUT_MS) {
            composeRule.onAllNodes(verticalLazyListMatcher)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun verticalScrollOffset(): Float {
        return composeRule.onAllNodes(verticalLazyListMatcher)
            .fetchSemanticsNodes()
            .single()
            .config[VerticalScrollAxisRange]
            .value()
    }

    private fun horizontalScrollOffset(): Float {
        return composeRule.onAllNodes(horizontalLazyListMatcher)
            .fetchSemanticsNodes()
            .maxOf { node -> node.config[HorizontalScrollAxisRange].value() }
    }
}

private const val FIXTURE_BACK_LABEL = "fixture-back-to-creator"
private const val SCROLL_STATE_TIMEOUT_MS = 3_000L

private val verticalLazyListMatcher = hasScrollToIndexAction().and(
    SemanticsMatcher.keyIsDefined(VerticalScrollAxisRange)
)

private val horizontalLazyListMatcher = hasScrollToIndexAction().and(
    SemanticsMatcher.keyIsDefined(HorizontalScrollAxisRange)
)

@Composable
private fun CreatorDetailNavigationFixture() {
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

    MaterialTheme {
        if (playlistVisible) {
            Text(
                text = FIXTURE_BACK_LABEL,
                modifier = Modifier.clickable { playlistVisible = false }
            )
        } else {
            stateHolder.SaveableStateProvider(
                key = youtubeMusicCreatorDetailStateKey(creator)
            ) {
                YouTubeMusicCreatorDetailScreen(
                    creator = creator,
                    onPlaylistClick = { playlistVisible = true },
                    detailViewModelFactory = CreatorDetailTestViewModelFactory(detail)
                )
            }
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
        return requireNotNull(modelClass.cast(YouTubeMusicCreatorDetailViewModel(
            application = application,
            loadDetail = { detail }
        )))
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
