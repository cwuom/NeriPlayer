package moe.ouom.neriplayer.util.platform

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.res.Resources
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

class PhoneOrientationLockTest {

    @Test
    fun `phones are locked to portrait`() {
        val activity = activityWithSmallestWidth(smallestScreenWidthDp = 411)

        activity.lockPortraitIfPhone()

        verify(activity).requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    @Test
    fun `tablets keep the system orientation`() {
        val activity = activityWithSmallestWidth(smallestScreenWidthDp = PHONE_SMALLEST_SCREEN_WIDTH_DP)

        activity.lockPortraitIfPhone()

        verify(activity).requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private fun activityWithSmallestWidth(smallestScreenWidthDp: Int): Activity {
        val configuration = Configuration().apply { this.smallestScreenWidthDp = smallestScreenWidthDp }
        val resources = mock(Resources::class.java).also { `when`(it.configuration).thenReturn(configuration) }
        return mock(Activity::class.java).also { `when`(it.resources).thenReturn(resources) }
    }
}
