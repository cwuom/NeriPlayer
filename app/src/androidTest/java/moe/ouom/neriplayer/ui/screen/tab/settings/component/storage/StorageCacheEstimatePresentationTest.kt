package moe.ouom.neriplayer.ui.screen.tab.settings.component.storage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.format.formatFileSize
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.model.storage.PlayerCacheClearEstimate
import moe.ouom.neriplayer.data.model.storage.StorageCacheClearOptions
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageCacheEstimatePresentationTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var estimate by mutableStateOf(PlayerCacheClearEstimate(64L * CACHE_PREVIEW_MIB, 8L * CACHE_PREVIEW_MIB))
    private var pending by mutableStateOf(false)

    @Before
    fun requireComposeHost() = assumeComposeHostAvailable()

    @Test
    fun occupiedBytesRemainVisibleWhenProtectedCachesHaveNoReclaimablePayload() {
        estimate = PlayerCacheClearEstimate(0L, 0L)
        render()

        composeRule.onNodeWithTag("cache_occupied_Audio", useUnmergedTree = true).assertTextEquals(
            context.getString(CoreCommonR.string.storage_cleanup_occupied_size, formatFileSize(64L * CACHE_PREVIEW_MIB))
        )
        composeRule.onNodeWithTag("cache_occupied_Image", useUnmergedTree = true).assertTextEquals(
            context.getString(CoreCommonR.string.storage_cleanup_occupied_size, formatFileSize(8L * CACHE_PREVIEW_MIB))
        )
    }

    @Test
    fun recomputingDoesNotMoveTheRetentionSlider() {
        render()
        val slider = composeRule.onNodeWithTag("cache_retention_slider", useUnmergedTree = true)
        slider.performScrollTo()
        val before = slider.fetchSemanticsNode().boundsInRoot.top

        composeRule.runOnIdle { pending = true }
        composeRule.waitForIdle()

        assertEquals(before, slider.fetchSemanticsNode().boundsInRoot.top, 1f)
    }

    @Test
    fun updatedCapacityTransitionsThroughAnIntermediateFrame() {
        render()
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle { estimate = PlayerCacheClearEstimate(4L * CACHE_PREVIEW_MIB, CACHE_PREVIEW_MIB) }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(80L)
        val intermediate = composeRule.onRoot().captureToImage()
        captureCacheCleanupScreenshot("cache-estimate-transition-mid.png")
        composeRule.mainClock.advanceTimeBy(1_000L)
        val settled = composeRule.onRoot().captureToImage()
        captureCacheCleanupScreenshot("cache-estimate-transition-settled.png")

        assertTrue("容量更新应经过可见过渡，不能直接跳到最终值", differentPixels(intermediate, settled) > 20)
    }

    private fun render() {
        val summary = cacheCleanupPreviewSummary()
        composeRule.setContent {
            NeriTheme(
                followSystemDark = false, forceDark = false, dynamicColor = false,
                seedColorHex = "6750A4", uiStyle = UiStyle.Clean, animateColors = false
            ) {
                Box(Modifier.width(400.dp).height(720.dp)) {
                    StorageCacheCleanupContent(
                        storageDetails = summary, isScanning = false, hasScanned = true,
                        isClearing = false, options = StorageCacheClearOptions(),
                        onOptionsChange = {}, onClear = {}, onDismiss = {},
                        estimate = estimate, isEstimating = pending
                    )
                }
            }
        }
    }

    private fun differentPixels(first: ImageBitmap, second: ImageBitmap): Int {
        assertEquals(first.width, second.width)
        assertEquals(first.height, second.height)
        val a = first.toPixelMap()
        val b = second.toPixelMap()
        var count = 0
        for (y in 0 until first.height) for (x in 0 until first.width) {
            if (a[x, y] != b[x, y]) count++
        }
        return count
    }
}
