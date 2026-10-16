package moe.ouom.neriplayer.data.settings.appearance

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Test

class UiDensityScalePreferenceTest {
    @Test
    fun phoneAndTabletRangesMeetAtTheExisting600DpDeviceBoundary() {
        val phone = UiDensityScalePreference.forDevice(599, Configuration.UI_MODE_TYPE_NORMAL)
        val tablet = UiDensityScalePreference.forDevice(600, Configuration.UI_MODE_TYPE_NORMAL)

        assertEquals(0.6f..1.2f, phone.valueRange)
        assertEquals(11, phone.steps)
        assertEquals(0.6f..2f, tablet.valueRange)
        assertEquals(27, tablet.steps)
        assertEquals(1.2f, phone.normalize(2f), 0f)
        assertEquals(2f, tablet.normalize(2f), 0f)
    }

    @Test
    fun carModeAllowsTwoTimesAtPhoneWidthRegardlessOfNightModeFlags() {
        val car = UiDensityScalePreference.forDevice(
            320, Configuration.UI_MODE_TYPE_CAR or Configuration.UI_MODE_NIGHT_YES
        )
        assertEquals(2f, car.maximumScale, 0f)
        assertEquals(2f, car.normalize(3f), 0f)
        assertEquals(27, car.steps)
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
        assertEquals(2f, UiDensityScalePreference.normalize(3f), 0f)
        assertEquals(1.65f, tablet.normalize(1.65f), 0f)
        assertEquals(0.6f, phone.normalize(0.4f), 0f)
        assertEquals(1.15f, phone.normalize(1.15f), 0f)
    }
}
