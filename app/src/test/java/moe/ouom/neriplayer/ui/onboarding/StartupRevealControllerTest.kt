package moe.ouom.neriplayer.ui.onboarding

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class StartupRevealControllerTest {

    private val uncaught = mutableListOf<Throwable>()
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e -> uncaught += e }
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `failed settings write rolls back without escaping the coroutine`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val controller = StartupRevealController(scope, null, View(context), ResumedLifecycleOwner())
        var beforeApplyCalls = 0
        var failureCalls = 0

        controller.request(
            origin = Offset(10f, 10f),
            startRadius = 4f,
            coverColor = Color.White,
            beforeApply = { beforeApplyCalls += 1 },
            apply = { throw IllegalStateException("datastore write failed") },
            onFailure = { failureCalls += 1 }
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(emptyList<Throwable>(), uncaught)
        assertEquals(1, beforeApplyCalls)
        assertEquals(1, failureCalls)
        assertFalse(controller.revealActive)
        assertFalse(controller.busy)
    }

    private class ResumedLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).apply {
            currentState = Lifecycle.State.RESUMED
        }
        override val lifecycle: Lifecycle get() = registry
    }
}
