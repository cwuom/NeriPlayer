package moe.ouom.neriplayer.util.coroutines

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class RunCatchingNonCancellationTest {
    @Test
    fun `returns successful result`() = runBlocking {
        assertEquals("ready", runCatchingNonCancellation { "ready" }.getOrThrow())
    }

    @Test
    fun `ordinary exception remains available to caller`() = runBlocking {
        val failure = IllegalStateException("unavailable")

        val result = runCatchingNonCancellation<String> { throw failure }

        assertSame(failure, result.exceptionOrNull())
    }

    @Test
    fun `cancellation escapes result handling`() = runBlocking {
        val cancellation = CancellationException("cancelled")

        try {
            runCatchingNonCancellation<Unit> { throw cancellation }
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }
}
