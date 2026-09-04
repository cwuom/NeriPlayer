package moe.ouom.neriplayer.core.download.execution

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadExecutionPumpIsolationTest {
    @Test
    fun `单项取消不会取消同批其他 operation`() = runTest {
        var siblingExecuted = false
        val results = supervisorScope {
            listOf(
                async {
                    executePumpCandidateIsolated("cancelled") {
                        throw CancellationException("user cancelled")
                    }
                },
                async {
                    siblingExecuted = true
                    executePumpCandidateIsolated("sibling") {
                        DownloadExecutionResult.Accepted
                    }
                }
            ).map { it.await() }
        }

        assertEquals(DownloadExecutionResult.Cancelled, results[0])
        assertEquals(DownloadExecutionResult.Accepted, results[1])
        assertTrue(siblingExecuted)
    }
}
