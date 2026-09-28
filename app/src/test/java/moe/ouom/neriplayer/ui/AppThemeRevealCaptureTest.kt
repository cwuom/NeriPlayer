package moe.ouom.neriplayer.ui

import android.view.View
import android.view.ViewTreeObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import moe.ouom.neriplayer.ui.theme.reveal.awaitStableDraw
import moe.ouom.neriplayer.ui.theme.reveal.captureThemeRevealSnapshot
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppThemeRevealCaptureTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun absentWindowUsesTheFallbackViewWithoutRequiringAReadyFrame() = runBlocking {
        val view = mock(View::class.java)
        assertNull(captureThemeRevealSnapshot(null, view))
    }

    @Test
    fun stableDrawDoesNotWaitForADetachedView() = runBlocking {
        val view = mock(View::class.java)
        awaitStableDraw(view)
        verify(view).isAttachedToWindow
        Unit
    }

    @Test
    fun stableDrawWaitsForTheNextFrameAndRemovesItsListener() = runBlocking {
        val view = mock(View::class.java)
        val observer = mock(ViewTreeObserver::class.java)
        `when`(view.isAttachedToWindow).thenReturn(true)
        `when`(view.width).thenReturn(100)
        `when`(view.height).thenReturn(100)
        `when`(view.viewTreeObserver).thenReturn(observer)
        `when`(observer.isAlive).thenReturn(true)
        doAnswer { invocation ->
            (invocation.arguments[0] as Runnable).run()
            true
        }.`when`(view).post(any(Runnable::class.java))
        doAnswer { invocation ->
            (invocation.arguments[0] as ViewTreeObserver.OnDrawListener).onDraw()
            null
        }.`when`(observer).addOnDrawListener(any(ViewTreeObserver.OnDrawListener::class.java))

        awaitStableDraw(view)

        verify(observer).removeOnDrawListener(any(ViewTreeObserver.OnDrawListener::class.java))
    }
}
