package moe.ouom.neriplayer.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.runtime.R as LifecycleR
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
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.withSettings
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
    fun attachedDecorRecoversItsActivityFromTheWindowTreeWithoutAnActivityContext() = runBlocking {
        val (activity, decor) = captureHost()
        `when`(decor.getTag(LifecycleR.id.view_tree_lifecycle_owner)).thenReturn(activity)

        assertNull(captureThemeRevealSnapshot(null, decor))

        verify(activity, atLeastOnce()).window
        verify(decor, never()).context
        Unit
    }

    @Test
    fun attachedViewCanRecoverTheSameWindowThroughWrappedContexts() = runBlocking {
        val (activity, decor) = captureHost()
        val wrapper = mock(ContextWrapper::class.java)
        `when`(wrapper.baseContext).thenReturn(activity)
        `when`(decor.context).thenReturn(wrapper)

        assertNull(captureThemeRevealSnapshot(null, decor))

        verify(wrapper).baseContext
        verify(activity, atLeastOnce()).window
        Unit
    }

    @Test
    fun anOwnerFromAnotherWindowCannotReplaceTheRequestedView() = runBlocking {
        val (activity, foreignDecor) = captureHost()
        val requestedView = mock(View::class.java)
        `when`(requestedView.isAttachedToWindow).thenReturn(true)
        `when`(requestedView.rootView).thenReturn(requestedView)
        `when`(requestedView.getTag(LifecycleR.id.view_tree_lifecycle_owner)).thenReturn(activity)

        assertNull(captureThemeRevealSnapshot(null, requestedView))

        verify(foreignDecor, never()).width
        verify(foreignDecor, never()).height
        Unit
    }

    @Test
    fun attachedViewsWithoutAnActivityKeepTheOriginalFallback() = runBlocking {
        val view = mock(View::class.java)
        `when`(view.isAttachedToWindow).thenReturn(true)
        `when`(view.rootView).thenReturn(view)
        `when`(view.context).thenReturn(mock(Context::class.java))
        `when`(view.getTag(LifecycleR.id.view_tree_lifecycle_owner))
            .thenReturn(mock(LifecycleOwner::class.java))

        assertNull(captureThemeRevealSnapshot(null, view))
    }

    @Test
    fun detachedViewsNeverResolveAnActivityFromTheirContext() = runBlocking {
        val view = mock(View::class.java)

        assertNull(captureThemeRevealSnapshot(null, view))

        verify(view, never()).context
        verify(view, never()).rootView
        Unit
    }

    @Test
    fun anExplicitActivityTakesPriorityOverFallbackHostDiscovery() = runBlocking {
        val (activity, _) = captureHost()
        val fallback = mock(View::class.java)

        assertNull(captureThemeRevealSnapshot(activity, fallback))

        verify(activity, atLeastOnce()).window
        verify(fallback, never()).isAttachedToWindow
        Unit
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

    private fun captureHost(): Pair<Activity, View> {
        val activity = mock(Activity::class.java, withSettings().extraInterfaces(LifecycleOwner::class.java))
        val window = mock(Window::class.java)
        val decor = mock(View::class.java)
        `when`(activity.window).thenReturn(window)
        `when`(window.decorView).thenReturn(decor)
        `when`(decor.rootView).thenReturn(decor)
        `when`(decor.isAttachedToWindow).thenReturn(true)
        return activity to decor
    }
}
