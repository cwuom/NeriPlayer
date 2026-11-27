package moe.ouom.neriplayer.ui.component.overlay

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.roundToInt
import moe.ouom.neriplayer.R as AppR
import moe.ouom.neriplayer.common.R
import moe.ouom.neriplayer.ui.screen.debug.TextLogFileListScreen
import moe.ouom.neriplayer.ui.screen.history.stats.StatsCalendarDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.network.DohDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.network.ProxyDialog
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h760dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaterialDialogActionRenderingTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tempFolder = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val light = lightColorScheme(primary = Color(0xFF4E5993), onPrimary = Color.White)
    private val dark = darkColorScheme(primary = Color(0xFFBCC5FF), onPrimary = Color(0xFF232E63))

    @Test fun statisticsDateApplyMatchesThePlaylistButtonWhileCancelStaysFlat() {
        val day = LocalDate.of(2026, 1, 12)
        val showCalendar = mutableStateOf(true)
        compose.setContent {
            MaterialTheme(colorScheme = light) {
                if (showCalendar.value) {
                    StatsCalendarDialog(true, day, day, day, DayOfWeek.MONDAY, {}, { _, _ -> })
                } else {
                    MiuixSettingsDialog(
                        onDismissRequest = {},
                        confirmButton = {
                            MiuixSettingsButton(onClick = {}) { Text(stringResource(R.string.action_apply)) }
                        },
                        dismissButton = {
                            MiuixSettingsTextButton(onClick = {}) { Text(stringResource(R.string.action_cancel)) }
                        }
                    )
                }
            }
        }
        assertEnabledAction(R.string.action_apply, light)
        val calendar = actionPixels(R.string.action_apply)
        compose.runOnIdle { showCalendar.value = false }
        val reference = actionPixels(R.string.action_apply)
        assertArrayEquals("Apply should render like the filled playlist action", reference, calendar)
    }

    @Test
    @Config(qualifiers = "w780dp-h360dp-land-mdpi")
    fun compactProxyAndDnsDistinguishValidAndDisabledActions() {
        val proxy = mutableStateOf(true)
        compose.setContent {
            MaterialTheme(colorScheme = dark) {
                if (proxy.value) ProxyDialog(null, {}, {}) else DohDialog("", {}, {})
            }
        }
        assertEnabledAction(R.string.action_confirm, dark)
        compose.onNodeWithText("HTTP").performClick()
        compose.onNodeWithText(context.getString(R.string.action_confirm)).assertIsNotEnabled()
        val disabledPixels = actionPixels(R.string.action_confirm)
        assertFalse(dark.primary.toArgb() in disabledPixels)
        assertFilledWith(disabledPixels, dark.surfaceVariant)
        compose.runOnIdle { proxy.value = false }
        assertEnabledAction(R.string.action_confirm, dark)
    }

    @Test
    fun clearLogsActionUsesContrastingErrorColorsWithRedAccent() {
        val directory = tempFolder.newFolder()
        File(directory, "sample.txt").writeText("log")
        val darkMode = mutableStateOf(false)
        lateinit var palette: ColorScheme
        compose.setContent {
            NeriTheme(
                followSystemDark = false,
                forceDark = darkMode.value,
                dynamicColor = false,
                seedColorHex = "B3261E",
                animateColors = false
            ) {
                val colors = MaterialTheme.colorScheme
                SideEffect { palette = colors }
                TextLogFileListScreen(
                    titleRes = AppR.string.log_app,
                    clearConfirmRes = AppR.string.log_delete_confirm,
                    clearedCountRes = AppR.plurals.log_cleared_count,
                    emptyTitleRes = AppR.string.log_no_file,
                    emptyHintRes = AppR.string.log_enable_hint,
                    resolveDirectory = { directory },
                    onBack = {},
                    onLogFileClick = {},
                    fileIcon = Icons.Rounded.Description,
                    fileHue = colors.primary
                )
            }
        }
        val clear = compose.onNodeWithContentDescription(context.getString(AppR.string.log_clear))
        compose.waitUntil(5_000) { clear.isDisplayed() }
        clear.performClick()

        for (useDarkColors in listOf(false, true)) {
            compose.runOnIdle { darkMode.value = useDarkColors }
            val pixels = actionPixels(R.string.common_clear_all)
            val contrast = (palette.onError.luminance() + 0.05f) / (palette.error.luminance() + 0.05f)
            val legacyContrast = (palette.error.luminance() + 0.05f) / (palette.primary.luminance() + 0.05f)
            assertTrue("Error fill is missing for dark=$useDarkColors, previous contrast ratio=$legacyContrast",
                pixels.count { it == palette.error.toArgb() } > pixels.size / 2)
            assertTrue("The label should use onError", palette.onError.toArgb() in pixels)
            assertTrue("Expected at least 4.5:1 contrast", contrast >= 4.5f || 1f / contrast >= 4.5f)
        }
    }

    private fun assertEnabledAction(label: Int, palette: ColorScheme) {
        compose.onNodeWithText(context.getString(label)).assertIsEnabled()
        assertFilledWith(actionPixels(label), palette.primary)
        val cancel = actionPixels(R.string.action_cancel)
        assertTrue("Cancel should only use the primary color for its text", cancel.count { it == palette.primary.toArgb() } < cancel.size / 4)
    }

    private fun assertFilledWith(pixels: IntArray, fill: Color) {
        assertTrue("Expected a solid button fill", pixels.count { it == fill.toArgb() } > pixels.size / 2)
    }

    private fun actionPixels(label: Int): IntArray {
        val node = compose.onNodeWithText(context.getString(label)).fetchSemanticsNode()
        return compose.runOnIdle {
            val view = (node.root as ViewRootForTest).view
            val bounds = node.boundsInRoot
            val width = bounds.width.roundToInt()
            val height = bounds.height.roundToInt()
            val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(image)
                canvas.translate(-bounds.left, -bounds.top)
                view.draw(canvas)
                IntArray(width * height).also { image.getPixels(it, 0, width, 0, 0, width, height) }
            } finally {
                image.recycle()
            }
        }
    }
}
