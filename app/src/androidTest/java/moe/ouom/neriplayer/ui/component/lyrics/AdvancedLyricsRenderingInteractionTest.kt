package moe.ouom.neriplayer.ui.component.lyrics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.abs
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.testutil.UiFailureDiagnostics
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.buildNowPlayingFastLyricsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdvancedLyricsRenderingInteractionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var loaded by mutableStateOf(yrc("Alpha", "Beta", "old translation", 1_000L))
    private var position by mutableLongStateOf(1_800L)
    private val seeks = mutableListOf<Long>()
    private val pressed = mutableListOf<LyricEntry>()

    @Before
    fun setUp() = assumeComposeHostAvailable()

    @Test
    fun asyncYrcPaintsWordProgressAndTranslationThenSeeksAndPressesTheNewLyrics() =
        UiFailureDiagnostics.onFailure("advanced-lyrics-yrc-interaction") {
            render()
            awaitTranslation("old translation")
            val first = lyricRow("old translation").captureToImage()
            val firstMainBottom = mainBandBottom("old translation", first)
            assertInk("逐字原文必须真实绘制", first, 0, firstMainBottom)
            assertTranslationInk("old translation")

            composeRule.runOnIdle { position = 3_500L }
            val progressed = lyricRow("old translation").captureToImage()
            assertEquals(first.width, progressed.width)
            assertEquals(first.height, progressed.height)
            val before = first.toPixelMap()
            val after = progressed.toPixelMap()
            var changed = 0
            for (y in 0 until firstMainBottom) for (x in 0 until before.width) {
                if (abs(before[x, y].red - after[x, y].red) > 0.12f) changed++
            }
            assertTrue("播放位置变化必须推进真实逐字高亮", changed > 20)

            val next = yrc("Gamma", "Delta", "new translation", 6_000L)
            composeRule.runOnIdle {
                loaded = next
                position = 7_800L
            }
            awaitTranslation("new translation")
            composeRule.onAllNodesWithText("old translation", useUnmergedTree = true).assertCountEquals(0)
            assertTranslationInk("new translation")
            val nextFrame = lyricRow("new translation").captureToImage()
            assertInk("切词后必须绘制新原文", nextFrame, 0, mainBandBottom("new translation", nextFrame))

            lyricRow("new translation").performTouchInput { click() }
            composeRule.runOnIdle { assertEquals(listOf(6_000L), seeks) }
            lyricRow("new translation").performTouchInput { longClick() }
            composeRule.runOnIdle {
                assertEquals(1, pressed.size)
                assertSame("长按必须返回新输入的真实 LyricEntry", next.lyrics.single(), pressed.single())
                assertEquals("GammaDelta", pressed.single().text)
                assertEquals(listOf(6_000L), seeks)
            }
        }

    @Test
    fun asyncRawTtmlKeepsItsVisibleLeadAndAccompanimentTranslations() =
        UiFailureDiagnostics.onFailure("advanced-lyrics-ttml-rendering") {
            val raw = """
                <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
                    <body><div><p begin="00:00:01.000" end="00:00:04.000">
                        <span begin="00:00:01.000" end="00:00:04.000">Lead</span>
                        <span ttm:role="x-translation">lead translation</span>
                        <span ttm:role="x-bg" begin="00:00:01.000" end="00:00:04.000">
                            <span begin="00:00:01.000" end="00:00:04.000">Harmony</span>
                            <span ttm:role="x-translation">harmony translation</span>
                        </span>
                    </p></div></body>
                </tt>
            """.trimIndent()
            // 公共入口也接受仅原始 TTML 的调用，保留和声结构而不先压成普通歌词行
            loaded = buildNowPlayingFastLyricsState(null, null, null).copy(rawLyrics = raw)
            position = 2_500L
            render()
            awaitTranslation("lead translation")
            awaitTranslation("harmony translation")
            assertTranslationInk("lead translation")
            assertTranslationInk("harmony translation")
            val row = lyricRow("lead translation")
            val frame = row.captureToImage()
            assertInk("TTML 主唱必须真实绘制", frame, 0, mainBandBottom("lead translation", frame))
            val rowBounds = row.fetchSemanticsNode().boundsInRoot
            val lead = translation("lead translation").fetchSemanticsNode().boundsInRoot
            val harmony = translation("harmony translation").fetchSemanticsNode().boundsInRoot
            val scale = frame.height / rowBounds.height
            assertInk("TTML 和声必须真实绘制", frame,
                ((lead.bottom - rowBounds.top) * scale).toInt() + 2,
                ((harmony.top - rowBounds.top) * scale).toInt() - 2)
        }

    private fun render() {
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalAdvancedLyricsPreparationCache provides loaded.advancedLyricsPreparation) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(360.dp, 640.dp).background(Color.Black).testTag("advanced-lyrics-viewport")) {
                            AdvancedLyricsView(
                                lyrics = loaded.lyrics,
                                currentTimeMs = position,
                                rawLyrics = loaded.rawLyrics,
                                rawTranslatedLyrics = loaded.rawTranslatedLyrics,
                                translatedLyrics = loaded.translatedLyrics,
                                textColor = Color.White,
                                lyricFontScale = 1f,
                                modifier = Modifier.fillMaxSize(),
                                onSeekTo = { seeks.add(it) },
                                onLyricLongClick = { pressed.add(it) }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun awaitTranslation(text: String) {
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().any {
                it.layoutInfo.isPlaced && it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f
            }
        }
        translation(text).assertIsDisplayed()
    }

    private fun lyricRow(text: String): SemanticsNodeInteraction = composeRule.onNode(
        hasClickAction() and hasAnyDescendant(hasText(text)), useUnmergedTree = true
    )

    private fun translation(text: String) = composeRule.onNodeWithText(text, useUnmergedTree = true)

    private fun mainBandBottom(text: String, frame: ImageBitmap): Int {
        val row = lyricRow(text).fetchSemanticsNode().boundsInRoot
        val secondary = translation(text).fetchSemanticsNode().boundsInRoot
        return (((secondary.top - row.top) * frame.height / row.height).toInt() - 2).coerceAtLeast(0)
    }

    private fun assertTranslationInk(text: String) {
        val frame = translation(text).captureToImage()
        assertInk("翻译必须真实绘制: $text", frame, 0, frame.height)
    }

    private fun assertInk(message: String, frame: ImageBitmap, top: Int, bottom: Int) {
        val pixels = frame.toPixelMap()
        var ink = 0
        for (y in top.coerceAtLeast(0) until bottom.coerceAtMost(pixels.height)) {
            for (x in 0 until pixels.width) if (pixels[x, y].red > 0.15f) ink++
        }
        assertTrue("$message: ink=$ink band=[$top,$bottom)", ink > 20)
    }

    private fun yrc(first: String, second: String, translated: String, start: Long) =
        buildNowPlayingFastLyricsState(
            "[$start,3000]($start,1500,0)$first(${start + 1500},1500,0)$second",
            "[${start / 60_000}:${(start / 1_000 % 60).toString().padStart(2, '0')}.00]$translated",
            null
        )
}
