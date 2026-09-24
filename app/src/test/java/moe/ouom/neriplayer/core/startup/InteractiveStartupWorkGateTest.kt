package moe.ouom.neriplayer.core.startup

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractiveStartupWorkGateTest {
    @Test
    fun `heavy startup work waits until interactive content is ready`() = runTest {
        val gate = InteractiveStartupWorkGate()
        val waiting = async {
            gate.awaitInteractiveContentOrTimeout(timeoutMillis = 1_000L)
        }

        yield()
        assertFalse(waiting.isCompleted)

        gate.markInteractiveContentReady()

        assertTrue(waiting.await())
    }

    @Test
    fun `headless startup fails open after the deadline`() = runTest {
        val gate = InteractiveStartupWorkGate()

        assertFalse(gate.awaitInteractiveContentOrTimeout(timeoutMillis = 100L))
    }
}
