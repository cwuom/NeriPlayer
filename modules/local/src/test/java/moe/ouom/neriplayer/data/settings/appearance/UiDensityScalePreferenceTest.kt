package moe.ouom.neriplayer.data.settings.appearance

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiDensityScalePreferenceTest {
    @Test
    fun phoneAndTabletRangesMeetAtTheExisting600DpDeviceBoundary() {
        val phone = UiDensityScalePreference.forDevice(599, Configuration.UI_MODE_TYPE_NORMAL)
        val tablet = UiDensityScalePreference.forDevice(600, Configuration.UI_MODE_TYPE_NORMAL)

        assertEquals(0.6f..1.2f, phone.valueRange)
        assertEquals(11, phone.steps)
        assertEquals(0.6f..1f, tablet.valueRange)
        assertEquals(7, tablet.steps)
        assertEquals(1.2f, phone.normalize(2f), 0f)
        assertEquals(1f, tablet.normalize(2f), 0f)
    }

    @Test
    fun eachTabletStopsAtTheLastFivePercentStepThatPreserves600Dp() {
        for ((width, maximum) in listOf(600 to 1f, 629 to 1f, 630 to 1.05f, 719 to 1.15f,
            720 to 1.2f, 800 to 1.3f, 900 to 1.5f, 1000 to 1.65f, 1200 to 2f, 1500 to 2.5f)) {
            val range = UiDensityScalePreference.forDevice(width, Configuration.UI_MODE_TYPE_NORMAL)
            assertEquals("sw=$width", maximum, range.maximumScale, 0f)
            assertEquals(maximum, range.normalize(Float.MAX_VALUE), 0f)
            assertTrue("最大值仍应保留平板宽度: sw=$width max=$maximum", width / maximum >= 599.999f)
            assertTrue("再增一档就不再保留平板宽度: sw=$width max=$maximum", width / (maximum + 0.05f) < 600f)
        }
        val large = UiDensityScalePreference.forDevice(Int.MAX_VALUE, Configuration.UI_MODE_TYPE_NORMAL)
        assertTrue("大配置不能因步数溢出而丢失可用缩放", large.maximumScale > 1000f && large.steps > 0)
    }

    @Test
    fun carModeCapsRestoredTwoTimesAtOnePointFiveRegardlessOfNightModeFlags() {
        val car = UiDensityScalePreference.forDevice(
            320, Configuration.UI_MODE_TYPE_CAR or Configuration.UI_MODE_NIGHT_YES
        )
        assertEquals(1.5f, car.maximumScale, 0f)
        assertEquals(1.5f, car.normalize(3f), 0f)
        assertEquals(17, car.steps)
        assertEquals(1.5f, UiDensityScalePreference.forDevice(
            1500, Configuration.UI_MODE_TYPE_CAR
        ).maximumScale, 0f)
        val phoneAtNight = UiDensityScalePreference.forDevice(
            320, Configuration.UI_MODE_TYPE_NORMAL or Configuration.UI_MODE_NIGHT_YES
        )
        assertEquals(1.2f, phoneAtNight.maximumScale, 0f)
        assertEquals(1.2f, UiDensityScalePreference.forDevice(320, Configuration.UI_MODE_TYPE_DESK).maximumScale, 0f)
    }

    @Test
    fun normalizationKeepsValidPreferencesAndReplacesInvalidNumbers() {
        val phone = UiDensityScalePreference.forDevice(390, Configuration.UI_MODE_TYPE_NORMAL)
        val tablet = UiDensityScalePreference.forDevice(800, Configuration.UI_MODE_TYPE_NORMAL)
        for (scale in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(1f, UiDensityScalePreference.normalize(scale), 0f)
            assertEquals(1f, phone.normalize(scale), 0f)
            assertEquals(1f, tablet.normalize(scale), 0f)
        }
        assertEquals(0.6f, UiDensityScalePreference.normalize(-1f), 0f)
        assertEquals(3f, UiDensityScalePreference.normalize(3f), 0f)
        assertEquals(1.3f, tablet.normalize(1.65f), 0f)
        assertEquals(1.25f, tablet.normalize(1.25f), 0f)
        assertEquals(0.6f, phone.normalize(0.4f), 0f)
        assertEquals(1.15f, phone.normalize(1.15f), 0f)
    }

    @Test
    fun defaultScaleRemainsOneOnPhoneTabletAndCar() {
        for ((width, mode) in listOf(390 to Configuration.UI_MODE_TYPE_NORMAL,
            600 to Configuration.UI_MODE_TYPE_NORMAL, 320 to Configuration.UI_MODE_TYPE_CAR)) {
            assertEquals(1f, UiDensityScalePreference.forDevice(width, mode).normalize(1f), 0f)
        }
    }
}
