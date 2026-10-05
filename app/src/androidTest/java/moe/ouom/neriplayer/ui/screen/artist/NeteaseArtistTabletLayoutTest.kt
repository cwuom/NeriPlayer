package moe.ouom.neriplayer.ui.screen.artist

import android.content.res.Configuration
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.testutil.FittedTestViewport
import moe.ouom.neriplayer.ui.component.navigation.NeriBottomBar
import moe.ouom.neriplayer.ui.component.playback.NeriMiniPlayerDefaults
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.resolveBottomBarLayoutInsets
import moe.ouom.neriplayer.ui.viewmodel.artist.NeteaseArtistDetailUiState
import moe.ouom.neriplayer.ui.viewmodel.artist.NeteaseArtistHeader
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NeteaseArtistTabletLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun tabletLandscape_keepsProfileAndTabsBesideScrollableWorks() {
        setFixture(width = 1280.dp, height = 800.dp)
        val profile = composeRule.onNodeWithText(FIXTURE_ARTIST).fetchSemanticsNode().boundsInRoot
        val firstSong = composeRule.onNodeWithText("Song 0").fetchSemanticsNode().boundsInRoot
        val initialTabs = composeRule.onNodeWithText(songsLabel).fetchSemanticsNode().boundsInRoot
        assertTrue("横屏平板的作品应位于简介右侧", firstSong.left > profile.right)
        capture("artist-landscape")

        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(18)

        composeRule.onNodeWithText(FIXTURE_ARTIST).assertIsDisplayed()
        composeRule.onNodeWithText(songsLabel).assertIsDisplayed()
        assertEquals(
            "作品滚动不应移动平板分类栏",
            initialTabs,
            composeRule.onNodeWithText(songsLabel).fetchSemanticsNode().boundsInRoot
        )
        assertEquals(
            "作品滚动不应移动歌手简介",
            profile,
            composeRule.onNodeWithText(FIXTURE_ARTIST).fetchSemanticsNode().boundsInRoot
        )
    }

    @Test
    fun tabletPortrait_usesTwoPanesFromFirstFrame() {
        setFixture(width = 800.dp, height = 1000.dp)

        val profile = composeRule.onNodeWithText(FIXTURE_ARTIST).fetchSemanticsNode().boundsInRoot
        val song = composeRule.onNodeWithText("Song 0").fetchSemanticsNode().boundsInRoot
        val tabs = composeRule.onNodeWithText(songsLabel).fetchSemanticsNode().boundsInRoot
        assertTrue("竖屏平板首次显示也应采用双栏", tabs.left > profile.right)
        assertTrue("竖屏平板作品应保留独立阅读区", song.left > profile.right)
        composeRule.onNodeWithText(followLabel).assertIsDisplayed()
        capture("artist-portrait")
    }

    @Test
    fun chromeOpaqueLandscape_keepsSmallGapAboveMiniAndFollowVisible() {
        verifyChromeLandscape(baseBlurRequested = false, hasMini = true, stage = "opaque")
    }

    @Test
    fun chromeBlurLandscape_keepsSmallGapAboveMiniAndFollowVisible() {
        verifyChromeLandscape(baseBlurRequested = true, hasMini = true, stage = "blur")
    }

    @Test
    fun chromeOpaqueWithoutMini_doesNotReservePhantomPlayerSpace() {
        verifyChromeLandscape(baseBlurRequested = false, hasMini = false, stage = "opaque-no-mini")
    }

    @Test
    fun chromeBlurWithoutMini_doesNotReservePhantomPlayerSpace() {
        verifyChromeLandscape(baseBlurRequested = true, hasMini = false, stage = "blur-no-mini")
    }

    @Test
    fun narrowTablet_largeFontKeepsProfileActionsAndSongQueueReachable() {
        val initialUi = artistFixture()
        val ui = initialUi.copy(
            header = checkNotNull(initialUi.header).copy(
                name = FIXTURE_ARTIST.repeat(4),
                alias = "很长的歌手别名与名称".repeat(5)
            )
        )
        var followRequests = 0
        var playedQueue: List<SongItem>? = null
        var playedIndex: Int? = null
        setFixture(
            width = 600.dp,
            height = 800.dp,
            fontScale = 1.5f,
            ui = ui,
            onToggleFollow = { followRequests++ },
            onSongClick = { queue, index ->
                playedQueue = queue
                playedIndex = index
            }
        )
        val initialTabs = composeRule.onNodeWithText(songsLabel).fetchSemanticsNode().boundsInRoot
        capture("artist-compact-portrait")

        composeRule.onNodeWithText(followLabel).performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Song 0").assertIsDisplayed().performClick()

        assertEquals(
            "简介滚动不应移动作品分类栏",
            initialTabs,
            composeRule.onNodeWithText(songsLabel).fetchSemanticsNode().boundsInRoot
        )
        composeRule.runOnIdle {
            assertEquals(1, followRequests)
            assertEquals(ui.songs, playedQueue)
            assertEquals(0, playedIndex)
        }
    }

    @Test
    fun tabletTabs_preserveIndependentPositionsAndPaginationActions() {
        val ui = artistFixture()
        var songsLoadRequests = 0
        var albumsLoadRequests = 0
        var openedAlbum: AlbumSummary? = null
        val lists = setChromeFixture(
            width = 800.dp,
            height = 1000.dp,
            baseBlurRequested = true,
            hasMini = true,
            ui = ui,
            onLoadMoreSongs = { songsLoadRequests++ },
            onLoadMoreAlbums = { albumsLoadRequests++ },
            onAlbumClick = { openedAlbum = it }
        )
        assertChromeContentGap(hasMini = true, width = 800.dp)
        assertFollowFullyVisible()
        val tabs = composeRule.onNodeWithText(songsLabel).fetchSemanticsNode().boundsInRoot
        val cover = unclippedBounds(composeRule.onNodeWithContentDescription(FIXTURE_ARTIST))
        assertTrue("带主导航的竖屏平板仍应保留双栏", tabs.left > cover.right)
        capture("artist-chrome-portrait")
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(18)
        assertEquals(
            "竖屏作品滚动不应移动分类栏",
            tabs,
            composeRule.onNodeWithText(songsLabel).fetchSemanticsNode().boundsInRoot
        )
        val songsPosition = composeRule.runOnIdle {
            lists.songs.firstVisibleItemIndex to lists.songs.firstVisibleItemScrollOffset
        }
        composeRule.onNodeWithText(albumsLabel).performClick()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(12)
        val albumsPosition = composeRule.runOnIdle {
            lists.albums.firstVisibleItemIndex to lists.albums.firstVisibleItemScrollOffset
        }
        composeRule.onNodeWithText("Album 12").performClick()
        composeRule.onNodeWithText(songsLabel).performClick()
        composeRule.runOnIdle {
            assertEquals(
                songsPosition,
                lists.songs.firstVisibleItemIndex to lists.songs.firstVisibleItemScrollOffset
            )
        }
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(ui.songs.size)
        composeRule.onNodeWithText(loadMoreLabel).assertIsDisplayed().performClick()
        composeRule.onNodeWithText(albumsLabel).performClick()
        composeRule.runOnIdle {
            assertEquals(
                albumsPosition,
                lists.albums.firstVisibleItemIndex to lists.albums.firstVisibleItemScrollOffset
            )
        }
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(ui.albums.size)
        composeRule.onNodeWithText(loadMoreLabel).assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals(1, songsLoadRequests)
            assertEquals(1, albumsLoadRequests)
            assertEquals(ui.albums[12], openedAlbum)
        }
    }

    @Test
    fun phoneLandscape_keepsHeaderInsideOriginalSingleList() {
        setFixture(width = 800.dp, height = 360.dp, smallestScreenWidth = 360)
        composeRule.onNodeWithText(FIXTURE_ARTIST).assertIsDisplayed()

        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(2)

        composeRule.onNodeWithText("Song 0").assertIsDisplayed()
        composeRule.onNodeWithText(FIXTURE_ARTIST).assertIsNotDisplayed()
    }

    @Test
    fun narrowTabletWindow_fallsBackToSingleList() {
        setFixture(width = 500.dp, height = 800.dp)

        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(2)

        composeRule.onNodeWithText("Song 0").assertIsDisplayed()
        composeRule.onNodeWithText(FIXTURE_ARTIST).assertIsNotDisplayed()
    }

    @Test
    fun tabletInitialError_keepsRetryInWorksPane() {
        var retryRequests = 0
        val ui = artistFixture().copy(error = "fixture error", songs = emptyList(), albums = emptyList())
        setFixture(width = 600.dp, height = 800.dp, ui = ui, onRetry = { retryRequests++ })

        val profile = composeRule.onNodeWithText(FIXTURE_ARTIST).fetchSemanticsNode().boundsInRoot
        val error = composeRule.onNodeWithText("fixture error").fetchSemanticsNode().boundsInRoot
        assertTrue("错误与重试应显示在作品区域", error.left > profile.right)
        composeRule.onNodeWithText(retryLabel).assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, retryRequests) }
    }

    private fun verifyChromeLandscape(baseBlurRequested: Boolean, hasMini: Boolean, stage: String) {
        var followRequests = 0
        setChromeFixture(
            width = 1280.dp,
            height = 800.dp,
            baseBlurRequested = baseBlurRequested,
            hasMini = hasMini,
            onToggleFollow = { followRequests++ }
        )
        assertChromeContentGap(hasMini = hasMini, width = 1280.dp)
        assertFollowFullyVisible()
        val cover = unclippedBounds(composeRule.onNodeWithContentDescription(FIXTURE_ARTIST))
        assertTrue("平板照片应比原正方形封面更矮", cover.height <= cover.width * 0.85f)
        capture("artist-chrome-$stage-landscape")

        composeRule.onNodeWithText(followLabel).performClick()

        composeRule.runOnIdle { assertEquals("首屏关注按钮必须可以点击", 1, followRequests) }
    }

    private fun assertChromeContentGap(hasMini: Boolean, width: Dp) {
        val works = composeRule.onNode(hasScrollToIndexAction()).fetchSemanticsNode().boundsInRoot
        val chrome = composeRule.onNodeWithTag(if (hasMini) CHROME_MINI else CHROME_BOTTOM_NAV)
            .fetchSemanticsNode().boundsInRoot
        val viewport = composeRule.onNodeWithTag(FIXTURE_ROOT).fetchSemanticsNode().boundsInRoot
        val pixelsPerDp = viewport.width / width.value
        val gap = chrome.top - works.bottom
        assertTrue("作品区域不能被底部控件遮挡", gap >= -1f)
        assertTrue("作品区到下方控件的间距不能重复加入系统 inset 或播放器高度", gap <= 12f * pixelsPerDp + 1f)
    }

    private fun assertFollowFullyVisible() {
        val follow = unclippedBounds(composeRule.onNodeWithText(followLabel))
        val works = composeRule.onNode(hasScrollToIndexAction()).fetchSemanticsNode().boundsInRoot
        val topBar = composeRule.onNodeWithTag(CHROME_TOP_BAR).fetchSemanticsNode().boundsInRoot
        composeRule.onNodeWithText(followLabel).assertIsDisplayed()
        assertTrue("首屏关注按钮上缘应位于顶部栏下方", follow.top >= topBar.bottom - 1f)
        assertTrue("首屏应完整露出关注按钮，不能依赖滚动", follow.bottom <= works.bottom + 1f)
    }

    private fun unclippedBounds(node: SemanticsNodeInteraction): Rect {
        val semantics = node.fetchSemanticsNode()
        val position = semantics.positionInRoot
        return Rect(
            left = position.x,
            top = position.y,
            right = position.x + semantics.size.width,
            bottom = position.y + semantics.size.height
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun setChromeFixture(
        width: Dp,
        height: Dp,
        baseBlurRequested: Boolean,
        hasMini: Boolean,
        ui: NeteaseArtistDetailUiState = artistFixture(),
        onToggleFollow: () -> Unit = {},
        onLoadMoreSongs: () -> Unit = {},
        onLoadMoreAlbums: () -> Unit = {},
        onAlbumClick: (AlbumSummary) -> Unit = {}
    ): ArtistFixtureLists {
        val lists = ArtistFixtureLists()
        composeRule.setContent {
            MaterialTheme {
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val baseDensity = LocalDensity.current
                    val scale = minOf(maxWidth.value / width.value, maxHeight.value / height.value)
                    val configuration = Configuration(LocalConfiguration.current).apply {
                        smallestScreenWidthDp = 600
                        screenWidthDp = width.value.toInt()
                        screenHeightDp = height.value.toInt()
                    }
                    CompositionLocalProvider(
                        LocalConfiguration provides configuration,
                        LocalDensity provides Density(baseDensity.density * scale, fontScale = 1f)
                    ) {
                        var selectedTab by remember { mutableIntStateOf(0) }
                        FittedTestViewport(
                            width = width,
                            height = height,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.background)
                                .testTag(FIXTURE_ROOT)
                        ) {
                            Scaffold(
                                containerColor = Color.Transparent,
                                bottomBar = {
                                    NeriBottomBar(
                                        items = listOf(
                                            Destinations.Home to Icons.Filled.Home,
                                            Destinations.Explore to Icons.Filled.Search,
                                            Destinations.Library to Icons.Filled.LibraryMusic,
                                            Destinations.Settings to Icons.Filled.Settings
                                        ),
                                        currentDestination = null,
                                        onItemSelected = {},
                                        modifier = Modifier.testTag(CHROME_BOTTOM_NAV)
                                    )
                                }
                            ) { innerPadding ->
                                val layoutInsets = resolveBottomBarLayoutInsets(
                                    baseBlurRequested = baseBlurRequested,
                                    bottomBarInset = innerPadding.calculateBottomPadding(),
                                    reservedMiniPlayerHeight = if (hasMini) NeriMiniPlayerDefaults.Height else 0.dp
                                )
                                CompositionLocalProvider(LocalMiniPlayerHeight provides layoutInsets.screenBottomInset) {
                                    Box(
                                        Modifier.fillMaxSize().padding(bottom = layoutInsets.navContentBottomPadding)
                                    ) {
                                        Column(Modifier.fillMaxSize()) {
                                            TopAppBar(
                                                title = { Text(FIXTURE_ARTIST) },
                                                modifier = Modifier.testTag(CHROME_TOP_BAR),
                                                navigationIcon = {
                                                    IconButton(onClick = {}) {
                                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                                    }
                                                },
                                                windowInsets = WindowInsets.statusBars,
                                                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                                            )
                                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                                NeteaseArtistDetailContent(
                                                    ui = ui,
                                                    listState = if (selectedTab == 0) lists.songs else lists.albums,
                                                    selectedTab = selectedTab,
                                                    onTabSelected = { selectedTab = it },
                                                    onRetry = {},
                                                    onToggleFollow = onToggleFollow,
                                                    onLoadMoreSongs = onLoadMoreSongs,
                                                    onLoadMoreAlbums = onLoadMoreAlbums,
                                                    onSongClick = { _, _ -> },
                                                    onAlbumClick = onAlbumClick,
                                                    offlineMode = true
                                                )
                                            }
                                        }
                                        if (hasMini) {
                                            Surface(
                                                modifier = Modifier.align(Alignment.BottomCenter)
                                                    .padding(bottom = layoutInsets.miniPlayerBottomPadding)
                                                    .fillMaxWidth().height(NeriMiniPlayerDefaults.Height)
                                                    .testTag(CHROME_MINI),
                                                color = MaterialTheme.colorScheme.secondaryContainer
                                            ) {
                                                Box(contentAlignment = Alignment.CenterStart) {
                                                    Text("Fixture playback", Modifier.padding(horizontal = 20.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return lists
    }

    private fun setFixture(
        width: Dp,
        height: Dp,
        smallestScreenWidth: Int = 600,
        fontScale: Float = 1f,
        ui: NeteaseArtistDetailUiState = artistFixture(),
        onRetry: () -> Unit = {},
        onToggleFollow: () -> Unit = {},
        onLoadMoreSongs: () -> Unit = {},
        onLoadMoreAlbums: () -> Unit = {},
        onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> },
        onAlbumClick: (AlbumSummary) -> Unit = {}
    ): ArtistFixtureLists {
        val lists = ArtistFixtureLists()
        composeRule.setContent {
            MaterialTheme {
                val baseDensity = LocalDensity.current
                val configuration = Configuration(LocalConfiguration.current).apply {
                    smallestScreenWidthDp = smallestScreenWidth
                    screenWidthDp = width.value.toInt()
                    screenHeightDp = height.value.toInt()
                }
                CompositionLocalProvider(
                    LocalConfiguration provides configuration,
                    LocalDensity provides Density(baseDensity.density * 0.5f, fontScale),
                    LocalMiniPlayerHeight provides 0.dp
                ) {
                    var selectedTab by remember { mutableIntStateOf(0) }
                    FittedTestViewport(
                        width = width,
                        height = height,
                        fontScale = fontScale,
                        modifier = Modifier
                            .testTag(FIXTURE_ROOT)
                    ) {
                        NeteaseArtistDetailContent(
                            ui = ui,
                            listState = if (selectedTab == 0) lists.songs else lists.albums,
                            selectedTab = selectedTab,
                            onTabSelected = { selectedTab = it },
                            onRetry = onRetry,
                            onToggleFollow = onToggleFollow,
                            onLoadMoreSongs = onLoadMoreSongs,
                            onLoadMoreAlbums = onLoadMoreAlbums,
                            onSongClick = onSongClick,
                            onAlbumClick = onAlbumClick,
                            offlineMode = true
                        )
                    }
                }
            }
        }
        return lists
    }

    private fun capture(stage: String) {
        val prefix = InstrumentationRegistry.getArguments().getString("capturePrefix")
            ?.takeIf(String::isNotBlank) ?: return
        val safePrefix = prefix.replace(Regex("[^a-zA-Z0-9_-]"), "-").take(60)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(checkNotNull(context.getExternalFilesDir(null)), "$safePrefix-$stage.png")
        val bitmap = composeRule.onNodeWithTag(FIXTURE_ROOT).captureToImage().asAndroidBitmap()
        try {
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            Log.i("ArtistTabletLayoutTest", "screenshot=${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private val songsLabel get() = resource(CoreCommonR.string.artist_tab_songs)
    private val albumsLabel get() = resource(CoreCommonR.string.artist_tab_albums)
    private val followLabel get() = resource(CoreCommonR.string.artist_follow)
    private val loadMoreLabel get() = resource(CoreCommonR.string.artist_load_more)
    private val retryLabel get() = resource(CoreCommonR.string.action_retry)

    private fun resource(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
}

private class ArtistFixtureLists {
    val songs = LazyListState()
    val albums = LazyListState()
}

private const val FIXTURE_ARTIST = "Fixture artist"
private const val FIXTURE_ROOT = "netease-artist-fixture"
private const val CHROME_TOP_BAR = "netease-artist-fixture-top-bar"
private const val CHROME_BOTTOM_NAV = "netease-artist-fixture-bottom-nav"
private const val CHROME_MINI = "netease-artist-fixture-mini"

private fun artistFixture(): NeteaseArtistDetailUiState = NeteaseArtistDetailUiState(
    loading = false,
    header = NeteaseArtistHeader(
        id = 1,
        name = FIXTURE_ARTIST,
        coverUrl = "",
        avatarUrl = "",
        alias = "歌手别名",
        briefDesc = "歌手的音乐风格、创作经历与代表作品简介。".repeat(20),
        musicSize = 40,
        albumSize = 24,
        followed = false
    ),
    songs = (0 until 40).map { index ->
        SongItem(
            id = index.toLong() + 1,
            name = "Song $index",
            artist = "Fixture performer",
            album = "Fixture album",
            albumId = 1,
            durationMs = 180_000,
            coverUrl = ""
        )
    },
    albums = (0 until 24).map { index ->
        AlbumSummary(id = index.toLong() + 1, name = "Album $index", picUrl = "", size = 10)
    },
    songsHasMore = true,
    albumsHasMore = true
)
