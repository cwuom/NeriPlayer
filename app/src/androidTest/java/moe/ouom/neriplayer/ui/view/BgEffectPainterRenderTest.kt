package moe.ouom.neriplayer.ui.view

import android.graphics.Color as AndroidColor
import android.graphics.RenderEffect
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class BgEffectPainterRenderTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var referencePainter: BgEffectPainter
    private lateinit var directPainter: BgEffectPainter
    private lateinit var referenceView: View
    private lateinit var directView: HyperBackgroundShaderView

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun directCanvasRendering_matchesRenderEffectAndTracksUniformUpdates() {
        composeRule.setContent {
            Row {
                AndroidView(
                    factory = { context ->
                        referencePainter = BgEffectPainter(context.applicationContext)
                        View(context).apply {
                            setWillNotDraw(false)
                            setBackgroundColor(AndroidColor.TRANSPARENT)
                            referenceView = this
                        }
                    },
                    modifier = Modifier
                        .size(testViewSize)
                        .testTag(ReferenceTag)
                )
                AndroidView(
                    factory = { context ->
                        directPainter = BgEffectPainter(context.applicationContext)
                        HyperBackgroundShaderView(context).apply {
                            effectPainter = directPainter
                            directView = this
                        }
                    },
                    modifier = Modifier
                        .size(testViewSize)
                        .testTag(DirectTag)
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = DrawTimeoutMs) {
            ::referenceView.isInitialized &&
                ::directView.isInitialized &&
                referenceView.width > 0 &&
                directView.width > 0
        }
        awaitDraws(referenceView, directView) {
            configurePainter(referencePainter, InitialAnimTime)
            referencePainter.setResolution(referenceView.width.toFloat(), referenceView.height.toFloat())
            referenceView.setRenderEffect(
                RenderEffect.createRuntimeShaderEffect(referencePainter.mBgRuntimeShader, "uTex")
            )
            configurePainter(directPainter, InitialAnimTime)
        }

        val referenceImage = composeRule.onNodeWithTag(ReferenceTag).captureToImage()
        val directImage = composeRule.onNodeWithTag(DirectTag).captureToImage()
        val comparison = compareImages(referenceImage, directImage)

        assertTrue("reference image must contain rendered pixels", comparison.opaquePixelRatio > 0.99)
        assertTrue(
            "direct shader differs from RenderEffect: mean=${comparison.meanChannelDifference}, " +
                "outliers=${comparison.outlierPixelRatio}",
            comparison.meanChannelDifference <= MaxMeanChannelDifference &&
                comparison.outlierPixelRatio <= MaxOutlierPixelRatio
        )

        val initialDirectImage = directImage
        awaitDraws(directView) {
            configurePainter(directPainter, UpdatedAnimTime)
        }
        val updatedDirectImage = composeRule.onNodeWithTag(DirectTag).captureToImage()
        val changedPixelRatio = changedPixelRatio(initialDirectImage, updatedDirectImage)

        assertTrue(
            "RuntimeShader uniforms did not update through Canvas drawing: changed=$changedPixelRatio",
            changedPixelRatio >= MinChangedPixelRatio
        )
    }

    @Test
    fun hyperBackgroundComposable_rendersDynamicShader() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            Box(
                modifier = Modifier
                    .size(testViewSize)
                    .background(Color.Black)
            ) {
                HyperBackground(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(ProductionTag),
                    isDark = true,
                    coverUrl = null
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(200L)
        composeRule.waitForIdle()
        val image = composeRule.onNodeWithTag(ProductionTag).captureToImage()
        val coloredPixelRatio = coloredPixelRatio(image)

        assertTrue(
            "HyperBackground rendered transparent or black: colored=$coloredPixelRatio",
            coloredPixelRatio >= MinColoredPixelRatio
        )
    }

    private fun configurePainter(painter: BgEffectPainter, animTime: Float) {
        painter.setAnimTime(animTime)
        painter.setReactive(TestMusicLevel, TestBeat)
        painter.updateMaterials()
    }

    private fun awaitDraws(vararg views: View, update: () -> Unit) {
        val drawLatch = CountDownLatch(views.size)
        val listeners = views.associateWith { view ->
            ViewTreeObserver.OnDrawListener { drawLatch.countDown() }
        }
        composeRule.runOnIdle {
            listeners.forEach { (view, listener) ->
                view.viewTreeObserver.addOnDrawListener(listener)
            }
            update()
            views.forEach(View::postInvalidateOnAnimation)
        }
        assertTrue("views did not draw in time", drawLatch.await(DrawTimeoutMs, TimeUnit.MILLISECONDS))
        composeRule.runOnIdle {
            listeners.forEach { (view, listener) ->
                if (view.viewTreeObserver.isAlive) {
                    view.viewTreeObserver.removeOnDrawListener(listener)
                }
            }
        }
    }

    private fun compareImages(first: ImageBitmap, second: ImageBitmap): ImageComparison {
        assertEquals(first.width, second.width)
        assertEquals(first.height, second.height)
        val firstPixels = first.toPixelMap()
        val secondPixels = second.toPixelMap()
        var channelDifferenceSum = 0.0
        var opaquePixels = 0
        var outlierPixels = 0
        var comparedPixels = 0

        for (y in PixelInset until first.height - PixelInset) {
            for (x in PixelInset until first.width - PixelInset) {
                val firstColor = firstPixels[x, y]
                val secondColor = secondPixels[x, y]
                val maxDifference = max(
                    max(abs(firstColor.red - secondColor.red), abs(firstColor.green - secondColor.green)),
                    max(abs(firstColor.blue - secondColor.blue), abs(firstColor.alpha - secondColor.alpha))
                )
                channelDifferenceSum += maxDifference
                if (firstColor.alpha >= OpaqueAlphaThreshold) opaquePixels++
                if (maxDifference > OutlierChannelDifference) outlierPixels++
                comparedPixels++
            }
        }

        return ImageComparison(
            meanChannelDifference = channelDifferenceSum / comparedPixels,
            opaquePixelRatio = opaquePixels.toDouble() / comparedPixels,
            outlierPixelRatio = outlierPixels.toDouble() / comparedPixels
        )
    }

    private fun changedPixelRatio(first: ImageBitmap, second: ImageBitmap): Double {
        assertEquals(first.width, second.width)
        assertEquals(first.height, second.height)
        val firstPixels = first.toPixelMap()
        val secondPixels = second.toPixelMap()
        var changedPixels = 0
        var comparedPixels = 0

        for (y in PixelInset until first.height - PixelInset) {
            for (x in PixelInset until first.width - PixelInset) {
                val firstColor = firstPixels[x, y]
                val secondColor = secondPixels[x, y]
                val maxDifference = max(
                    max(abs(firstColor.red - secondColor.red), abs(firstColor.green - secondColor.green)),
                    max(abs(firstColor.blue - secondColor.blue), abs(firstColor.alpha - secondColor.alpha))
                )
                if (maxDifference > ChangedChannelDifference) changedPixels++
                comparedPixels++
            }
        }

        return changedPixels.toDouble() / comparedPixels
    }

    private fun coloredPixelRatio(image: ImageBitmap): Double {
        val pixels = image.toPixelMap()
        var coloredPixels = 0
        var comparedPixels = 0
        for (y in PixelInset until image.height - PixelInset) {
            for (x in PixelInset until image.width - PixelInset) {
                val color = pixels[x, y]
                if (max(color.red, max(color.green, color.blue)) > MinVisibleColorChannel) {
                    coloredPixels++
                }
                comparedPixels++
            }
        }
        return coloredPixels.toDouble() / comparedPixels
    }

    private data class ImageComparison(
        val meanChannelDifference: Double,
        val opaquePixelRatio: Double,
        val outlierPixelRatio: Double
    )

    private companion object {
        val testViewSize = 128.dp
        const val ReferenceTag = "render-effect-reference"
        const val DirectTag = "direct-runtime-shader"
        const val ProductionTag = "production-hyper-background"
        const val DrawTimeoutMs = 5_000L
        const val PixelInset = 2
        const val InitialAnimTime = 0.75f
        const val UpdatedAnimTime = 2.25f
        const val TestMusicLevel = 0.42f
        const val TestBeat = 0.58f
        const val OpaqueAlphaThreshold = 0.99f
        const val OutlierChannelDifference = 2f / 255f
        const val ChangedChannelDifference = 1f / 255f
        const val MaxMeanChannelDifference = 1.5 / 255.0
        const val MaxOutlierPixelRatio = 0.01
        const val MinChangedPixelRatio = 0.25
        const val MinVisibleColorChannel = 0.02f
        const val MinColoredPixelRatio = 0.95
    }
}
