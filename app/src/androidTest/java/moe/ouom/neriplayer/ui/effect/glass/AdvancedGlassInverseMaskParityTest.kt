package moe.ouom.neriplayer.ui.effect.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.testutil.captureCommittedFrame
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class AdvancedGlassInverseMaskParityTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var composeHost: View
    private lateinit var patternView: PatternView
    private val effect = mutableStateOf<RenderEffect?>(null)
    private val backdrop = mutableStateOf(Color.Black)
    private val evidenceDirectory by lazy {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, "glass-inverse-parity/${UUID.randomUUID()}").apply {
            check(mkdirs() || isDirectory) { "could not create parity evidence directory: $this" }
        }
    }

    @Before
    fun assumeDeviceUnlocked() = assumeComposeHostAvailable()

    @Test
    fun productionGraphMatchesFrozenGraphForOpaqueAndTransparentInputs() {
        showFixture()
        val reference = FrozenAdvancedGlassEffectSession(frozenAdvancedGlassMaskSource())
        val production = productionSession()
        for (kind in SourceKind.entries) {
            for (background in listOf(Color.Black, Color.White)) {
                composeRule.runOnIdle {
                    patternView.sourceKind = kind
                    backdrop.value = background
                }
                for (case in cases()) {
                    val expected = render(updated(reference, case))
                    try {
                        assertNonUniform(expected, "reference ${case.name}/$kind/$background")
                        val actual = render(updated(production, case))
                        try {
                            if (case.name == "one-asymmetric" && background == Color.Black) {
                                saveEvidence(expected, actual, kind)
                            }
                            assertEquivalent(expected, actual, "${case.name}/$kind/$background", "matrix")
                        } finally {
                            actual.recycle()
                        }
                    } finally {
                        expected.recycle()
                    }
                }
            }
        }
    }

    @Test
    fun sameSessionUpdatesDoNotPollutePreviouslyBoundEffects() {
        showFixture()
        val reference = FrozenAdvancedGlassEffectSession(frozenAdvancedGlassMaskSource())
        val production = productionSession()
        val firstCase = cases().first { it.name == "one-asymmetric" }
        val firstReference = updated(reference, firstCase)
        val firstProduction = updated(production, firstCase)
        val initial = render(firstProduction)
        try {
            val initialReference = render(firstReference)
            try {
                assertEquivalent(initialReference, initial, "initial bind", "initial")
            } finally {
                initialReference.recycle()
            }
            for (step in 1..6) {
                val changed = firstCase.copy(
                    name = "dynamic-$step",
                    regions = firstCase.regions.map { region ->
                        region.copy(
                            left = region.left + step * 7.25f,
                            right = region.right + step * 7.25f,
                            opacity = 0.1f + step * 0.1f
                        )
                    }
                )
                val nextReference = updated(reference, changed)
                val nextProduction = updated(production, changed)
                val expected = render(nextReference)
                try {
                    val actual = render(nextProduction)
                    try {
                        assertEquivalent(expected, actual, "current ${changed.name}", "update")
                        assertTrue("dynamic fixture did not change", maximumDifference(initial, actual) > 1)
                    } finally {
                        actual.recycle()
                    }
                } finally {
                    expected.recycle()
                }
                val retained = render(firstProduction)
                try {
                    assertEquivalent(initial, retained, "retained old effect after step $step", "retained")
                } finally {
                    retained.recycle()
                }
            }
        } finally {
            initial.recycle()
        }
    }

    @Test
    fun frozenOracleRespondsToCoverageBlurAndLiquidTieOrder() {
        showFixture()
        val reference = FrozenAdvancedGlassEffectSession(frozenAdvancedGlassMaskSource())
        val case = cases().first { it.name == "one-asymmetric" }
        val base = render(updated(reference, case))
        try {
            for (changed in listOf(
                case.copy(name = "lower-opacity", regions = case.regions.map { it.copy(opacity = 0.25f) }),
                case.copy(name = "different-radius", radius = 3f),
                case.copy(name = "different-position", regions = case.regions.map {
                    it.copy(left = it.left + patternView.width * 0.4f, right = it.right + patternView.width * 0.4f)
                })
            )) {
                val changedImage = render(updated(reference, changed))
                try {
                    assertTrue("inactive oracle: ${changed.name}", maximumDifference(base, changedImage) > 1)
                } finally {
                    changedImage.recycle()
                }
            }
        } finally {
            base.recycle()
        }
        val tied = cases().first { it.name == "tied-lens-zero-first" }
        val first = render(updated(reference, tied))
        try {
            val reversed = render(updated(reference, tied.copy(regions = tied.regions.reversed())))
            try {
                assertTrue("oracle lost liquid tie order", maximumDifference(first, reversed) > 1)
            } finally {
                reversed.recycle()
            }
        } finally {
            first.recycle()
        }
    }

    private fun productionSession(): AdvancedGlassRenderEffectSession {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        return createAdvancedGlassRenderEffectSession(AdvancedGlassShaderSource(assets), Build.VERSION.SDK_INT)
    }

    private fun updated(session: AdvancedGlassRenderEffectSession, case: FrameCase): RenderEffect {
        lateinit var result: RenderEffect
        composeRule.runOnIdle {
            result = requireNotNull(session.update(case.radius, case.regions, case.lens))
        }
        return result
    }

    private fun showFixture() {
        composeRule.setContent {
            composeHost = LocalView.current
            Box(Modifier.background(backdrop.value)) {
                Box(
                    Modifier.size(128.dp, 96.dp).graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                        renderEffect = effect.value
                    }.testTag(HostTag)
                ) {
                    AndroidView(
                        factory = { context -> PatternView(context).also { patternView = it } },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertTrue("fixture requires hardware rendering", patternView.isHardwareAccelerated)
            assertTrue("fixture was not laid out", patternView.width > 32 && patternView.height > 32)
        }
    }

    private fun render(nextEffect: RenderEffect): Bitmap {
        composeRule.runOnIdle { effect.value = nextEffect }
        composeRule.waitForIdle()
        val bounds = composeRule.onNodeWithTag(HostTag).fetchSemanticsNode().boundsInRoot
        return composeRule.captureCommittedFrame(composeHost, bounds)
    }

    private fun cases(): List<FrameCase> {
        val width = patternView.width.toFloat()
        val height = patternView.height.toFloat()
        val one = AdvancedGlassRenderRegion(
            left = width * 0.1f + 0.25f,
            top = height * 0.1f + 0.5f,
            right = width * 0.85f + 0.75f,
            bottom = height * 0.9f,
            cornerRadiiPx = AdvancedGlassCornerRadii(0f, 5.5f, 27f, 53f),
            lens = 1f
        )
        val lens = AdvancedGlassLensSpec(0.42f, 14f, 1.6f, 0.16f)
        val many = List(32) { index ->
            val left = width * (-0.04f + (index % 8) * 0.13f)
            val top = height * (-0.04f + (index / 8) * 0.26f)
            one.copy(
                left = left,
                top = top,
                right = left + width * 0.2f,
                bottom = top + height * 0.35f,
                cornerRadiiPx = AdvancedGlassCornerRadii(0f, 3.5f, 15f, 1000f),
                opacity = listOf(0f, 0.25f, 0.5f, 1f)[index % 4],
                lens = (index % 2).toFloat()
            )
        }
        val tied = listOf(one.copy(lens = 0f), one.copy(lens = 1f))
        return listOf(
            FrameCase("one-asymmetric", 24f, listOf(one), lens),
            FrameCase("one-frosted", 24f, listOf(one), AdvancedGlassLensSpec.None),
            FrameCase("one-zero-opacity", 24f, listOf(one.copy(opacity = 0f)), lens),
            FrameCase("one-quarter-opacity", 24f, listOf(one.copy(opacity = 0.25f)), lens),
            FrameCase("one-half-opacity", 24f, listOf(one.copy(opacity = 0.5f)), lens),
            FrameCase("one-large-radius", 64f, listOf(one), lens),
            FrameCase("one-small-radius", 2f, listOf(one), lens),
            FrameCase("thirty-two-overlap-liquid", 24f, many, lens),
            FrameCase("thirty-two-overlap-frosted", 24f, many, AdvancedGlassLensSpec.None),
            FrameCase("tied-lens-zero-first", 24f, tied, lens),
            FrameCase("tied-lens-one-first", 24f, tied.reversed(), lens)
        )
    }

    private fun assertNonUniform(image: Bitmap, case: String) {
        val pixels = pixels(image)
        val range = listOf(0, 8, 16).maxOf { shift ->
            pixels.maxOf { (it ushr shift) and 255 } - pixels.minOf { (it ushr shift) and 255 }
        }
        assertTrue("blank or spatially constant reference: $case", range > 16)
    }

    private fun assertEquivalent(expected: Bitmap, actual: Bitmap, case: String, phase: String) {
        assertEquals("width: $case", expected.width, actual.width)
        assertEquals("height: $case", expected.height, actual.height)
        val difference = maximumDifference(expected, actual)
        val status = JSONObject()
            .put("phase", phase)
            .put("case", case)
            .put("width", expected.width)
            .put("height", expected.height)
            .put("max_rgba_lsb", difference)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("glass_parity_case", status.toString())
        })
        assertTrue("maximum RGBA difference=$difference LSB: $case", difference <= 1)
    }

    private fun saveEvidence(reference: Bitmap, candidate: Bitmap, kind: SourceKind) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for ((role, image) in listOf("reference" to reference, "candidate" to candidate)) {
            val file = File(evidenceDirectory, "${kind.name}-$role.png")
            file.outputStream().use { output ->
                check(image.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG encoding failed: $file" }
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            val status = JSONObject()
                .put("case", "one-asymmetric")
                .put("source_kind", kind.name)
                .put("background", "black")
                .put("role", role)
                .put("width", image.width)
                .put("height", image.height)
                .put("package", instrumentation.targetContext.packageName)
                .put("path", file.absolutePath)
                .put("sha256", digest)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("glass_parity_png", status.toString())
            })
        }
    }

    private fun maximumDifference(first: Bitmap, second: Bitmap): Int {
        val firstPixels = pixels(first)
        val secondPixels = pixels(second)
        var difference = 0
        firstPixels.indices.forEach { index ->
            for (shift in 0..24 step 8) {
                difference = max(difference, abs(
                    ((firstPixels[index] ushr shift) and 255) - ((secondPixels[index] ushr shift) and 255)
                ))
            }
        }
        return difference
    }

    private fun pixels(image: Bitmap): IntArray = IntArray(image.width * image.height).also {
        image.getPixels(it, 0, image.width, 0, 0, image.width, image.height)
    }

    private data class FrameCase(
        val name: String,
        val radius: Float,
        val regions: List<AdvancedGlassRenderRegion>,
        val lens: AdvancedGlassLensSpec
    )

    private enum class SourceKind { Opaque, MixedAlpha, SparseOpaque }

    private class PatternView(context: Context) : View(context) {
        private val paint = Paint()
        var sourceKind = SourceKind.Opaque
            set(value) {
                field = value
                invalidate()
            }

        init {
            setWillNotDraw(false)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            for (row in 0 until 12) {
                for (column in 0 until 16) {
                    val index = row * 16 + column
                    val alpha = when (sourceKind) {
                        SourceKind.Opaque -> 255
                        SourceKind.MixedAlpha -> listOf(0, 64, 160, 255)[index % 4]
                        SourceKind.SparseOpaque -> if (index % 5 == 0) 255 else 0
                    }
                    paint.color = AndroidColor.argb(alpha, (index * 47) % 256, (index * 83) % 256, (index * 131) % 256)
                    canvas.drawRect(
                        column * width / 16f, row * height / 12f,
                        (column + 1) * width / 16f, (row + 1) * height / 12f, paint
                    )
                }
            }
        }
    }

    private companion object {
        const val HostTag = "advanced-glass-inverse-parity-host"
    }
}
