package moe.ouom.neriplayer.ui.component.overlay

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt
import moe.ouom.neriplayer.common.R
import moe.ouom.neriplayer.ui.screen.history.stats.StatsCalendarDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.network.DohDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.network.ProxyDialog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h760dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaterialDialogActionRenderingTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val palette = lightColorScheme(primaryContainer = Color(0xFF589ACC),
        onPrimaryContainer = Color.White, onSurface = Color.Black, surfaceContainerHigh = Color.White)

    @Test fun statisticsDateApplyHasAnOutlineAndFillWhileCancelStaysFlat() {
        val day = LocalDate.of(2026, 1, 12)
        compose.setContent {
            MaterialTheme(colorScheme = palette) {
                StatsCalendarDialog(true, day, day, day, DayOfWeek.MONDAY, {}, { _, _ -> })
            }
        }
        assertEnabledAction(R.string.action_apply)
    }

    @Test
    @Config(qualifiers = "w780dp-h360dp-land-mdpi")
    fun compactProxyAndDnsDistinguishValidAndDisabledActions() {
        val proxy = mutableStateOf(true)
        compose.setContent {
            MaterialTheme(colorScheme = palette) {
                if (proxy.value) ProxyDialog(null, {}, {}) else DohDialog("", {}, {})
            }
        }
        assertEnabledAction(R.string.action_confirm)
        compose.onNodeWithText("HTTP").performClick()
        compose.onNodeWithText(context.getString(R.string.action_confirm)).assertIsNotEnabled()
        assertFalse(palette.primaryContainer.toArgb() in actionPixels(R.string.action_confirm))
        compose.runOnIdle { proxy.value = false }
        assertEnabledAction(R.string.action_confirm)
    }

    private fun assertEnabledAction(label: Int) {
        compose.onNodeWithText(context.getString(label)).assertIsEnabled()
        val pixels = actionPixels(label)
        val fill = palette.primaryContainer
        val outline = palette.onSurface.copy(alpha = 0.24f).compositeOver(fill).toArgb()
        assertTrue("Expected visible tonal fill", pixels.count { it == fill.toArgb() } > 100)
        assertTrue("Expected a visible outline", pixels.count { closeTo(it, outline) } > 8)
        assertFalse("Cancel should stay flat", fill.toArgb() in actionPixels(R.string.action_cancel))
    }

    private fun closeTo(pixel: Int, expected: Int): Boolean =
        abs(AndroidColor.red(pixel) - AndroidColor.red(expected)) <= 2 &&
            abs(AndroidColor.green(pixel) - AndroidColor.green(expected)) <= 2 &&
            abs(AndroidColor.blue(pixel) - AndroidColor.blue(expected)) <= 2

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
