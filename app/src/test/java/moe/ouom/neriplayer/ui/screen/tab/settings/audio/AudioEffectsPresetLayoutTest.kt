package moe.ouom.neriplayer.ui.screen.tab.settings.audio

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsBuiltInPresets
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsPresetCategory
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsProfile
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsSettings
import moe.ouom.neriplayer.data.model.playback.effects.AudioOutputRoute
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "zh-rCN-w360dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AudioEffectsPresetLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun checkPresetSpacing(fontScale: Float) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                NeriTheme(
                    followSystemDark = false,
                    forceDark = false,
                    dynamicColor = false,
                    seedColorHex = "885522",
                    uiStyle = UiStyle.Clean,
                    animateColors = false
                ) {
                    Column(Modifier.width(300.dp)) {
                        AudioEffectsPresetCard(
                            settings = AudioEffectsSettings(),
                            profile = AudioEffectsProfile(),
                            editor = AudioEffectsEditor(AudioOutputRoute.SPEAKER)
                        )
                    }
                }
            }
        }

        AudioEffectsPresetCategory.entries.forEach { category ->
            composeRule.onNodeWithText(context.getString(category.tabLabelRes())).performClick()
            val bounds = AudioEffectsBuiltInPresets.filter { it.category == category }.map { preset ->
                val label = context.getString(checkNotNull(audioEffectsPresetNameRes(preset.id)))
                composeRule.onNodeWithText(label).getBoundsInRoot()
            }
            val rows = bounds.groupBy { it.top.value }.toSortedMap().values
            assertTrue("$category must wrap at this width", rows.size > 1)
            rows.zipWithNext().forEach { (previous, next) ->
                val gap = next.minOf { it.top.value } - previous.maxOf { it.bottom.value }
                assertTrue("$category row gap was ${gap}dp", gap >= 7.5f)
            }
        }
    }

    @Test
    fun `wrapped presets have space between rows in every category`() {
        checkPresetSpacing(fontScale = 1f)
    }

    @Test
    fun `wrapped presets keep their row spacing with larger text`() {
        checkPresetSpacing(fontScale = 1.3f)
    }
}
