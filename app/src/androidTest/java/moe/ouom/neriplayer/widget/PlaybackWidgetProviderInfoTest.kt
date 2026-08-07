package moe.ouom.neriplayer.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.os.Process
import android.util.TypedValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class PlaybackWidgetProviderInfoTest {
    @Test
    @SdkSuppress(minSdkVersion = 31)
    fun fullWidgetDefaultsToTwoRowsAndAllowsVerticalResizing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val provider = ComponentName(context, NeriPlayerPlaybackWidgetProvider::class.java)
        val providerInfo = checkNotNull(
            appWidgetManager.getInstalledProvidersForPackage(
                context.packageName,
                Process.myUserHandle(),
            ).firstOrNull { it.provider == provider },
        ) { "Playback widget provider is not installed" }

        assertTrue(
            providerInfo.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0,
        )
        assertEquals(
            AppWidgetProviderInfo.RESIZE_VERTICAL,
            providerInfo.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL,
        )
        assertEquals(
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                PLAYBACK_WIDGET_DEFAULT_HEIGHT_DP.toFloat(),
                context.resources.displayMetrics,
            ).roundToInt(),
            providerInfo.minHeight,
        )
        assertEquals(2, providerInfo.targetCellHeight)
    }
}
