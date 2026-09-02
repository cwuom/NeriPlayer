package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Answers
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class DownloadExecutionHostCancellationRaceTest {
    @Test
    fun `cancel all keeps executing admission until execution finishes`() = runTest {
        runCancellationRace { host, context, operationId ->
            host.cancelAll(context, setOf(operationId))
        }
    }

    @Test
    fun `cancel all owned keeps executing admission until execution finishes`() = runTest {
        runCancellationRace { host, context, _ ->
            host.cancelAllOwned(context)
        }
    }

    private suspend fun runCancellationRace(
        cancel: suspend (DefaultDownloadExecutionHost, Context, String) -> Unit
    ) = coroutineScope {
        val context = mockContext()
        val journal = InMemoryDownloadExecutionOperationJournal()
        val store = DownloadExecutionOperationStore { journal }
        val request = DownloadExecutionRequest(
            operationId = "operation-cancel-race",
            song = SongItem(
                id = 42L,
                name = "Song",
                artist = "Artist",
                album = "Album",
                albumId = 7L,
                durationMs = 1_234L,
                coverUrl = null
            )
        )
        store.save(context, request)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { _, _ ->
                started.complete(Unit)
                finish.await()
                DownloadExecutionResult.Accepted
            },
            sdkInt = 28
        )

        val execution = async { host.execute(context, request.operationId) }
        started.await()
        cancel(host, context, request.operationId)

        assertEquals(0, journal.hostAdmissionReleaseCount)
        finish.complete(Unit)
        execution.await()
        assertEquals(1, journal.hostAdmissionReleaseCount)
    }

    private fun mockContext(): Context {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java, Answers.RETURNS_SELF)
        `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(preferences)
        `when`(preferences.getBoolean(anyString(), anyBoolean())).thenReturn(false)
        `when`(preferences.edit()).thenReturn(editor)
        return context
    }
}
