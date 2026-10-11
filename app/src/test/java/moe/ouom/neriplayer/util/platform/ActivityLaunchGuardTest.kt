package moe.ouom.neriplayer.util.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActivityLaunchGuardTest {

    private val appContext: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `started activity forwards the intent and reports success`() {
        val context = RecordingContext(appContext)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/page"))

        assertTrue(context.tryStartActivity(intent))
        assertEquals(listOf(intent), context.started)
    }

    @Test
    fun `missing or blocked activities report failure instead of throwing`() {
        listOf(ActivityNotFoundException("no browser"), SecurityException("blocked")).forEach { failure ->
            val context = RecordingContext(appContext, failure)

            assertFalse(context.tryStartActivity(Intent(Intent.ACTION_VIEW)))
            assertTrue(context.started.isEmpty())
        }
    }

    @Test
    fun `result launcher forwards the input and reports success`() {
        val launcher = RecordingLauncher()

        assertTrue(launcher.tryLaunch(null))
        assertEquals(listOf<Uri?>(null), launcher.inputs)
    }

    @Test
    fun `result launcher without a document picker reports failure`() {
        listOf(ActivityNotFoundException("no DocumentsUI"), SecurityException("blocked")).forEach { failure ->
            val launcher = RecordingLauncher(failure)

            assertFalse(launcher.tryLaunch(null))
            assertTrue(launcher.inputs.isEmpty())
        }
    }

    @Test
    fun `unrelated launch errors still propagate`() {
        val launcher = RecordingLauncher(IllegalStateException("launcher not registered"))

        assertThrows(IllegalStateException::class.java) { launcher.tryLaunch(null) }
    }

    private class RecordingContext(
        base: Context,
        private val failure: RuntimeException? = null
    ) : ContextWrapper(base) {
        val started = mutableListOf<Intent>()

        override fun startActivity(intent: Intent) {
            failure?.let { throw it }
            started += intent
        }
    }

    private class RecordingLauncher(
        private val failure: RuntimeException? = null
    ) : ActivityResultLauncher<Uri?>() {
        val inputs = mutableListOf<Uri?>()

        override val contract: ActivityResultContract<Uri?, *> = ActivityResultContracts.OpenDocumentTree()

        override fun launch(input: Uri?, options: ActivityOptionsCompat?) {
            failure?.let { throw it }
            inputs += input
        }

        override fun unregister() = Unit
    }
}
