package moe.ouom.neriplayer.ui.screen.tab.settings.dialog

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R as CoreCommonR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast

@RunWith(AndroidJUnit4::class)
class SettingsGitHubTokenPageTest {

    private val appContext: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `token creation page opens in a browser`() {
        val context = RecordingContext(appContext)

        openGitHubTokenCreationPage(context)
        shadowOf(Looper.getMainLooper()).idle()

        val intent = context.started.single()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(GITHUB_TOKEN_CREATION_URL, intent.dataString)
        assertNull(ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `missing browser shows a localized toast instead of crashing`() {
        openGitHubTokenCreationPage(RecordingContext(appContext, ActivityNotFoundException("no browser")))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            appContext.getString(CoreCommonR.string.sync_create_token_open_failed),
            ShadowToast.getTextOfLatestToast()
        )
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
}
