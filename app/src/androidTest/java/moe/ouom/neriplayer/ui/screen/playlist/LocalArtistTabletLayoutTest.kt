package moe.ouom.neriplayer.ui.screen.playlist

import android.content.res.Configuration
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.testutil.FittedTestViewport
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalArtistTabletLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun landscapeKeepsArtistBesideIndependentSongList() {
        setFixture(width = 1280.dp, height = 800.dp)
        val profile = profileTitle().fetchSemanticsNode().boundsInRoot
        val song = composeRule.onNodeWithText("Local song 0").fetchSemanticsNode().boundsInRoot
        assertTrue(song.left > profile.right)
        assertTrue("侧栏封面应位于歌手名称上方", profile.top > song.top)

        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(18)

        profileTitle().assertIsDisplayed()
        assertEquals(profile, profileTitle().fetchSemanticsNode().boundsInRoot)
    }

    @Test
    fun compactPortraitLargeFontKeepsMetadataAndSongsVisible() {
        val title = ARTIST_NAME.repeat(5)
        setFixture(width = 600.dp, height = 800.dp, fontScale = 1.5f, title = title)

        composeRule.onNodeWithTag("creatorDetailSplitLayout").assertIsDisplayed()
        profileTitle(title).assertIsDisplayed()
        composeRule.onNodeWithText("Local song 0").assertIsDisplayed()
        val profile = composeRule.onNodeWithTag("creatorDetailProfile").fetchSemanticsNode().boundsInRoot
        val works = composeRule.onNodeWithTag("creatorDetailWorks").fetchSemanticsNode().boundsInRoot
        assertTrue(works.left > profile.right)
    }

    @Test
    fun dockedSearchAndSongClickKeepFilteredQueueAndIndex() {
        val songs = localSongs()
        val filtered = listOf(songs[4], songs[9])
        val query = mutableStateOf("")
        var playedQueue: List<SongItem>? = null
        var playedIndex: Int? = null
        setFixture(
            width = 800.dp,
            height = 1000.dp,
            songs = filtered,
            showSearch = true,
            query = query,
            onSongClick = { queue, index ->
                playedQueue = queue
                playedIndex = index
            }
        )
        composeRule.onNode(hasSetTextAction()).performTextReplacement("Local")
        composeRule.waitUntil(timeoutMillis = 1_000L) { query.value == "Local" }
        composeRule.runOnIdle { assertEquals("Local", query.value) }
        composeRule.onNodeWithText("Local song 9").performClick()

        composeRule.runOnIdle {
            assertEquals(filtered, playedQueue)
            assertEquals(1, playedIndex)
        }
    }

    @Test
    fun longPressAndFilteredSelectionKeepHiddenSongSelected() {
        val songs = localSongs()
        val displayed = mutableStateOf(songs)
        val selecting = mutableStateOf(false)
        val selected = mutableStateOf<Set<String>>(emptySet())
        var playRequests = 0
        setFixture(
            width = 800.dp,
            height = 1000.dp,
            songState = displayed,
            selectionMode = selecting,
            selectedKeys = selected,
            onLongClick = { song ->
                selecting.value = true
                selected.value = setOf(song.stableKey())
            },
            onToggleSelect = { song ->
                val key = song.stableKey()
                selected.value = if (key in selected.value) selected.value - key else selected.value + key
            },
            onSongClick = { _, _ -> playRequests++ }
        )
        composeRule.onNodeWithText("Local song 0").performTouchInput { longClick() }
        composeRule.runOnIdle {
            assertTrue(selecting.value)
            assertEquals(setOf(songs[0].stableKey()), selected.value)
            displayed.value = listOf(songs[4], songs[9])
        }
        composeRule.onNodeWithText("Local song 9").performClick()
        composeRule.runOnIdle {
            assertEquals(setOf(songs[0].stableKey(), songs[9].stableKey()), selected.value)
            assertEquals(0, playRequests)
        }
        composeRule.onNodeWithText("Local song 9").performClick()
        composeRule.runOnIdle { assertEquals(setOf(songs[0].stableKey()), selected.value) }
    }

    @Test
    fun narrowWindowRoundTripKeepsSongPosition() {
        val width = mutableStateOf(800.dp)
        val list = setFixture(width = width.value, height = 1000.dp, windowWidth = width)
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(15)
        val position = composeRule.runOnIdle { list.firstVisibleItemIndex }
        composeRule.runOnIdle { width.value = 500.dp }
        composeRule.onNodeWithTag("creatorDetailSplitLayout").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(position, list.firstVisibleItemIndex) }
        composeRule.runOnIdle { width.value = 800.dp }
        composeRule.onNodeWithTag("creatorDetailSplitLayout").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(position, list.firstVisibleItemIndex) }
    }

    private fun profileTitle(title: String = ARTIST_NAME) = composeRule.onNode(
        hasText(title).and(hasAnyAncestor(hasTestTag("creatorDetailProfile")))
    )

    private fun setFixture(
        width: Dp,
        height: Dp,
        fontScale: Float = 1f,
        title: String = ARTIST_NAME,
        songs: List<SongItem> = localSongs(),
        windowWidth: MutableState<Dp>? = null,
        songState: MutableState<List<SongItem>>? = null,
        showSearch: Boolean = false,
        query: MutableState<String> = mutableStateOf(""),
        selectionMode: MutableState<Boolean> = mutableStateOf(false),
        selectedKeys: MutableState<Set<String>> = mutableStateOf(emptySet()),
        onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> },
        onLongClick: (SongItem) -> Unit = {},
        onToggleSelect: (SongItem) -> Unit = {}
    ): LazyListState {
        val listState = LazyListState()
        composeRule.setContent {
            MaterialTheme {
                val actualWidth = windowWidth?.value ?: width
                val baseDensity = LocalDensity.current
                val configuration = Configuration(LocalConfiguration.current).apply {
                    smallestScreenWidthDp = 600
                    screenWidthDp = actualWidth.value.toInt()
                    screenHeightDp = height.value.toInt()
                }
                CompositionLocalProvider(
                    LocalConfiguration provides configuration,
                    LocalDensity provides Density(baseDensity.density * 0.5f, fontScale),
                    LocalMiniPlayerHeight provides 0.dp
                ) {
                    FittedTestViewport(actualWidth, height, fontScale = fontScale) {
                        LocalArtistDetailContent(
                            title = title,
                            headerCover = null,
                            songCount = songs.size,
                            durationMs = songs.sumOf(SongItem::durationMs),
                            displayedSongs = songState?.value ?: songs,
                            listState = listState,
                            showSearch = showSearch,
                            searchQuery = query.value,
                            searchFocusRequester = remember { FocusRequester() },
                            selectionMode = selectionMode.value,
                            selectedKeys = selectedKeys.value,
                            downloadPresenceVersion = 0,
                            onQueryChange = { query.value = it },
                            onSongClick = onSongClick,
                            onToggleSelect = onToggleSelect,
                            onLongClick = onLongClick,
                            offlineMode = true
                        )
                    }
                }
            }
        }
        return listState
    }
}

private const val ARTIST_NAME = "Local Artist"

private fun localSongs(): List<SongItem> = List(40) { index ->
    SongItem(
        id = 9_010_000L + index,
        name = "Local song $index",
        artist = ARTIST_NAME,
        album = "Local fixture",
        albumId = 0L,
        durationMs = 120_000L,
        coverUrl = null
    )
}
