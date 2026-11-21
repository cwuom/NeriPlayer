package moe.ouom.neriplayer.ui.view

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.testutil.captureCommittedFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class HyperBackgroundZeroReactiveRenderTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var composeHost: View
    private lateinit var shaderHost: View
    private val fixtureSize = mutableStateOf(DpSize(128.dp, 96.dp))

    @Before
    fun assumeDeviceUnlocked() = assumeComposeHostAvailable()

    @Test
    fun zeroBeatAndMotionMatchTheFrozenShaderWithinOneChannelLsb() {
        assertMatchesFrozenShader(beatEase = 0f, motionEase = 0f)
    }

    @Test
    fun positiveBeatWithoutMotionMatchesTheFrozenShaderWithinOneChannelLsb() {
        assertMatchesFrozenShader(beatEase = 0.58f, motionEase = 0f)
    }

    @Test
    fun positiveMotionWithoutBeatMatchesTheFrozenShaderWithinOneChannelLsb() {
        assertMatchesFrozenShader(beatEase = 0f, motionEase = 0.65f)
    }

    @Test
    fun positiveBeatAndMotionMatchTheFrozenShaderWithinOneChannelLsb() {
        assertMatchesFrozenShader(beatEase = 0.58f, motionEase = 0.65f)
    }

    @Test
    fun frozenReferenceRespondsToReactiveUniformsAndTime() {
        showFixture()
        val reference = frozenReferenceSource()
        val silent = render(reference, DarkPalette, 0.75f, 0f, 0f)
        try {
            val reactive = render(reference, DarkPalette, 0.75f, 0.58f, 0.65f)
            try {
                assertTrue("reference did not respond to reactive uniforms", changedPixelRatio(silent, reactive) > 0.01)
                val later = render(reference, DarkPalette, 2.25f, 0.58f, 0.65f)
                try {
                    assertTrue("reference did not respond to animation time", changedPixelRatio(reactive, later) > 0.01)
                } finally {
                    later.recycle()
                }
            } finally {
                reactive.recycle()
            }
        } finally {
            silent.recycle()
        }
    }

    private fun assertMatchesFrozenShader(beatEase: Float, motionEase: Float) {
        showFixture()
        val reference = frozenReferenceSource()
        val candidate = InstrumentationRegistry.getInstrumentation().targetContext.assets
            .open(ProductionAsset).bufferedReader().use { it.readText() }
        listOf(DpSize(128.dp, 96.dp), DpSize(96.dp, 128.dp)).forEach { size ->
            composeRule.runOnIdle { fixtureSize.value = size }
            listOf(DarkPalette, LightPalette).forEach { palette ->
                listOf(0.75f, 2.25f).forEach { time ->
                    val case = "${palette.name}, size=$size, time=$time, beat=$beatEase, motion=$motionEase"
                    val referenceImage = render(reference, palette, time, beatEase, motionEase)
                    try {
                        assertNonUniformOpaqueFrame(referenceImage, case)
                        val candidateImage = render(candidate, palette, time, beatEase, motionEase)
                        try {
                            assertEquals("width changed: $case", referenceImage.width, candidateImage.width)
                            assertEquals("height changed: $case", referenceImage.height, candidateImage.height)
                            val maximumDifference = maximumChannelDifference(referenceImage, candidateImage)
                            assertTrue(
                                "shader differs by $maximumDifference channel LSB: $case",
                                maximumDifference <= 1
                            )
                        } finally {
                            candidateImage.recycle()
                        }
                    } finally {
                        referenceImage.recycle()
                    }
                }
            }
        }
    }

    private fun showFixture() {
        composeRule.setContent {
            composeHost = LocalView.current
            Box(Modifier.background(Color.Black)) {
                AndroidView(
                    factory = { context ->
                        View(context).apply {
                            setWillNotDraw(false)
                            setBackgroundColor(AndroidColor.TRANSPARENT)
                            shaderHost = this
                        }
                    },
                    modifier = Modifier
                        .size(fixtureSize.value.width, fixtureSize.value.height)
                        .testTag(ShaderHostTag)
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun render(
        source: String,
        palette: ShaderPalette,
        time: Float,
        beatEase: Float,
        motionEase: Float
    ): Bitmap {
        composeRule.runOnIdle {
            val shader = RuntimeShader(source).apply {
                setFloatUniform("uResolution", shaderHost.width.toFloat(), shaderHost.height.toFloat())
                setFloatUniform("uTranslateY", 0f)
                setFloatUniform("uBound", 0f, 0.4489f, 1f, 0.5511f)
                setFloatUniform("uPoints", Points)
                setFloatUniform("uColors", palette.colors)
                setFloatUniform("uAlphaMulti", 1f)
                setFloatUniform("uSaturateOffset", 0.2f)
                setFloatUniform("uLightOffset", palette.lightOffset)
                setFloatUniform("uAnimTime", time)
                setFloatUniform("uLevelEase", 0.42f)
                setFloatUniform("uBeatEase", beatEase)
                setFloatUniform("uMotionEase", motionEase)
                setFloatUniform("uZoom", 1.08f)
                setFloatUniform("uColorPulse", 0.5f)
                setFloatUniform("uGlobalMotion", 0f, 0f)
            }
            shaderHost.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "uTex"))
            shaderHost.invalidate()
        }
        val bounds = composeRule.onNodeWithTag(ShaderHostTag).fetchSemanticsNode().boundsInRoot
        return composeRule.captureCommittedFrame(composeHost, bounds)
    }

    private fun frozenReferenceSource(): String {
        val source = InstrumentationRegistry.getInstrumentation().context.assets
            .open(ReferenceAsset).bufferedReader().use { it.readText() }
        val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        assertEquals("frozen shader reference changed", ReferenceSha256, digest)
        return source
    }

    private fun assertNonUniformOpaqueFrame(image: Bitmap, case: String) {
        val pixels = pixels(image)
        assertTrue("reference contains transparent pixels: $case", pixels.all { AndroidColor.alpha(it) == 255 })
        val maximumRange = listOf(16, 8, 0).maxOf { shift ->
            pixels.maxOf { (it shr shift) and 0xff } - pixels.minOf { (it shr shift) and 0xff }
        }
        assertTrue("reference is blank or spatially constant: $case", maximumRange > 8)
    }

    private fun maximumChannelDifference(first: Bitmap, second: Bitmap): Int {
        val firstPixels = pixels(first)
        val secondPixels = pixels(second)
        var maximum = 0
        firstPixels.indices.forEach { index ->
            for (shift in 0..24 step 8) {
                val firstChannel = (firstPixels[index] shr shift) and 0xff
                val secondChannel = (secondPixels[index] shr shift) and 0xff
                maximum = max(maximum, abs(firstChannel - secondChannel))
            }
        }
        return maximum
    }

    private fun changedPixelRatio(first: Bitmap, second: Bitmap): Double {
        val firstPixels = pixels(first)
        val secondPixels = pixels(second)
        return firstPixels.indices.count { firstPixels[it] != secondPixels[it] }.toDouble() / firstPixels.size
    }

    private fun pixels(image: Bitmap): IntArray = IntArray(image.width * image.height).also {
        image.getPixels(it, 0, image.width, 0, 0, image.width, image.height)
    }

    private data class ShaderPalette(val name: String, val colors: FloatArray, val lightOffset: Float)

    private companion object {
        const val ShaderHostTag = "hyper-background-zero-reactive-host"
        const val ProductionAsset = "shaders/hyper_background_effect.glsl"
        const val ReferenceAsset = "shaders/hyper_background_effect_zero_reactive_reference.glsl"
        // 冻结 HEAD 51f0100c4ae2982a43f5a1ca3d64399dae02384c，防止基线跟随候选一起变化
        const val ReferenceSha256 = "a45a4a8a4b9029e2dd0b0edfe0adce6043bd760f49d947f9d168af4576dca3c3"
        val Points = floatArrayOf(
            0.52f, 0.46f, 0.92f,
            0.14f, 0.32f, 0.74f,
            0.92f, 0.30f, 0.76f,
            0.26f, 0.88f, 0.80f,
            0.84f, 0.86f, 0.84f
        )
        val DarkPalette = ShaderPalette("dark", floatArrayOf(
            0.07f, 0.27f, 0.42f, 1f,
            0.35f, 0.24f, 0.20f, 1f,
            0.34f, 0.12f, 0.26f, 1f,
            0.17f, 0.14f, 0.34f, 1f,
            0.18f, 0.34f, 0.36f, 1f
        ), -0.1f)
        val LightPalette = ShaderPalette("light", floatArrayOf(
            0.68f, 0.82f, 0.98f, 1f,
            0.96f, 0.85f, 0.74f, 1f,
            0.94f, 0.76f, 0.88f, 1f,
            0.74f, 0.72f, 0.94f, 1f,
            0.80f, 0.88f, 0.92f, 1f
        ), 0.1f)
    }
}
