package moe.ouom.neriplayer.ui.screen.artist

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.BiliUploaderSummary
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorDetail
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorHeader
import moe.ouom.neriplayer.testutil.FittedTestViewport
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.theme.NeriTheme
import moe.ouom.neriplayer.ui.viewmodel.artist.BiliUploaderDetailUiState
import moe.ouom.neriplayer.ui.viewmodel.artist.BiliUploaderHeader
import moe.ouom.neriplayer.ui.viewmodel.artist.NeteaseArtistDetailUiState
import moe.ouom.neriplayer.ui.viewmodel.artist.NeteaseArtistHeader
import moe.ouom.neriplayer.ui.viewmodel.artist.YouTubeMusicCreatorDetailUiState
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArtistResponsiveLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() = assumeComposeHostAvailable()

    @Test
    fun cleanPhoneStatsWrapAsWholeChipsAtLargeFont() {
        checkStats(UiStyle.Clean)
        capture("artist-responsive-clean-stats-200")
    }

    @Test
    fun materialPhoneStatsWrapAsWholeChipsAtLargeFont() = checkStats(UiStyle.Material)

    @Test
    fun cleanLongAlbumDoesNotHideTheSongArtist() = checkSongSubtitle(UiStyle.Clean)

    @Test
    fun materialLongAlbumDoesNotHideTheSongArtist() = checkSongSubtitle(UiStyle.Material)

    @Test
    fun cleanArtistTabsStayCompactAndSelectAlbums() {
        val ui = artistFixture()
        var selected = 0
        render(UiStyle.Clean, fontScale = 1.5f) {
            ArtistContent(ui, onTabSelected = { selected = it })
        }
        val albums = resource(CoreCommonR.string.artist_tab_albums)
        composeRule.onNodeWithText(albums).performScrollTo()
        assertCompactTab(albums)
        composeRule.onNodeWithText(albums).performClick()
        composeRule.runOnIdle { assertEquals(1, selected) }
        composeRule.onNodeWithText("Fixture album").assertIsDisplayed()
    }

    @Test
    fun cleanUploaderTabsStayCompactAndSelectCollections() {
        var selected = 0
        render(UiStyle.Clean, fontScale = 1.5f) {
            var tab by remember { mutableIntStateOf(0) }
            val header = BiliUploaderHeader(123L, "Fixture uploader", "", "", "")
            BiliUploaderContent(
                ui = BiliUploaderDetailUiState(loading = false, header = header),
                followFavorite = createBiliUploaderFavorite(
                    BiliUploaderSummary(header.mid, header.name, ""), header
                ),
                listState = rememberLazyListState(),
                selectedTab = tab,
                onTabSelected = { tab = it; selected = it },
                onRetry = {}, onLoadMoreVideos = {}, onLoadMoreContents = {},
                onVideoClick = { _, _ -> }, onContentClick = {},
                offlineMode = true, tabletDevice = false
            )
        }
        val collections = resource(CoreCommonR.string.bili_uploader_tab_collections)
        composeRule.onNodeWithText(collections).performScrollTo()
        assertCompactTab(collections)
        composeRule.onNodeWithText(collections).performClick()
        composeRule.runOnIdle { assertEquals(1, selected) }
    }

    @Test
    fun cleanCreatorHeroCanGrowForVeryLargeText() {
        checkCreatorHero(UiStyle.Clean)
        capture("artist-responsive-clean-youtube-250")
    }

    @Test
    fun materialCreatorHeroCanGrowForVeryLargeText() = checkCreatorHero(UiStyle.Material)

    @Test
    fun cleanCreatorHeroKeepsTwoLinesAtTwoHundredPercent() = checkCreatorHero(UiStyle.Clean, 2f)

    @Test
    fun materialCreatorHeroKeepsTwoLinesAtTwoHundredPercent() = checkCreatorHero(UiStyle.Material, 2f)

    @Test
    fun cleanPhoneLastSongRemainsReachableAboveMiniPlayer() = checkLastSong(UiStyle.Clean)

    @Test
    fun materialPhoneLastSongRemainsReachableAboveMiniPlayer() = checkLastSong(UiStyle.Material)

    private fun checkStats(style: UiStyle) {
        render(style, fontScale = 2f) { ArtistContent(artistFixture()) }
        val songs = quantity(CoreCommonR.plurals.artist_song_count, 99999)
        val albums = quantity(CoreCommonR.plurals.artist_album_count, 9999)
        val songChip = composeRule.onNode(hasText(songs) and hasClickAction())
        val albumChip = composeRule.onNode(hasText(albums) and hasClickAction())
        val first = bounds(songChip)
        val second = bounds(albumChip)
        assertTrue("统计标签应整体换行，不能挤窄第二枚标签", second.top >= first.bottom - 1f)
        listOf(songs, albums).forEach { label ->
            val layout = textLayout(label)
            assertEquals("每枚统计标签应保留单行文字", 1, layout.lineCount)
            assertFalse("统计文字不能竖向裁切", layout.didOverflowHeight)
        }
    }

    private fun checkSongSubtitle(style: UiStyle) {
        render(style, fontScale = 2f) {
            ArtistSongRow(1, fixtureSong(0), onClick = {}, offlineMode = true)
        }
        val artist = composeRule.onNodeWithText(SONG_ARTIST, useUnmergedTree = true)
        val album = composeRule.onNodeWithText(" · $LONG_ALBUM", useUnmergedTree = true)
        assertTrue("长专辑名不能把歌手宽度挤成零", bounds(artist).width > 1f)
        assertTrue("专辑仍应分到可显示宽度", bounds(album).width > 1f)
        artist.assertIsDisplayed()
        album.assertIsDisplayed()
    }

    private fun checkCreatorHero(style: UiStyle, fontScale: Float = 2.5f) {
        val title = "歌手\n名称"
        val subtitle = "简介"
        render(style, fontScale = fontScale) {
            val header = YouTubeMusicCreatorHeader("fixture", title, subtitle, "")
            YouTubeMusicCreatorDetailContent(
                uiState = YouTubeMusicCreatorDetailUiState(
                    loading = false, detail = YouTubeMusicCreatorDetail(header, emptyList())
                ),
                listState = rememberLazyListState(), creatorBrowseId = header.browseId,
                sectionStateHolder = rememberSaveableStateHolder(), miniPlayerHeight = 0.dp,
                offlineMode = true, onRetry = {}, onSongClick = { _, _ -> },
                onSectionSongClick = { _, _ -> }, onPlaylistClick = {}, onCreatorClick = {},
                onSectionMoreClick = {}, isTabletLayout = false
            )
        }
        assertEquals("两行歌手名不能被固定头部高度压成一行", 2, textLayout(title).lineCount)
        listOf(title, subtitle).forEach { text ->
            val layout = textLayout(text)
            assertTrue("最后一行应完整显示", layout.getLineBottom(layout.lineCount - 1) <= layout.size.height + 1f)
        }
    }

    private fun checkLastSong(style: UiStyle) {
        val songs = (0 until 40).map(::fixtureSong)
        var playedIndex = -1
        render(style, fontScale = 1.5f, miniPlayer = true) {
            ArtistContent(artistFixture().copy(songs = songs), onSongClick = { queue, index ->
                assertEquals(songs, queue)
                playedIndex = index
            })
        }
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(songs.size + 1)
        val last = composeRule.onNodeWithText(songs.last().name)
        last.assertIsDisplayed().performClick()
        val viewport = bounds(composeRule.onNodeWithTag(VIEWPORT))
        assertTrue("末行文字不能被底部播放器遮挡", bounds(last).bottom <= viewport.bottom - 64f * viewport.width / 320f + 1f)
        composeRule.runOnIdle { assertEquals(songs.lastIndex, playedIndex) }
    }

    private fun assertCompactTab(label: String) {
        val tab = composeRule.onNode(
            hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        )
        val pixelsPerDp = bounds(composeRule.onNodeWithTag(VIEWPORT)).width / 320f
        assertTrue("Clean 分类应使用单行分段控件", bounds(tab).height <= textLayout(label).size.height + 16f * pixelsPerDp + 1f)
    }

    private fun textLayout(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
        return results.single()
    }

    private fun capture(name: String) {
        val bitmap = composeRule.onNodeWithTag(VIEWPORT).captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            context.cacheDir.resolve("$name.png").outputStream().use {
                assertTrue("布局截图应成功保存", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun bounds(node: SemanticsNodeInteraction): Rect {
        val semantics = node.fetchSemanticsNode()
        val position = semantics.positionInRoot
        return Rect(position.x, position.y, position.x + semantics.size.width, position.y + semantics.size.height)
    }

    @Composable
    private fun ArtistContent(
        ui: NeteaseArtistDetailUiState,
        onTabSelected: (Int) -> Unit = {},
        onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> }
    ) {
        var tab by remember { mutableIntStateOf(0) }
        NeteaseArtistDetailContent(
            ui = ui, listState = rememberLazyListState(), selectedTab = tab,
            onTabSelected = { tab = it; onTabSelected(it) }, onRetry = {}, onToggleFollow = {},
            onLoadMoreSongs = {}, onLoadMoreAlbums = {}, onSongClick = onSongClick,
            onAlbumClick = {}, offlineMode = true
        )
    }

    private fun render(
        style: UiStyle,
        fontScale: Float,
        miniPlayer: Boolean = false,
        content: @Composable () -> Unit
    ) {
        composeRule.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                smallestScreenWidthDp = 320
                screenWidthDp = 320
                screenHeightDp = 1000
            }
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalMiniPlayerHeight provides if (miniPlayer) 64.dp else 0.dp
            ) {
                NeriTheme(
                    followSystemDark = false, forceDark = false, dynamicColor = false,
                    seedColorHex = "885522", uiStyle = style, animateColors = false
                ) {
                    FittedTestViewport(320.dp, 1000.dp, fontScale = fontScale, modifier = Modifier.testTag(VIEWPORT)) {
                        Box(Modifier.fillMaxSize()) { content() }
                    }
                }
            }
        }
    }

    private fun resource(id: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun quantity(id: Int, count: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.resources.getQuantityString(id, count, count)
}

private const val VIEWPORT = "artist-responsive-viewport"
private const val SONG_ARTIST = "歌手"
private val LONG_ALBUM = "一张标题非常长而且不应挤掉歌手信息的专辑".repeat(3)

private fun fixtureSong(index: Int) = SongItem(
    id = index.toLong() + 1, name = "Fixture song $index", artist = SONG_ARTIST,
    album = LONG_ALBUM, albumId = 1, durationMs = 180_000, coverUrl = ""
)

private fun artistFixture() = NeteaseArtistDetailUiState(
    loading = false,
    header = NeteaseArtistHeader(1, "Fixture artist", "", "", "", "", 99999, 9999, false),
    songs = listOf(fixtureSong(0)),
    albums = listOf(AlbumSummary(2, "Fixture album", "", 10))
)
