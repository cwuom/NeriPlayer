package moe.ouom.neriplayer.ui.effect.glass

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.P)
class AdvancedGlassOverscrollRenderTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun playlistHeroColorFillsTheTopGapDuringDownwardOverscroll() {
        val overscrollApplied = mutableStateOf(false)

        composeRule.setContent {
            val effect = remember { AdvancedGlassOverscrollFactory.createOverscrollEffect() }
            val layoutReady = remember { mutableStateOf(false) }
            val overscrollOffset = remember { mutableStateOf(0f) }
            CompositionLocalProvider(
                LocalAdvancedGlassOverscrollBackdrop provides AdvancedGlassOverscrollBackdrop(
                    color = Color.Blue,
                    offsetY = overscrollOffset
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Red)
                        .drawAdvancedGlassOverscrollBackdrop(
                            AdvancedGlassOverscrollBackdrop(
                                color = Color.Blue,
                                offsetY = overscrollOffset
                            )
                        )
                        .onGloballyPositioned { layoutReady.value = true }
                        .testTag(PlaylistOverscrollRootTag)
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        overscrollEffect = effect
                    ) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .background(Color.Green)
                            )
                        }
                    }
                }
            }
            LaunchedEffect(effect, layoutReady.value) {
                if (!layoutReady.value) return@LaunchedEffect
                withFrameNanos { }
                effect.applyToScroll(
                    delta = Offset(0f, 64f),
                    source = NestedScrollSource.UserInput,
                    performScroll = { Offset.Zero }
                )
                overscrollApplied.value = effect.isInProgress
            }
        }

        composeRule.waitUntil(timeoutMillis = 3_000) { overscrollApplied.value }
        composeRule.waitForIdle()
        val image = composeRule.onNodeWithTag(PlaylistOverscrollRootTag).captureToImage()
        val pixels = image.toPixelMap()
        val topPixel = pixels[image.width / 2, 8]
        val firstGreenPixel = (0 until image.height).firstOrNull { y ->
            val pixel = pixels[image.width / 2, y]
            pixel.green > 0.9f && pixel.red < 0.1f && pixel.blue < 0.1f
        }

        assertTrue(
            "playlist overscroll exposed the parent instead of the hero color: $topPixel",
            topPixel.blue > 0.9f && topPixel.red < 0.1f
        )
        assertTrue(
            "playlist content did not move below the fixed hero fill",
            firstGreenPixel != null && firstGreenPixel > 8
        )
    }

    private companion object {
        const val PlaylistOverscrollRootTag = "playlist_overscroll_root"
    }
}
