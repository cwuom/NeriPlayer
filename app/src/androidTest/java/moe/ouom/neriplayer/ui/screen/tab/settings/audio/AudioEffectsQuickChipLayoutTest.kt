package moe.ouom.neriplayer.ui.screen.tab.settings.audio

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.playback.PlaybackSoundState
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsSettings
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsSound
import moe.ouom.neriplayer.data.model.playback.effects.AudioOutputRoute
import moe.ouom.neriplayer.data.model.playback.effects.ParametricEqBand
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AudioEffectsQuickChipLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun showCard(style: UiStyle, fontScale: Float, content: @Composable () -> Unit) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                NeriTheme(
                    followSystemDark = false,
                    forceDark = false,
                    dynamicColor = false,
                    seedColorHex = "885522",
                    uiStyle = style,
                    animateColors = false
                ) {
                    Column(Modifier.width(300.dp).verticalScroll(rememberScrollState())) {
                        content()
                    }
                }
            }
        }
        settle()
    }

    private fun settle() {
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitForIdle()
    }

    private fun assertWrappedRows(group: String, matchers: List<SemanticsMatcher>) {
        val bounds = matchers.map { composeRule.onNode(it).getUnclippedBoundsInRoot() }
        bounds.forEach { bound ->
            assertTrue(
                "$group chip must have nonempty layout bounds",
                bound.right > bound.left && bound.bottom > bound.top
            )
        }
        val rows = bounds.groupBy { it.top.value }.toSortedMap().values
        assertTrue("$group must wrap at this width", rows.size > 1)
        rows.zipWithNext().forEach { (previous, next) ->
            val gap = next.minOf { it.top.value } - previous.maxOf { it.bottom.value }
            assertTrue("$group row gap was ${gap}dp", gap >= 7.5f)
        }
    }

    private fun choice(label: String): SemanticsMatcher =
        hasText(label) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)

    private fun checkSpeedAndPitchRows(style: UiStyle, fontScale: Float) {
        showCard(style, fontScale) {
            val editor = remember { AudioEffectsEditor(AudioOutputRoute.SPEAKER) }
            AudioEffectsSpeedCard(
                soundState = PlaybackSoundState(speed = 1f, pitch = 1f),
                settings = AudioEffectsSettings(speedPitchLinked = false),
                usbExclusive = false,
                editor = editor
            )
        }
        if (style == UiStyle.Clean && fontScale == 1f) {
            File(context.cacheDir, "audio-quick-chips-spacing.png").outputStream().use {
                composeRule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }

        assertWrappedRows(
            "speed",
            listOf(
                "0.50x", "0.75x", "0.85x", "0.95x", "1.00x",
                "1.05x", "1.10x", "1.25x", "1.50x", "2.00x"
            ).map(::choice)
        )
        assertWrappedRows("pitch", listOf("-3.0", "-1.0", "0.0", "+1.0", "+3.0").map(::choice))
    }

    private fun checkParametricRows(style: UiStyle, fontScale: Float) {
        showCard(style, fontScale) {
            val editor = remember { AudioEffectsEditor(AudioOutputRoute.SPEAKER) }
            AudioEffectsCorrectionCard(
                sound = AudioEffectsSound(parametricEnabled = true, parametricBands = listOf(ParametricEqBand())),
                editor = editor
            )
        }
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.audio_effects_filter_peak))
            .performScrollTo().performClick()
        settle()

        val labels = listOf(
            CoreCommonR.string.audio_effects_filter_peak,
            CoreCommonR.string.audio_effects_filter_low_shelf,
            CoreCommonR.string.audio_effects_filter_high_shelf,
            CoreCommonR.string.audio_effects_filter_low_pass,
            CoreCommonR.string.audio_effects_filter_high_pass,
            CoreCommonR.string.audio_effects_filter_notch,
            CoreCommonR.string.audio_effects_filter_band_pass
        )
        assertWrappedRows(
            "parametric types",
            labels.map { choice(context.getString(it)) and hasAnyAncestor(isDialog()) }
        )
    }

    @Test
    fun cleanSpeedAndPitchRowsHaveSpace() {
        checkSpeedAndPitchRows(UiStyle.Clean, fontScale = 1f)
    }

    @Test
    fun cleanSpeedAndPitchRowsHaveSpaceWithLargerText() {
        checkSpeedAndPitchRows(UiStyle.Clean, fontScale = 1.3f)
    }

    @Test
    fun materialSpeedAndPitchRowsHaveSpace() {
        checkSpeedAndPitchRows(UiStyle.Material, fontScale = 1f)
    }

    @Test
    fun materialSpeedAndPitchRowsHaveSpaceWithLargerText() {
        checkSpeedAndPitchRows(UiStyle.Material, fontScale = 1.3f)
    }

    @Test
    fun cleanParametricTypeRowsHaveSpace() {
        checkParametricRows(UiStyle.Clean, fontScale = 1f)
    }

    @Test
    fun cleanParametricTypeRowsHaveSpaceWithLargerText() {
        checkParametricRows(UiStyle.Clean, fontScale = 1.3f)
    }

    @Test
    fun materialParametricTypeRowsHaveSpace() {
        checkParametricRows(UiStyle.Material, fontScale = 1f)
    }

    @Test
    fun materialParametricTypeRowsHaveSpaceWithLargerText() {
        checkParametricRows(UiStyle.Material, fontScale = 1.3f)
    }
}
