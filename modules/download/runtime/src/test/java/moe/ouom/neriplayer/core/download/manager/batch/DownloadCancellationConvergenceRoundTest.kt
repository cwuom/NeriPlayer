package moe.ouom.neriplayer.core.download.manager.batch

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.download.DownloadExecutionRequest
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadCancellationConvergenceRoundTest {
    private val request = DownloadExecutionRequest(
        operationId = "cancelled",
        song = SongItem(1L, "song", "artist", "album", 0L, 0L, null)
    )

    @Test
    fun `failed snapshot reads preserve artifacts and durable cancellation evidence`() = runBlocking {
        val fixture = Fixture()
        val failure = IOException("snapshot unavailable")

        assertFalse(fixture.run { throw failure })

        assertTrue(fixture.artifactsPresent)
        assertTrue(fixture.evidencePresent)
        assertSame(failure, fixture.readFailure)
        assertTrue(fixture.events.isEmpty())
    }

    @Test
    fun `cancellation during snapshot loading propagates without cleanup`() = runBlocking {
        val fixture = Fixture()
        val cancellation = CancellationException("cancel convergence")

        val result = runCatching { fixture.run { throw cancellation } }

        assertSame(cancellation, result.exceptionOrNull())
        assertEquals(null, fixture.readFailure)
        assertTrue(fixture.evidencePresent)
        assertTrue(fixture.events.isEmpty())
    }

    @Test
    fun `an unconfirmed artifact cleanup cannot purge cancellation evidence`() = runBlocking {
        val fixture = Fixture(cleanupSucceeded = false)

        assertFalse(fixture.run { listOf(request) })

        assertTrue(fixture.artifactsPresent)
        assertTrue(fixture.evidencePresent)
        assertEquals(listOf("cleanup cancelled"), fixture.events)
    }

    @Test
    fun `confirmed cleanup completes before cancellation evidence is removed`() = runBlocking {
        val fixture = Fixture()

        assertTrue(fixture.run { listOf(request) })

        assertFalse(fixture.artifactsPresent)
        assertFalse(fixture.evidencePresent)
        assertEquals(listOf("cleanup cancelled", "finish cancelled"), fixture.events)
    }

    @Test
    fun `a later successful read can retry cleanup after a read failure`() = runBlocking {
        val fixture = Fixture()
        assertFalse(fixture.run { throw IOException("temporarily unavailable") })

        assertTrue(fixture.run { listOf(request) })

        assertFalse(fixture.artifactsPresent)
        assertFalse(fixture.evidencePresent)
        assertEquals(listOf("cleanup cancelled", "finish cancelled"), fixture.events)
    }

    @Test
    fun `cleanup exceptions propagate without finalizing the operation`() = runBlocking {
        val failure = IOException("cleanup failed")
        val effects = mutableListOf<String>()
        val result = runCatching {
            runCancellationConvergenceRound(
                loadRequests = { listOf(request) },
                onReadFailure = { effects += "read failure" },
                cleanup = { throw failure },
                finish = { effects += "finish" }
            )
        }

        assertSame(failure, result.exceptionOrNull())
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `a successful empty read still allows untracked cancellation cleanup`() = runBlocking {
        val fixture = Fixture()

        assertTrue(fixture.run { emptyList() })

        assertFalse(fixture.artifactsPresent)
        assertFalse(fixture.evidencePresent)
        assertEquals(listOf("cleanup ", "finish "), fixture.events)
    }

    private class Fixture(private val cleanupSucceeded: Boolean = true) {
        var artifactsPresent = true
        var evidencePresent = true
        var readFailure: Throwable? = null
        val events = mutableListOf<String>()

        suspend fun run(load: suspend () -> List<DownloadExecutionRequest>) = runCancellationConvergenceRound(
            loadRequests = load,
            onReadFailure = { readFailure = it },
            cleanup = { requests ->
                events += "cleanup ${requests.joinToString { it.operationId }}"
                if (cleanupSucceeded) artifactsPresent = false
                cleanupSucceeded
            },
            finish = { requests ->
                events += "finish ${requests.joinToString { it.operationId }}"
                evidencePresent = false
            }
        )
    }
}
