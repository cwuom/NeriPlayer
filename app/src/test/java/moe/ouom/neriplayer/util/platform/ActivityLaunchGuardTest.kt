package moe.ouom.neriplayer.util.platform

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.util.AndroidRuntimeException
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [28, 36])
class ActivityLaunchGuardTest {

    private val appContext: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `started activity forwards the intent and reports success`() {
        val context = RecordingContext(appContext)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/page"))

        assertTrue(context.tryStartActivity(intent))
        assertEquals(1, context.started.size)
        assertTrue(intent.filterEquals(context.started.single()))
    }

    @Test
    fun `application context starts the share chooser in a new task without changing the input`() {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Shared song")
        }
        val chooser = Intent.createChooser(sendIntent, "Share song")

        assertTrue(appContext.tryStartActivity(chooser))

        val started = shadowOf(appContext as Application).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, started.action)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(0, chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        assertEquals("Share song", started.getCharSequenceExtra(Intent.EXTRA_TITLE))
        val target = IntentCompat.getParcelableExtra(started, Intent.EXTRA_INTENT, Intent::class.java)
        assertEquals("text/plain", target?.type)
        assertEquals("Shared song", target?.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(0, sendIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    fun `wrapped configuration context preserves image payload and read permission`() {
        val context = ContextWrapper(ContextWrapper(appContext.createConfigurationContext(Configuration())))
        val uri = Uri.parse("content://moe.ouom.neriplayer.fileprovider/lyric_share_cards/card.png")
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("Lyric card", uri)
        }
        val chooser = Intent.createChooser(sendIntent, null)
        val originalFlags = chooser.flags

        assertTrue(context.tryStartActivity(chooser))

        val started = shadowOf(appContext as Application).nextStartedActivity
        assertEquals(originalFlags or Intent.FLAG_ACTIVITY_NEW_TASK, started.flags)
        assertEquals(uri, started.clipData?.getItemAt(0)?.uri)
        val target = IntentCompat.getParcelableExtra(started, Intent.EXTRA_INTENT, Intent::class.java)
        assertEquals("image/png", target?.type)
        assertEquals(uri, target?.let { IntentCompat.getParcelableExtra(it, Intent.EXTRA_STREAM, Uri::class.java) })
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, target?.flags)
        assertEquals(uri, target?.clipData?.getItemAt(0)?.uri)
        assertEquals(originalFlags, chooser.flags)
    }

    @Test
    fun `activity and nested activity wrappers preserve the original task behavior`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val contexts = listOf(activity, ContextWrapper(ContextWrapper(activity)))
            contexts.forEach { context ->
                val chooser = Intent.createChooser(Intent(Intent.ACTION_SEND), null)

                assertTrue(context.tryStartActivity(chooser))

                val started = shadowOf(activity).nextStartedActivity
                assertSame(chooser, started)
                assertEquals(0, started.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } finally {
            controller.pause().stop().destroy()
        }
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
        val context = RecordingContext(appContext, AndroidRuntimeException("unexpected launch failure"))

        assertThrows(IllegalStateException::class.java) { launcher.tryLaunch(null) }
        assertThrows(AndroidRuntimeException::class.java) {
            context.tryStartActivity(Intent(Intent.ACTION_VIEW))
        }
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
