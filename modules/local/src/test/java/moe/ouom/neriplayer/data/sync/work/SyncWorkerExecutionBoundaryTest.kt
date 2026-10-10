package moe.ouom.neriplayer.data.sync.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ListenableWorker.Result as WorkResult
import androidx.work.WorkerParameters
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.sync.SyncResult
import moe.ouom.neriplayer.data.model.sync.SyncWorkerOutcome
import moe.ouom.neriplayer.data.sync.github.GitHubSyncWorker
import moe.ouom.neriplayer.data.sync.schedule.SyncWorkerHost
import moe.ouom.neriplayer.data.sync.webdav.WebDavSyncWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic

class SyncWorkerExecutionBoundaryTest {
    @Test
    fun `real workers pass startup input to the host and execute during playback while ordinary work defers`() = runBlocking(Dispatchers.IO) {
        for (provider in Provider.entries) {
            val startup = RecordingHost().apply { playback = true }
            withWorker(provider, startupInput(), startup) { worker ->
                assertEquals(WorkResult.success(), worker.doWork())
                assertEquals(listOf("automatic", "configured", "approval", "network", "sync"), startup.calls)
            }
            val ordinary = RecordingHost().apply { playback = true }
            withWorker(provider, Data.EMPTY, ordinary) { worker ->
                assertEquals(WorkResult.success(), worker.doWork())
                assertEquals(listOf("automatic", "configured", "approval", "playback", "defer"), ordinary.calls)
            }
        }
    }

    @Test
    fun `real workers read both manual inputs and bypass automatic settings and playback deferral`() = runBlocking(Dispatchers.IO) {
        for (provider in Provider.entries) {
            for (key in listOf("force_sync", "trigger_by_user_action")) {
                val host = RecordingHost().apply { automatic = false; playback = true }
                withWorker(provider, Data.Builder().putBoolean(key, true).build(), host) { worker ->
                    assertEquals(WorkResult.success(), worker.doWork())
                    assertEquals(listOf("configured", "approval", "network", "sync"), host.calls)
                    assertNull(host.failure)
                }
            }
        }
    }

    @Test
    fun `real startup workers retain the automatic configuration and protocol approval gates`() = runBlocking(Dispatchers.IO) {
        for (provider in Provider.entries) {
            val gates = listOf(
                RecordingHost().apply { automatic = false } to listOf("automatic"),
                RecordingHost().apply { configured = false } to listOf("automatic", "configured"),
                RecordingHost().apply { approved = false } to listOf("automatic", "configured", "approval")
            )
            for ((host, expectedCalls) in gates) {
                withWorker(provider, startupInput(), host) { worker ->
                    assertEquals(WorkResult.success(), worker.doWork())
                    assertEquals(expectedCalls, host.calls)
                    assertNull(host.failure)
                }
            }
        }
    }

    @Test
    fun `real startup ordinary and manual workers return retry offline without synchronizing`() = runBlocking(Dispatchers.IO) {
        val inputs = listOf(startupInput(), Data.EMPTY,
            Data.Builder().putBoolean("trigger_by_user_action", true).build(),
            Data.Builder().putBoolean("force_sync", true).build())
        for (provider in Provider.entries) {
            for (input in inputs) {
                val host = RecordingHost().apply { network = false }
                withWorker(provider, input, host) { worker ->
                    assertEquals(WorkResult.retry(), worker.doWork())
                    assertEquals("network", host.calls.last())
                    assertFalse("sync" in host.calls)
                    assertFalse("defer" in host.calls)
                    assertNull(host.failure)
                }
            }
        }
    }

    @Test
    fun `real workers adapt failure outcomes and retain manual classification for returned and thrown errors`() = runBlocking(Dispatchers.IO) {
        for (provider in Provider.entries) {
            for (manual in listOf(false, true)) {
                val input = if (manual) Data.Builder().putBoolean("trigger_by_user_action", true).build()
                    else startupInput()
                val returnedError = IOException("remote rejected sync")
                val returned = RecordingHost().apply {
                    result = Result.failure(returnedError)
                    failureOutcome = SyncWorkerOutcome.FAILURE
                }
                withWorker(provider, input, returned) { worker ->
                    assertEquals(WorkResult.failure(), worker.doWork())
                    assertEquals(Triple(returnedError, manual, false), returned.failure)
                }
                val thrownError = IOException("remote unavailable")
                val thrown = RecordingHost().apply { synchronizeAction = { throw thrownError } }
                withWorker(provider, input, thrown) { worker ->
                    assertEquals(WorkResult.retry(), worker.doWork())
                    assertEquals(Triple(thrownError, manual, true), thrown.failure)
                }
            }
        }
    }

