package moe.ouom.neriplayer.ui.screen

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchCandidate
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchSource
import moe.ouom.neriplayer.data.model.lyrics.matching.RankedEditableLyricMatch
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.LyricMatchResultsContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NowPlayingLyricsMatchLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val width = mutableStateOf(840.dp)
    private val height = mutableStateOf(360.dp)
    private val query = mutableStateOf("夜航星 不才")
    private val selectedSources = mutableStateOf(EditableLyricMatchSource.entries.toSet())
    private val results = mutableStateOf((0..29).map(::result))
    private val loading = mutableStateOf(false)
    private val error = mutableStateOf<String?>(null)
    private val searched = mutableStateOf(true)
    private val searches = mutableListOf<String>()
    private val applied = mutableListOf<RankedEditableLyricMatch>()
    private var cancels = 0
    private var parentMotionCount = 0
    private lateinit var focusManager: FocusManager
    private var keyboardController: SoftwareKeyboardController? = null

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun shortLandscapeCanEditQueryToggleEverySourceAndApplyCandidateWithoutNetwork() {
        render(840.dp, 360.dp, 1.3f)
        composeRule.onNodeWithTag("lyrics-match-scroll").assertIsDisplayed()
        EditableLyricMatchSource.entries.forEach { source ->
            scrollTo("lyrics-match-source-${source.name}").assertIsEnabled().performClick()
        }
        scrollTo("lyrics-match-search").assertIsNotEnabled()
        scrollTo("lyrics-match-query").performTextReplacement("新的歌词查询")
        clearFocus()
        scrollTo("lyrics-match-source-KUGOU").performClick()
        scrollTo("lyrics-match-search").assertIsEnabled().performClick()
        scrollTo("lyrics-match-result-29").performClick()
        scrollTo("lyrics-match-cancel").performClick()
        composeRule.onNodeWithTag("lyrics-match-scroll").performTouchInput { swipeDown() }
        composeRule.runOnIdle {
            assertEquals(listOf("新的歌词查询"), searches)
            assertEquals(listOf(result(29)), applied)
            assertEquals(setOf(EditableLyricMatchSource.KUGOU), selectedSources.value)
            assertEquals(1, cancels)
            assertEquals("匹配页滚动不能拖动父sheet", 0, parentMotionCount)
        }
        capture("lyrics-match-840x360")
    }

    @Test
    fun keyboardSizedViewportPreservesQueryAndSourceAcrossLayoutBranches() {
        render(360.dp, 840.dp, 1.3f)
        composeRule.onNodeWithTag("lyrics-match-query").performTextReplacement("键盘前的查询")
        clearFocus()
        composeRule.onNodeWithTag("lyrics-match-source-QQ_MUSIC").performClick()
        val previousSources = selectedSources.value
        composeRule.runOnIdle { width.value = 640.dp; height.value = 180.dp }
        composeRule.waitForIdle()
        scrollTo("lyrics-match-query").performTextReplacement("键盘后的查询")
        clearFocus()
        // 光标跟随和键盘关闭会改变滚动位置，最终视窗稳定后再验证整框可达
        scrollTo("lyrics-match-query")
        val field = composeRule.onNodeWithTag("lyrics-match-query").getUnclippedBoundsInRoot()
        val viewport = composeRule.onNodeWithTag("lyrics-match-layout").getUnclippedBoundsInRoot()
        capture("lyrics-match-keyboard-before-bounds", force = true)
        assertTrue("查询框应完整可见: field=$field viewport=$viewport",
            field.top >= viewport.top && field.bottom <= viewport.bottom)
        scrollTo("lyrics-match-search").assertIsEnabled().performClick()
        scrollTo("lyrics-match-result-29").performClick()
        scrollTo("lyrics-match-cancel").performClick()
        composeRule.runOnIdle {
            assertEquals(previousSources, selectedSources.value)
            assertEquals(listOf("键盘后的查询"), searches)
            assertEquals(listOf(result(29)), applied)
            assertEquals(1, cancels)
        }
        capture("lyrics-match-keyboard-budget")
    }

    @Test
    fun shortLargeFontKeepsLoadingEmptyErrorAndCancelReachable() {
        loading.value = true
        results.value = emptyList()
        render(540.dp, 240.dp, 1.5f)
        scrollTo("lyrics-match-loading")
        scrollTo("lyrics-match-source-YOUTUBE_MUSIC").assertIsNotEnabled()
        scrollTo("lyrics-match-search").assertIsNotEnabled()
        scrollTo("lyrics-match-cancel").performClick()
        composeRule.runOnIdle { loading.value = false }
        composeRule.waitForIdle()
        scrollTo("lyrics-match-empty")
        composeRule.runOnIdle {
            error.value = (1..20).joinToString("\n") { "离线错误信息第${it}行" }
            results.value = listOf(result(29))
        }
        composeRule.waitForIdle()
        scrollTo("lyrics-match-error")
        scrollTo("lyrics-match-result-29").performClick()
        scrollTo("lyrics-match-cancel").performClick()
        composeRule.runOnIdle {
            assertEquals(2, cancels)
            assertEquals(listOf(result(29)), applied)
        }
        capture("lyrics-match-540x240")
    }

    @Test
    fun tallTabletKeepsHeaderAndQueryFixedWhileResultsScroll() {
        render(800.dp, 1280.dp, 1.5f)
        composeRule.onNodeWithTag("lyrics-match-scroll").assertDoesNotExist()
        val header = composeRule.onNodeWithTag("lyrics-match-header").getUnclippedBoundsInRoot()
        val field = composeRule.onNodeWithTag("lyrics-match-query").getUnclippedBoundsInRoot()
        composeRule.onNodeWithTag("lyrics-match-results").performScrollToNode(hasTestTag("lyrics-match-result-29"))
        composeRule.onNodeWithTag("lyrics-match-result-29").assertIsDisplayed().performClick()
        assertEquals(header, composeRule.onNodeWithTag("lyrics-match-header").getUnclippedBoundsInRoot())
        assertEquals(field, composeRule.onNodeWithTag("lyrics-match-query").getUnclippedBoundsInRoot())
        composeRule.runOnIdle { assertEquals(listOf(result(29)), applied) }
        capture("lyrics-match-tablet-portrait")
    }

    private fun scrollTo(tag: String): SemanticsNodeInteraction {
        if (composeRule.onAllNodesWithTag("lyrics-match-scroll").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag("lyrics-match-scroll").performScrollToNode(hasTestTag(tag))
        } else if (tag.startsWith("lyrics-match-result-")) {
            composeRule.onNodeWithTag("lyrics-match-results").performScrollToNode(hasTestTag(tag))
        }
        return composeRule.onNodeWithTag(tag).assertIsDisplayed()
    }

    private fun clearFocus() {
        composeRule.runOnIdle {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
        composeRule.waitForIdle()
    }

    private fun render(initialWidth: Dp, initialHeight: Dp, fontScale: Float) {
        width.value = initialWidth
        height.value = initialHeight
        composeRule.setContent {
            MaterialTheme {
                val currentFocusManager = LocalFocusManager.current
                val currentKeyboardController = LocalSoftwareKeyboardController.current
                SideEffect {
                    focusManager = currentFocusManager
                    keyboardController = currentKeyboardController
                }
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val density = LocalDensity.current
                    val scale = minOf(maxWidth.value / width.value.value, maxHeight.value / height.value.value)
                    CompositionLocalProvider(LocalDensity provides Density(density.density * scale, fontScale)) {
                        val parentConnection = remember {
                            object : NestedScrollConnection {
                                override fun onPostScroll(consumed: Offset, available: Offset,
                                    source: NestedScrollSource): Offset {
                                    if (available.y != 0f) parentMotionCount++
                                    return available
                                }
                                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                                    if (available.y != 0f) parentMotionCount++
                                    return available
                                }
                            }
                        }
                        Box(Modifier.requiredSize(width.value, height.value).nestedScroll(parentConnection)) {
                            LyricMatchResultsContent(query.value, { query.value = it }, results.value,
                                loading.value, error.value, searched.value, selectedSources.value,
                                onSourceToggle = { source ->
                                    selectedSources.value = selectedSources.value.let {
                                        if (source in it) it - source else it + source
                                    }
                                }, onSearch = { searches += it }, onApply = { applied += it },
                                onDismiss = { cancels++ })
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun capture(stage: String, force: Boolean = false) {
        val prefix = InstrumentationRegistry.getArguments().getString("capturePrefix")
            ?.replace(Regex("[^a-zA-Z0-9_-]"), "-")?.take(80)?.takeIf { it.isNotBlank() }
            ?: if (force) "lyrics-match-regression" else return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val file = File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "$prefix-$stage.png")
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        fun result(index: Int) = RankedEditableLyricMatch(
            EditableLyricMatchCandidate(index.toString(), EditableLyricMatchSource.KUGOU,
                "离线候选$index", "歌手$index", durationMs = 222_000L, lyrics = "候选歌词$index"),
            score = 90, durationDeltaMs = 0L)
    }
}
