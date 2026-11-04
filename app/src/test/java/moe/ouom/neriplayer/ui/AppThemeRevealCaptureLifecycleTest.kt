package moe.ouom.neriplayer.ui

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.view.PixelCopy
import android.view.View
import android.view.Window
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import moe.ouom.neriplayer.ui.theme.reveal.captureThemeRevealSnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.MockedStatic
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AppThemeRevealCaptureLifecycleTest {
    private val dispatcher = StandardTestDispatcher()
    private val activity = mock(Activity::class.java)
    private val fallback = mock(View::class.java)
    private val allocatedBitmaps = mutableListOf<Bitmap>()
    private lateinit var pixelCopy: MockedStatic<PixelCopy>
    private var pendingCopy: PendingCopy? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val window = mock(Window::class.java)
        val decor = mock(View::class.java)
        `when`(activity.window).thenReturn(window)
        `when`(window.decorView).thenReturn(decor)
        `when`(decor.width).thenReturn(100)
        `when`(decor.height).thenReturn(100)
        pixelCopy = mockStatic(PixelCopy::class.java)
        pixelCopy.`when`<Unit> {
            PixelCopy.request(
                any(Window::class.java),
                any(Bitmap::class.java),
                any(PixelCopy.OnPixelCopyFinishedListener::class.java),
                any(Handler::class.java)
            )
        }.thenAnswer { invocation ->
            val bitmap = invocation.getArgument<Bitmap>(1)
            val listener = invocation.getArgument<PixelCopy.OnPixelCopyFinishedListener>(2)
            allocatedBitmaps += bitmap
            pendingCopy = PendingCopy(bitmap, listener)
            null
        }
    }

    @After
    fun tearDown() {
        pixelCopy.close()
        allocatedBitmaps.filterNot(Bitmap::isRecycled).forEach(Bitmap::recycle)
        Dispatchers.resetMain()
    }

    @Test
    fun cancellationKeepsTheDestinationWritableUntilCopyCompletion() = runTest(dispatcher) {
        val capture = startCapture()
        val copy = checkNotNull(pendingCopy)

        capture.cancel()
        runCurrent()

        assertFalse("an in-flight PixelCopy still owns its destination", copy.bitmap.isRecycled)
        copy.bitmap.setPixel(0, 0, Color.GREEN)
        assertEquals(Color.GREEN, copy.bitmap.getPixel(0, 0))
        copy.finish(PixelCopy.ERROR_SOURCE_NO_DATA)
        assertTrue(capture.isCancelled)
    }

    @Test
    fun aFailedCopyRecyclesTheUnpublishedDestination() = runTest(dispatcher) {
        val capture = startCapture()
        val copy = checkNotNull(pendingCopy)

        copy.finish(PixelCopy.ERROR_SOURCE_NO_DATA)
        runCurrent()

        assertNull(capture.await())
        assertTrue("a failed copy must release its unpublished buffer", copy.bitmap.isRecycled)
    }

    @Test
    fun aSuccessfulCopyAfterCancellationRecyclesItsUnclaimedDestination() = runTest(dispatcher) {
        val capture = startCapture()
        val copy = checkNotNull(pendingCopy)

        capture.cancel()
        runCurrent()
        assertFalse(copy.bitmap.isRecycled)
        copy.bitmap.setPixel(0, 0, Color.BLUE)
        copy.finish(PixelCopy.SUCCESS)

        assertTrue(capture.isCancelled)
        assertTrue("the cancelled caller cannot claim the completed buffer", copy.bitmap.isRecycled)
    }

    @Test
    fun cancellationBeforeDispatchRecyclesTheCompletedButUndeliveredDestination() = runTest(dispatcher) {
        val capture = startCapture()
        val copy = checkNotNull(pendingCopy)

        copy.finish(PixelCopy.SUCCESS)
        assertFalse("completion is queued until the dispatcher runs", capture.isCompleted)
        assertFalse(copy.bitmap.isRecycled)
        capture.cancel()
        runCurrent()

        assertTrue(capture.isCancelled)
        assertTrue("a completed buffer must not be lost before delivery", copy.bitmap.isRecycled)
    }

    @Test
    fun successfulDeliveryTransfersAReadableDestinationToTheCaller() = runTest(dispatcher) {
        val capture = startCapture()
        val copy = checkNotNull(pendingCopy)
        copy.bitmap.setPixel(0, 0, Color.BLUE)

        copy.finish(PixelCopy.SUCCESS)
        runCurrent()
        val delivered = checkNotNull(capture.await()).asAndroidBitmap()

        assertSame(copy.bitmap, delivered)
        assertFalse("the caller owns a successfully delivered snapshot", delivered.isRecycled)
        assertEquals(Color.BLUE, delivered.getPixel(0, 0))
    }

    @Test
    fun cancellingAfterDeliveryDoesNotRecycleTheCallersSnapshot() = runTest(dispatcher) {
        val capture = startCapture()
        val copy = checkNotNull(pendingCopy)
        copy.bitmap.setPixel(0, 0, Color.GREEN)
        copy.finish(PixelCopy.SUCCESS)
        runCurrent()
        val delivered = checkNotNull(capture.await()).asAndroidBitmap()

        capture.cancel()
        runCurrent()

        assertFalse("a delivered snapshot may still be displayed", delivered.isRecycled)
        assertEquals(Color.GREEN, delivered.getPixel(0, 0))
    }

    private fun TestScope.startCapture(): Deferred<ImageBitmap?> {
        val capture = async(Dispatchers.Main) { captureThemeRevealSnapshot(activity, fallback) }
        runCurrent()
        checkNotNull(pendingCopy) { "the real capture must allocate and request a destination" }
        return capture
    }

    private class PendingCopy(
        val bitmap: Bitmap,
        private val listener: PixelCopy.OnPixelCopyFinishedListener
    ) {
        fun finish(result: Int) {
            listener.onPixelCopyFinished(result)
        }
    }
}