    @Test
    fun `cancelling real workers during a suspended sync propagates without classifying failure`() = runBlocking(Dispatchers.IO) {
        for (provider in Provider.entries) {
            val started = CompletableDeferred<Unit>()
            val remoteResult = CompletableDeferred<Result<SyncResult>>()
            val host = RecordingHost().apply {
                synchronizeAction = { started.complete(Unit); remoteResult.await() }
            }
            withWorker(provider, startupInput(), host) { worker ->
                val execution = async(start = CoroutineStart.UNDISPATCHED) { worker.doWork() }
                started.await()
                assertFalse(execution.isCompleted)
                execution.cancelAndJoin()
                assertTrue(execution.isCancelled)
                assertTrue(runCatching { execution.await() }.exceptionOrNull() is CancellationException)
                assertEquals(listOf("automatic", "configured", "approval", "network", "sync"), host.calls)
                assertNull(host.failure)
            }
        }
    }

    private fun startupInput(): Data = Data.Builder().putBoolean("trigger_by_app_startup", true).build()

    private suspend fun withWorker(
        provider: Provider,
        input: Data,
        host: RecordingHost,
        action: suspend (CoroutineWorker) -> Unit
    ) {
        val context = mock(Context::class.java)
        val parameters = mock(WorkerParameters::class.java)
        `when`(parameters.inputData).thenReturn(input)
        val startup = input.getBoolean("trigger_by_app_startup", false)
        val passedOrigins = mutableListOf<Boolean>()
        // 静态替身与真实 doWork 使用同一 IO 上下文，保留生产调度并避免线程局部替身失效
        mockStatic(Class.forName(provider.factoryClass)).use { factory ->
            val binding = when (provider) {
                Provider.GITHUB -> factory.`when`<SyncWorkerHost> { createGitHubWorkerHost(context, startup) }
                Provider.WEBDAV -> factory.`when`<SyncWorkerHost> { createWebDavWorkerHost(context, startup) }
            }
            binding.thenAnswer { invocation ->
                passedOrigins += invocation.getArgument<Boolean>(1)
                host
            }
            val worker = when (provider) {
                Provider.GITHUB -> GitHubSyncWorker(context, parameters)
                Provider.WEBDAV -> WebDavSyncWorker(context, parameters)
            }
            action(worker)
            assertEquals(listOf(startup), passedOrigins)
        }
    }

    private enum class Provider(val factoryClass: String) {
        GITHUB("moe.ouom.neriplayer.data.sync.work.GitHubSyncWorkerHostKt"),
        WEBDAV("moe.ouom.neriplayer.data.sync.work.WebDavSyncWorkerHostKt")
    }

    private class RecordingHost : SyncWorkerHost {
        val calls = mutableListOf<String>()
        var automatic = true
        var configured = true
        var approved = true
        var playback = false
        var network = true
        var result = Result.success(SyncResult(true, "synced"))
        var synchronizeAction: suspend () -> Result<SyncResult> = { result }
        var failureOutcome = SyncWorkerOutcome.RETRY
        var failure: Triple<Throwable?, Boolean, Boolean>? = null
        override fun autoSyncEnabled(): Boolean { calls += "automatic"; return automatic }
        override fun configured(): Boolean { calls += "configured"; return configured }
        override suspend fun protocolUpgradeApproved(): Boolean { calls += "approval"; return approved }
        override fun playbackActive(): Boolean { calls += "playback"; return playback }
        override fun validatedNetwork(): Boolean { calls += "network"; return network }
        override fun deferForPlayback() { calls += "defer" }
        override suspend fun synchronize(): Result<SyncResult> { calls += "sync"; return synchronizeAction() }
        override suspend fun handleFailure(error: Throwable?, manual: Boolean, unexpected: Boolean): SyncWorkerOutcome {
            failure = Triple(error, manual, unexpected)
            return failureOutcome
        }
    }
}
