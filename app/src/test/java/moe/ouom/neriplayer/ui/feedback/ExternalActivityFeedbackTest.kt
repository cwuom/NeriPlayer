package moe.ouom.neriplayer.ui.feedback

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R as CoreCommonR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class ExternalActivityFeedbackTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun resetFeedback() {
        // AppFeedback 按时间全局去重同一文案，而 Robolectric 每个用例都会重置时钟
        AppFeedback::class.java.getDeclaredField("lastToastDedupState").apply {
            isAccessible = true
            set(AppFeedback, null)
        }
        ShadowToast.reset()
    }

    @Test
    fun `available picker launches without feedback`() {
        val launcher = RecordingLauncher()

        assertTrue(launcher.launchOrShowUnavailable(context, arrayOf("*/*")))

        assertEquals(listOf("*/*"), launcher.inputs.single().toList())
        ShadowLooper.idleMainLooper()
        assertNull(ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `missing document picker reports unavailable instead of throwing`() {
        val launcher = RecordingLauncher(ActivityNotFoundException("no DocumentsUI"))

        assertFalse(launcher.launchOrShowUnavailable(context, arrayOf("*/*")))

        assertTrue(launcher.inputs.isEmpty())
        ShadowLooper.idleMainLooper()
        assertEquals(context.getString(CoreCommonR.string.error_no_app_for_action), ShadowToast.getTextOfLatestToast())
    }

    private class RecordingLauncher(
        private val failure: RuntimeException? = null
    ) : ActivityResultLauncher<Array<String>>() {
        val inputs = mutableListOf<Array<String>>()

        override val contract: ActivityResultContract<Array<String>, *> = ActivityResultContracts.OpenDocument()

        override fun launch(input: Array<String>, options: ActivityOptionsCompat?) {
            failure?.let { throw it }
            inputs += input
        }

        override fun unregister() = Unit
    }
}
