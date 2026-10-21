package moe.ouom.neriplayer.ui.screen.nowplaying.actions

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.auth.SavedCookieAuthHealth
import moe.ouom.neriplayer.data.model.auth.SavedCookieAuthState
import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaArtistInfo
import moe.ouom.neriplayer.data.model.playlist.FavoritePlaylist
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.playlist.favorite.FAVORITE_SOURCE_NETEASE_ARTIST
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylistRepository
import moe.ouom.neriplayer.testutil.FittedTestViewport
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.theme.NeriDesign
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SongEncyclopediaArtistLayoutTest {
    @get:Rule val composeRule = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Before
    fun prepare() = assumeComposeHostAvailable()

    @Test
    fun cleanLightNarrowNameAndFollowRemainSeparate() = checkLayout(UiStyle.Clean, dark = false)

    @Test
    fun cleanDarkNarrowNameAndFollowRemainSeparate() = checkLayout(UiStyle.Clean, dark = true)

    @Test
    fun materialLightNarrowNameAndFollowRemainSeparate() = checkLayout(UiStyle.Material, dark = false)

    @Test
    fun materialDarkNarrowNameAndFollowRemainSeparate() = checkLayout(UiStyle.Material, dark = true)

    @Test
    fun cleanLightLandscapeNameAndFollowRemainSeparate() =
        checkLayout(UiStyle.Clean, dark = false, landscape = true)

    @Test
    fun cleanDarkLandscapeNameAndFollowRemainSeparate() =
        checkLayout(UiStyle.Clean, dark = true, landscape = true)

    @Test
    fun materialLightLandscapeNameAndFollowRemainSeparate() =
        checkLayout(UiStyle.Material, dark = false, landscape = true)

    @Test
    fun materialDarkLandscapeNameAndFollowRemainSeparate() =
        checkLayout(UiStyle.Material, dark = true, landscape = true)

    @Test
    fun cleanLightShortArtistKeepsNameAndCountsTogether() = checkLayout(UiStyle.Clean, dark = false, shortName = true)

    @Test
    fun cleanDarkShortArtistKeepsNameAndCountsTogether() = checkLayout(UiStyle.Clean, dark = true, shortName = true)

    @Test
    fun materialLightShortArtistKeepsNameAndCountsTogether() = checkLayout(UiStyle.Material, dark = false, shortName = true)

    @Test
    fun materialDarkShortArtistKeepsNameAndCountsTogether() = checkLayout(UiStyle.Material, dark = true, shortName = true)

    @Test
    fun largeFontUsesAccessibleCompactFollowAction() =
        checkLayout(UiStyle.Material, dark = false, shortName = true, fontScale = 1.5f)

    @Test
    fun compactFollowingStateKeepsItsAccessibleDescription() = checkLayout(UiStyle.Clean, dark = true, followed = true)

    private fun checkLayout(
        style: UiStyle,
        dark: Boolean,
        landscape: Boolean = false,
        shortName: Boolean = false,
        followed: Boolean = false,
        fontScale: Float = if (shortName) 1f else 1.3f
    ) = withSyntheticAuth(followed, artistFixture(shortName)) {
        val artist = artistFixture(shortName)
        val width = if (landscape) 640 else if (shortName) 400 else 320
        render(style, dark, landscape, artist, width, fontScale)
        composeRule.waitForIdle()
        capture(
            "encyclopedia-artist-${style.name.lowercase()}-${if (dark) "dark" else "light"}-" +
                when {
                    followed -> "following"
                    shortName && fontScale > 1f -> "large-font"
                    shortName -> "short-name"
                    landscape -> "landscape"
                    else -> "narrow"
                }
        )

        val name = composeRule.onNodeWithText(artist.name, useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode()
        val followLabel = context.getString(if (followed) CoreCommonR.string.artist_followed else CoreCommonR.string.artist_follow)
        val follow = composeRule.onNode((hasText(followLabel) or hasContentDescription(followLabel)) and hasClickAction())
            .assertIsDisplayed().fetchSemanticsNode()
        val navigation = nearestClickTarget(name)
        val viewport = composeRule.onNodeWithTag(VIEWPORT).fetchSemanticsNode().boundsInRoot
        val pixelsPerDp = viewport.width / width

        assertTrue("长艺人名仍应分到可显示宽度", name.boundsInRoot.width > 1f)
        assertTrue("关注按钮应位于姓名右边", follow.boundsInRoot.left >= name.boundsInRoot.right - 1f)
        assertTrue("关注按钮不能超出面板右边界", follow.boundsInRoot.right <= viewport.right + 1f)
        assertTrue("艺人导航和关注触摸区域不能重叠", navigation.boundsInRoot.right <= follow.boundsInRoot.left + 1f)
        assertFalse(
            "关注按钮不能嵌套在艺人导航点击节点中",
            generateSequence(follow) { it.parent }.any { it.id == navigation.id }
        )

        val metaText = context.getString(CoreCommonR.string.encyclopedia_artist_bio_note, artist.albumSize, artist.musicSize)
        val meta = composeRule.onNodeWithText(metaText, useUnmergedTree = true).fetchSemanticsNode()
        assertEquals("姓名与数量应保持紧凑的2dp间距", 2f * pixelsPerDp, meta.boundsInRoot.top - name.boundsInRoot.bottom, 1f)
        assertEquals(
            "关注按钮应与整组姓名和数量垂直居中",
            (name.boundsInRoot.top + meta.boundsInRoot.bottom) / 2f,
            follow.boundsInRoot.center.y,
            1f
        )
        assertTrue("数量与关注按钮的水平区域不能重叠", meta.boundsInRoot.right <= follow.boundsInRoot.left + 1f)
        val metaLayout = textLayout(metaText)
        assertTrue("窄屏数量不应被挤成多行窄列", metaLayout.lineCount <= 2)
        assertFalse("数量文本不能横向裁切", metaLayout.didOverflowWidth)
        assertFalse("数量文本不能纵向裁切", metaLayout.didOverflowHeight)
        for (line in 0 until metaLayout.lineCount) {
            assertFalse("专辑和单曲数量应完整显示", metaLayout.isLineEllipsized(line))
        }

        val nameLayout = textLayout(artist.name)
        assertEquals("长艺人名应保持单行", 1, nameLayout.lineCount)
        if (!shortName) assertTrue("长艺人名应在单行内显示省略号", nameLayout.isLineEllipsized(0))
        // didOverflowHeight 也包含超过 maxLines 的正常省略，实际行框需单独检查
        assertTrue("艺人名单行顶部不能被裁切", nameLayout.getLineTop(0) >= -1f)
        assertTrue("艺人名单行底部应完整容纳", nameLayout.getLineBottom(0) <= nameLayout.size.height + 1f)
        val compact = !landscape && (!shortName || fontScale > 1f)
        if (compact) {
            composeRule.onNodeWithContentDescription(followLabel).assertExists()
            composeRule.onNodeWithText(followLabel, useUnmergedTree = true).assertDoesNotExist()
        } else {
            assertFalse("关注文案不能纵向裁切", textLayout(followLabel).didOverflowHeight)
        }
    }

    private fun render(
        style: UiStyle,
        dark: Boolean,
        landscape: Boolean,
        artist: EncyclopediaArtistInfo,
        width: Int,
        fontScale: Float
    ) {
        composeRule.setContent {
            val height = if (landscape) 320 else 640
            val configuration = Configuration(LocalConfiguration.current).apply {
                orientation = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                smallestScreenWidthDp = 320
                screenWidthDp = width
                screenHeightDp = height
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                NeriTheme(
                    followSystemDark = false,
                    forceDark = dark,
                    dynamicColor = false,
                    seedColorHex = "885522",
                    uiStyle = style,
                    animateColors = false
                ) {
                    FittedTestViewport(width.dp, height.dp, fontScale = fontScale, modifier = Modifier.testTag(VIEWPORT)) {
                        Column(
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
                                .padding(horizontal = NeriDesign.tokens.pageHorizontalPadding, vertical = 12.dp)
                        ) {
                            SongEncyclopediaArtistSection(
                                artist = artist,
                                onOpenArtist = {}
                            )
                        }
                    }
                }
            }
        }
    }

    private fun withSyntheticAuth(followed: Boolean, artist: EncyclopediaArtistInfo, body: () -> Unit) {
        var restore: (() -> Unit)? = null
        var restoreFavorite: (() -> Unit)? = null
        try {
            val favoriteRepository = if (followed) FavoritePlaylistRepository.getInstance(context).also {
                assertTrue("收藏仓库应完成初始化后再设置展示夹具", runBlocking { it.awaitInitialized() })
            } else null
            instrumentation.runOnMainSync {
                val repository = AppContainer.neteaseCookieRepo
                val field = repository.javaClass.getDeclaredField("_authHealthFlow").apply { isAccessible = true }
                val flow = field.get(repository)
                val original = repository.authHealthFlow.value
                val setter = MutableStateFlow::class.java.getMethod("setValue", Any::class.java)
                restore = { setter.invoke(flow, original); Unit }
                // 只替换展示状态，不写 cookie 或持久化认证数据
                setter.invoke(flow, SavedCookieAuthHealth(state = SavedCookieAuthState.Valid))
                if (favoriteRepository != null) {
                    val favoritesField = favoriteRepository.javaClass.getDeclaredField("_favorites").apply { isAccessible = true }
                    val favoritesFlow = favoritesField.get(favoriteRepository)
                    val originalFavorites = favoriteRepository.favorites.value
                    restoreFavorite = { setter.invoke(favoritesFlow, originalFavorites); Unit }
                    val fixture = FavoritePlaylist(
                        id = artist.artistId, name = artist.name, coverUrl = null,
                        trackCount = artist.musicSize ?: 0, source = FAVORITE_SOURCE_NETEASE_ARTIST, songs = emptyList()
                    )
                    // 只改可见列表的内存快照，不调用关注或持久化入口
                    setter.invoke(favoritesFlow, originalFavorites + fixture)
                }
            }
            body()
        } finally {
            instrumentation.runOnMainSync {
                try {
                    restoreFavorite?.invoke()
                } finally {
                    restore?.invoke()
                }
            }
        }
    }

    private fun artistFixture(shortName: Boolean) = EncyclopediaArtistInfo(
        artistId = 9_541_000_001L,
        name = if (shortName) "小瀬村晶" else LONG_ARTIST_NAME,
        briefDesc = "用于检查艺人姓名、关注按钮和简介在大字号下的布局",
        albumSize = if (shortName) 171 else 123,
        musicSize = if (shortName) 958 else 4567
    )

    private fun nearestClickTarget(node: SemanticsNode): SemanticsNode =
        generateSequence(node) { it.parent }.first { it.config.contains(SemanticsActions.OnClick) }

    private fun textLayout(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
        return results.single()
    }

    private fun capture(name: String) {
        val bitmap = composeRule.onNodeWithTag(VIEWPORT).captureToImage().asAndroidBitmap()
        try {
            context.cacheDir.resolve("$name.png").outputStream().use {
                assertTrue("布局截图应成功保存", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val VIEWPORT = "encyclopedia-artist-layout-viewport"
        const val LONG_ARTIST_NAME = "一位拥有很长姓名的歌手和合作艺人 Artist With A Long Name"
    }
}
