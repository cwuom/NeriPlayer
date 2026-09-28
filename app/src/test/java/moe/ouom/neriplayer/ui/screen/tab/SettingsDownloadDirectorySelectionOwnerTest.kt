package moe.ouom.neriplayer.ui.screen.tab

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.operation.DownloadDirectoryPreparationResult
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.operation.DownloadDirectorySelectionGateway
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.operation.DownloadDirectorySelectionOwner
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.operation.selectedDownloadDirectorySummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsDownloadDirectorySelectionOwnerTest {
    @Test
    fun `selected directory summary distinguishes provider timeout and failure`() {
        assertEquals("New", selectedDownloadDirectorySummary(Result.success("New")))
        assertEquals(
            "provider failed",
            runCatching { selectedDownloadDirectorySummary(Result.failure(IllegalStateException("provider failed"))) }
                .exceptionOrNull()?.message
        )
        assertTrue(runCatching { selectedDownloadDirectorySummary(null) }.isFailure)
    }

    @Test
    fun `cancelled picker and blocked selection do not start a job`() = runTest {
        val fixture = Fixture(this)
        fixture.owner.onPicked(null)
        assertTrue(fixture.events.isEmpty())

        fixture.blocked = true
        fixture.owner.onPicked("content://new")
        advanceUntilIdle()

        assertEquals(listOf("guard"), fixture.events)
        assertFalse(fixture.preparing.value)
        assertNull(fixture.job.value)
    }

    @Test
    fun `picked grant stays owned after successful preparation`() = runTest {
        val fixture = Fixture(this)

        fixture.owner.onPicked("content://new")
        assertTrue(fixture.preparing.value)
        advanceUntilIdle()

        assertEquals(
            listOf("guard", "persist", "describe", "prepare:content://new:New"),
            fixture.events
        )
        assertFalse(fixture.permissionLost.value)
        assertFalse(fixture.preparing.value)
        assertNull(fixture.job.value)
    }

    @Test
    fun `retryable preparation releases only the newly persisted grant`() = runTest {
        val fixture = Fixture(this).apply {
            preparation = DownloadDirectoryPreparationResult.RELEASE_PERSISTED_PERMISSION
        }

        fixture.owner.onPicked("content://new")
        advanceUntilIdle()

        assertEquals("release:content://new", fixture.events.last())
        assertFalse(fixture.preparing.value)
    }

    @Test
    fun `failed persistence reports error without releasing a grant that was never acquired`() = runTest {
        val fixture = Fixture(this).apply { gateway.persistError = IllegalStateException("grant failed") }

        fixture.owner.onPicked("content://new")
        advanceUntilIdle()

        assertEquals(listOf("guard", "persist", "error:grant failed"), fixture.events)
        assertTrue(fixture.permissionLost.value)
        assertFalse(fixture.preparing.value)
        assertNull(fixture.job.value)
    }

    @Test
    fun `failed description reports error and releases the acquired grant`() = runTest {
        val fixture = Fixture(this).apply { gateway.describeError = IllegalStateException("provider failed") }

        fixture.owner.onPicked("content://new")
        advanceUntilIdle()

        assertEquals(
            listOf("guard", "persist", "describe", "error:provider failed", "release:content://new"),
            fixture.events
        )
        assertFalse(fixture.preparing.value)
        assertNull(fixture.job.value)
    }

    @Test
    fun `cancelled preparation releases the acquired grant without reporting a normal error`() = runTest {
        val fixture = Fixture(this).apply { gateway.describeError = CancellationException("cancel") }

        fixture.owner.onPicked("content://new")
        advanceUntilIdle()

        assertEquals(listOf("guard", "persist", "describe", "release:content://new"), fixture.events)
        assertFalse(fixture.preparing.value)
        assertNull(fixture.job.value)
    }

    @Test
    fun `cancellation after provider persists a grant still releases that grant`() = runTest {
        val fixture = Fixture(this).apply { gateway.cancelAfterPersist = true }

        fixture.owner.onPicked("content://new")
        advanceUntilIdle()

        assertEquals(listOf("guard", "persist", "release:content://new"), fixture.events)
        assertFalse(fixture.preparing.value)
    }

    private class Fixture(scope: CoroutineScope) {
        val events = mutableListOf<String>()
        val gateway = FakeGateway(events)
        val preparing = mutableStateOf(false)
        val job = mutableStateOf<Job?>(null)
        val permissionLost = mutableStateOf(true)
        var blocked = false
        var preparation = DownloadDirectoryPreparationResult.KEEP_PERSISTED_PERMISSION
        val owner = DownloadDirectorySelectionOwner(
            gateway = gateway,
            scope = scope,
            isPreparingState = preparing,
            preparationJobState = job,
            permissionLostState = permissionLost,
            isBlocked = { events += "guard"; blocked },
            prepare = { uri, summary ->
                events += "prepare:$uri:$summary"
                preparation
            },
            onError = { events += "error:${it.message}" }
        )
    }

    private class FakeGateway(private val events: MutableList<String>) :
        DownloadDirectorySelectionGateway {
        var persistError: Exception? = null
        var describeError: Exception? = null
        var cancelAfterPersist = false

        override suspend fun persistGrant(targetUri: String, onPersisted: () -> Unit) {
            events += "persist"
            persistError?.let { throw it }
            onPersisted()
            if (cancelAfterPersist) throw CancellationException("cancel after grant")
        }

        override suspend fun describe(targetUri: String): String {
            events += "describe"
            describeError?.let { throw it }
            return "New"
        }

        override suspend fun releaseGrant(targetUri: String) {
            events += "release:$targetUri"
        }
    }
}
