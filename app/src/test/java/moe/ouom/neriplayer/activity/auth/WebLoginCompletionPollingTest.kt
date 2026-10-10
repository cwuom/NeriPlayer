package moe.ouom.neriplayer.activity.auth

import android.os.Handler
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

class WebLoginCompletionPollingTest {

    @Test
    fun `polling keeps checking until the login completes and then stops`() {
        var completed = false
        var checks = 0
        watch(onCheck = { checks++; completed }) { watcher, handler ->
            watcher.start()
            val poll = postedRunnable(handler, delayMs = 800L)

            poll.run()
            assertEquals(1, checks)
            verify(handler, times(2)).postDelayed(poll, 800L)

            completed = true
            poll.run()
            assertEquals(2, checks)
            verify(handler).removeCallbacksAndMessages(null)

            poll.run()
            assertEquals(2, checks)
            verify(handler, times(2)).postDelayed(poll, 800L)
        }
    }

    @Test
    fun `a successful debounced check stops the watcher and ignores later checks`() {
        var checks = 0
        watch(onCheck = { checks++; true }) { watcher, handler ->
            watcher.start()
            val initialCheck = postedRunnable(handler, delayMs = 0L)

            initialCheck.run()
            assertEquals(1, checks)
            verify(handler).removeCallbacksAndMessages(null)

            watcher.scheduleCheck()
            initialCheck.run()
            assertEquals(1, checks)
            verify(handler, never()).postDelayed(any(), eq(200L))
        }
    }

    @Test
    fun `a pending check is debounced while the watcher is running`() {
        var checks = 0
        watch(onCheck = { checks++; false }) { watcher, handler ->
            watcher.start()
            watcher.start()
            watcher.scheduleCheck(delayMs = -50L)

            val debounced = postedRunnable(handler, delayMs = 0L)
            verify(handler, times(2)).removeCallbacks(debounced)
            verify(handler, times(1)).postDelayed(any(), eq(800L))
            debounced.run()
            assertEquals(1, checks)
            verify(handler, never()).removeCallbacksAndMessages(null)
        }
    }

    private fun watch(
        onCheck: () -> Boolean,
        block: (WebLoginCompletionWatcher, Handler) -> Unit
    ) {
        mockConstruction(Handler::class.java).use { handlers ->
            val watcher = WebLoginCompletionWatcher(onCheck = onCheck, pollIntervalMs = 800L, debounceMs = 200L)
            block(watcher, handlers.constructed().single())
        }
    }

    private fun postedRunnable(handler: Handler, delayMs: Long): Runnable {
        val runnable = ArgumentCaptor.forClass(Runnable::class.java)
        verify(handler, atLeastOnce()).postDelayed(runnable.capture(), eq(delayMs))
        return runnable.value
    }
}
