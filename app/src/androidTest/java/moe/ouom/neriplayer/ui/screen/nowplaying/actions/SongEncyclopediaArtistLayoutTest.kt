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
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.auth.SavedCookieAuthHealth
import moe.ouom.neriplayer.data.model.auth.SavedCookieAuthState
import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaArtistInfo
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
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

    private fun checkLayout(style: UiStyle, dark: Boolean, landscape: Boolean = false) = withSyntheticAuth {
        render(style, dark, landscape)
        composeRule.waitForIdle()
        capture(
            "encyclopedia-artist-${style.name.lowercase()}-${if (dark) "dark" else "light"}-" +
                if (landscape) "landscape" else "narrow"
        )

        val name = composeRule.onNodeWithText(LONG_ARTIST_NAME, useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode()
        val followLabel = context.getString(CoreCommonR.string.artist_follow)
        val followText = composeRule.onNodeWithText(followLabel, useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode()
        val navigation = nearestClickTarget(name)
        val follow = nearestClickTarget(followText)
        val viewport = composeRule.onNodeWithTag(VIEWPORT).fetchSemanticsNode().boundsInRoot

        assertTrue("长艺人名仍应分到可显示宽度", name.boundsInRoot.width > 1f)
        assertTrue("关注按钮应位于姓名右边", follow.boundsInRoot.left >= name.boundsInRoot.right - 1f)
        assertTrue("关注按钮与姓名应处于同一行", follow.boundsInRoot.top < name.boundsInRoot.bottom)
        assertTrue("姓名与关注按钮应处于同一行", name.boundsInRoot.top < follow.boundsInRoot.bottom)
        assertTrue("关注按钮不能超出面板右边界", follow.boundsInRoot.right <= viewport.right + 1f)
        assertTrue("艺人导航和关注触摸区域不能重叠", navigation.boundsInRoot.right <= follow.boundsInRoot.left + 1f)
        assertFalse(
            "关注按钮不能嵌套在艺人导航点击节点中",
            generateSequence(follow) { it.parent }.any { it.id == navigation.id }
        )

        val metaText = context.getString(CoreCommonR.string.encyclopedia_artist_bio_note, 123, 4567)
        val meta = composeRule.onNodeWithText(metaText, useUnmergedTree = true).fetchSemanticsNode()
        assertTrue("艺人元信息应独占姓名和关注按钮下方一行", meta.boundsInRoot.top >= follow.boundsInRoot.bottom - 1f)
        assertTrue("艺人元信息应使用右侧整列宽度", meta.boundsInRoot.right >= follow.boundsInRoot.right - 1f)
        assertTrue("窄屏元信息不应被关注按钮挤成多行窄列", textLayout(metaText).lineCount <= 2)

        val nameLayout = textLayout(LONG_ARTIST_NAME)
        assertEquals("长艺人名应保持单行", 1, nameLayout.lineCount)
        assertTrue("长艺人名应在单行内显示省略号", nameLayout.isLineEllipsized(0))
        // didOverflowHeight 也包含超过 maxLines 的正常省略，实际行框需单独检查
        assertTrue("艺人名单行顶部不能被裁切", nameLayout.getLineTop(0) >= -1f)
        assertTrue("艺人名单行底部应完整容纳", nameLayout.getLineBottom(0) <= nameLayout.size.height + 1f)
        assertFalse("关注文案不能纵向裁切", textLayout(followLabel).didOverflowHeight)
    }

    private fun render(style: UiStyle, dark: Boolean, landscape: Boolean) {
        composeRule.setContent {
            val width = if (landscape) 640 else 320
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
                    FittedTestViewport(width.dp, height.dp, fontScale = 1.3f, modifier = Modifier.testTag(VIEWPORT)) {
                        Column(
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
                                .padding(horizontal = NeriDesign.tokens.pageHorizontalPadding, vertical = 12.dp)
                        ) {
                            SongEncyclopediaArtistSection(
                                artist = EncyclopediaArtistInfo(
                                    artistId = 9_541_000_001L,
                                    name = LONG_ARTIST_NAME,
                                    briefDesc = "用于检查艺人姓名、关注按钮和简介在大字号下的布局",
                                    albumSize = 123,
                                    musicSize = 4567
                                ),
                                onOpenArtist = {}
                            )
                        }
                    }
                }
            }
        }
    }

    private fun withSyntheticAuth(body: () -> Unit) {
        var restore: (() -> Unit)? = null
        try {
            instrumentation.runOnMainSync {
                val repository = AppContainer.neteaseCookieRepo
                val field = repository.javaClass.getDeclaredField("_authHealthFlow").apply { isAccessible = true }
                val flow = field.get(repository)
                val original = repository.authHealthFlow.value
                val setter = MutableStateFlow::class.java.getMethod("setValue", Any::class.java)
                restore = { setter.invoke(flow, original); Unit }
                // 只替换展示状态，不写 cookie 或持久化认证数据
                setter.invoke(flow, SavedCookieAuthHealth(state = SavedCookieAuthState.Valid))
            }
            body()
        } finally {
            instrumentation.runOnMainSync { restore?.invoke() }
        }
    }

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
