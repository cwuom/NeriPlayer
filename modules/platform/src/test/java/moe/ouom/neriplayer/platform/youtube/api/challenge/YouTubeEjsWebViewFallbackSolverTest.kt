package moe.ouom.neriplayer.platform.youtube.api.challenge

import android.app.Application
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class YouTubeEjsWebViewFallbackSolverTest {

    @Before
    fun useTestMainDispatcher() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun restoreMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a WebView created on main while the caller is cancelled is destroyed`() = runTest {
        val created = mutableListOf<WebView>()
        lateinit var caller: Job
        val solver = YouTubeEjsWebViewFallbackSolver(RuntimeEnvironment.getApplication()) { context ->
            WebView(context).also { webView ->
                created += webView
                caller.cancel()
            }
        }

        caller = launch { solver.warm(PLAYER_JS_URL, "var player = {};") }
        caller.join()

        assertTrue(caller.isCancelled)
        assertTrue(shadowOf(created.single()).wasDestroyCalled())
    }

    private companion object {
        const val PLAYER_JS_URL = "https://www.youtube.com/s/player/test/player_ias.vflset/en_US/base.js"
    }
}
