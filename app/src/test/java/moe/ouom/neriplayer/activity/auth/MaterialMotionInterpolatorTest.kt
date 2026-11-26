package moe.ouom.neriplayer.activity.auth

import android.app.Application
import android.content.Context
import android.util.TypedValue
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.R as MaterialR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class MaterialMotionInterpolatorTest {
    @Test
    fun `Material state animations use inline emphasized path data`() {
        val resources = ApplicationProvider.getApplicationContext<Context>().resources
        val theme = resources.newTheme().apply {
            applyStyle(MaterialR.style.Theme_Material3_DayNight_NoActionBar, true)
        }
        val interpolator = TypedValue()
        assertTrue(theme.resolveAttribute(MaterialR.attr.motionEasingEmphasizedInterpolator, interpolator, true))
        resources.getAnimation(interpolator.resourceId).use { xml ->
            var event = xml.next()
            while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT) {
                event = xml.next()
            }
            assertEquals("pathInterpolator", xml.name)
            val androidNamespace = "http://schemas.android.com/apk/res/android"
            assertEquals(0, xml.getAttributeResourceValue(androidNamespace, "pathData", 0))
            assertTrue(xml.getAttributeValue(androidNamespace, "pathData").orEmpty().startsWith("M"))
        }
    }
}
