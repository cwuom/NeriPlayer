package moe.ouom.neriplayer.ui.screen.lyrics

import android.content.res.Configuration
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricFontScales
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LyricsScreenPortraitLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var logicalWidth = 0f
    private var navigatedBack = 0
    private var seekPosition: Long? = null

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun tabletPortraitUsesCenteredReadingAndCompactPlaybackWidths() {
        render(800.dp, 1280.dp, smallestScreenWidth = 800)

        assertCenteredWidth("lyricsScreenHeader", 560.dp)
        assertCenteredWidth("lyricsScreenReadingPane", 560.dp)
        assertCenteredWidth("lyricsScreenControlPanel", 440.dp)
        assertCenteredWidth("lyricsScreenActionToolbar", 400.dp)
        assertTrue(bounds("lyricsScreenReadingPane").height >= logicalPixels(480.dp))
        assertTrue(bounds("lyricsScreenProgress").width <= logicalPixels(440.dp))
        assertTrue(bounds("lyricsScreenPlaybackControls").width <= logicalPixels(440.dp))
        assertOrderedPanels()
        capture("lyrics-tablet-portrait")

        composeRule.onNode(hasText("Verse 1") and hasClickAction()).assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1_000L, seekPosition) }
        composeRule.onNodeWithTag("lyricsScreen").performTouchInput { swipeRight(durationMillis = 60) }
        composeRule.runOnIdle { assertTrue(navigatedBack > 0) }
    }

    @Test
    fun smallerPortraitTabletKeepsLargeTextAndEveryPlaybackPanelInsideTheScreen() {
        render(600.dp, 960.dp, smallestScreenWidth = 600, fontScale = 1.5f)

        assertCenteredWidth("lyricsScreenHeader", 560.dp)
        assertCenteredWidth("lyricsScreenReadingPane", 560.dp)
        assertCenteredWidth("lyricsScreenControlPanel", 440.dp)
        assertCenteredWidth("lyricsScreenActionToolbar", 400.dp)
        assertTrue(bounds("lyricsScreenReadingPane").height >= logicalPixels(320.dp))
        assertOrderedPanels()
        capture("lyrics-tablet-compact-portrait")
    }

    @Test
    fun phonePortraitRetainsItsFullWidthSections() {
        render(360.dp, 800.dp, smallestScreenWidth = 360)

        listOf(
            "lyricsScreenHeader", "lyricsScreenReadingPane",
            "lyricsScreenControlPanel", "lyricsScreenActionToolbar"
        ).forEach { assertCenteredWidth(it, 320.dp) }
        assertOrderedPanels()
    }

    private fun render(width: Dp, height: Dp, smallestScreenWidth: Int, fontScale: Float = 1f) {
        logicalWidth = width.value
        val lyrics = (1..6).map { index -> LyricEntry("Verse $index", index * 1_000L, (index + 1) * 1_000L) }
        composeRule.setContent {
            MaterialTheme {
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val density = LocalDensity.current
                    val scale = minOf(maxWidth.value / width.value, maxHeight.value / height.value)
                    val configuration = Configuration(LocalConfiguration.current).apply {
                        smallestScreenWidthDp = smallestScreenWidth
                        orientation = Configuration.ORIENTATION_PORTRAIT
                        screenWidthDp = width.value.toInt()
                        screenHeightDp = height.value.toInt()
                    }
                    CompositionLocalProvider(
                        LocalDensity provides Density(density.density * scale, fontScale),
                        LocalConfiguration provides configuration
                    ) {
                        Box(
                            Modifier.requiredSize(width, height)
                                .background(MaterialTheme.colorScheme.background)
                                .testTag("viewport")
                        ) {
                            LyricsScreen(
                                lyrics = lyrics,
                                lyricBlurEnabled = false,
                                lyricBlurAmount = 0f,
                                lyricFontScales = LyricFontScales(1f, 1f, 1.3f, 1.2f),
                                onLyricFontScaleChange = { _, _ -> },
                                onEnterAlbum = {},
                                onExitNowPlaying = {},
                                onNavigateBack = { navigatedBack++ },
                                onSeekTo = { seekPosition = it },
                                advancedLyricsEnabled = false,
                                lyricSourceKey = null,
                                secondaryLyricsResolved = false,
                                lyricOffsetMs = 0L,
                                offlineMode = true
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun assertCenteredWidth(tag: String, expectedWidth: Dp) {
        val section = bounds(tag)
        val viewport = bounds("viewport")
        assertEquals(logicalPixels(expectedWidth), section.width, 1f)
        assertEquals(viewport.center.x, section.center.x, 1f)
        assertTrue(section.left >= viewport.left - 1f && section.right <= viewport.right + 1f)
        assertTrue(section.top >= viewport.top - 1f && section.bottom <= viewport.bottom + 1f)
    }

    private fun assertOrderedPanels() {
        assertTrue(bounds("lyricsScreenHeader").bottom <= bounds("lyricsScreenReadingPane").top)
        assertTrue(bounds("lyricsScreenReadingPane").bottom <= bounds("lyricsScreenControlPanel").top)
        assertTrue(bounds("lyricsScreenControlPanel").bottom <= bounds("lyricsScreenActionToolbar").top)
    }

    private fun bounds(tag: String): Rect = composeRule.onNodeWithTag(tag, useUnmergedTree = true)
        .fetchSemanticsNode().boundsInRoot

    private fun logicalPixels(dp: Dp): Float = bounds("viewport").width / logicalWidth * dp.value

    private fun capture(stage: String) {
        val prefix = InstrumentationRegistry.getArguments().getString("capturePrefix")
            ?.replace(Regex("[^a-zA-Z0-9_-]"), "-")?.take(60)?.takeIf(String::isNotBlank) ?: return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(checkNotNull(context.getExternalFilesDir(null)), "$prefix-$stage.png")
        val bitmap = composeRule.onNodeWithTag("viewport").captureToImage().asAndroidBitmap()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        Log.i("LyricsPortraitLayoutTest", "screenshot=${file.absolutePath}")
    }
}
