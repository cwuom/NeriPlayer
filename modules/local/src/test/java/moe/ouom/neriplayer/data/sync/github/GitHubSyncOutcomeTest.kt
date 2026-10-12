package moe.ouom.neriplayer.data.sync.github

import java.io.IOException
import moe.ouom.neriplayer.data.model.sync.SyncResult
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.mock

class GitHubSyncOutcomeTest {
    private val manager = mock(GitHubSyncManager::class.java, CALLS_REAL_METHODS)

    @Test
    fun `successful sync is classified without including its detail message`() {
        val result = Result.success(SyncResult(success = true, message = "fixture token=simulated-private-value"))

        assertEquals("success", manager.syncOutcome(result))
    }

    @Test
    fun `unfinished sync is deferred without including its detail message`() {
        val result = Result.success(SyncResult(success = false, message = "fixture owner=simulated-private-owner"))

        assertEquals("deferred", manager.syncOutcome(result))
    }

    @Test
    fun `failed sync reports only the exception class without its sensitive message`() {
        val result = Result.failure<SyncResult>(IOException("fixture token=simulated-private-value"))

        assertEquals("failed:IOException", manager.syncOutcome(result))
    }
}
