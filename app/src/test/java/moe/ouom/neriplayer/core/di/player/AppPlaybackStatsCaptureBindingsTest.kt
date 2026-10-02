package moe.ouom.neriplayer.core.di.player

import android.content.Context
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.stats.PlaybackStatsCaptureBarrier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class AppPlaybackStatsCaptureBindingsTest {
    @After fun resetCaptureBarrier() {
        PlaybackStatsCaptureBarrier.install {}
    }

    @Test fun mainProcessInstallsRecoveryWithoutPreparingPlayerOrNormalComponents() = runTest {
        val context = mock(Context::class.java)
        val application = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(application)
        val received = mutableListOf<Context>()
        installPlaybackStatsCaptureBarrier(runningInMainProcess = true) { received += it }
        PlaybackStatsCaptureBarrier.await(context)
        assertEquals(listOf(application), received)
    }

    @Test fun auxiliaryProcessDoesNotReplaceTheExistingCaptureBinding() = runTest {
        val context = mock(Context::class.java)
        var calls = 0
        PlaybackStatsCaptureBarrier.install { calls++ }
        installPlaybackStatsCaptureBarrier(runningInMainProcess = false) { error("auxiliary recovery must not run") }
        PlaybackStatsCaptureBarrier.await(context)
        assertEquals(1, calls)
    }

    @Test fun mainProcessRestoreBindingKeepsTheOperationInsideTheRuntimeGuard() = runTest {
        val context = mock(Context::class.java)
        val application = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(application)
        val events = mutableListOf<String>()
        installPlaybackStatsCaptureBarrier(
            runningInMainProcess = true,
            restore = { received, block ->
                assertSame(application, received)
                events += "paused"
                try { block() } finally { events += "resumed" }
            },
            flush = { error("restore must use its complete runtime guard") }
        )
        val failure = IOException("import failed")
        assertSame(failure, runCatching {
            PlaybackStatsCaptureBarrier.withRestore(context) {
                events += "import"
                throw failure
            }
        }.exceptionOrNull())
        assertEquals(listOf("paused", "import", "resumed"), events)
    }

    @Test fun auxiliaryProcessDoesNotReplaceTheExistingRestoreBinding() = runTest {
        val context = mock(Context::class.java)
        val events = mutableListOf<String>()
        PlaybackStatsCaptureBarrier.install({}, { _, block ->
            events += "guard"
            block()
        })
        installPlaybackStatsCaptureBarrier(
            runningInMainProcess = false,
            restore = { _, _ -> error("auxiliary restore must not run") },
            flush = { error("auxiliary recovery must not run") }
        )
        PlaybackStatsCaptureBarrier.withRestore(context) { events += "import" }
        assertEquals(listOf("guard", "import"), events)
    }

    @Test fun storageAndCancellationFailuresRemainVisibleToTheCaptureCaller() = runTest {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        for (failure in listOf(IOException("spool unavailable"), CancellationException("capture cancelled"))) {
            installPlaybackStatsCaptureBarrier(runningInMainProcess = true) { throw failure }
            assertSame(failure, runCatching { PlaybackStatsCaptureBarrier.await(context) }.exceptionOrNull())
        }
    }
}
