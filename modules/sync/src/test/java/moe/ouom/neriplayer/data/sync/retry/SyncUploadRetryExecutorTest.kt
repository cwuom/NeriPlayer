package moe.ouom.neriplayer.data.sync.retry

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.IOException

class SyncUploadRetryExecutorTest {
    @Test
    fun `skips upload when merged state has no meaningful change`() = runTest {
        var uploadCalled = false

        val result = SyncUploadRetryExecutor.execute(
            initialRemote = 1,
            initialVersion = "v1",
            initialRemoteChangedDuringSync = false,
            merge = { remote -> "merged-$remote" },
            hasMeaningfulChange = { _, _ -> false },
            upload = { _, _ ->
                uploadCalled = true
                Result.success("v2")
            },
            refetch = { Result.success(2 to "v2") },
            isConflict = { false }
        ).getOrThrow()

        assertEquals("merged-1", result.merged)
        assertEquals("v1", result.remoteVersion)
        assertFalse(result.uploadPerformed)
        assertFalse(result.remoteChangedDuringSync)
        assertFalse(uploadCalled)
    }

    @Test
    fun `retries on conflict and marks remote changed`() = runTest {
        val mergedInputs = mutableListOf<Int>()
        var uploadAttempts = 0
        var refetchCount = 0

        val result = SyncUploadRetryExecutor.execute(
            initialRemote = 1,
            initialVersion = "v1",
            initialRemoteChangedDuringSync = false,
            merge = { remote ->
                mergedInputs += remote
                "merged-$remote"
            },
            hasMeaningfulChange = { _, _ -> true },
            upload = { _, version ->
                uploadAttempts++
                if (version == "v1") {
                    Result.failure(ConflictException("sha mismatch"))
                } else {
                    Result.success("v3")
                }
            },
            refetch = {
                refetchCount++
                Result.success(2 to "v2")
            },
            isConflict = { it is ConflictException }
        ).getOrThrow()

        assertEquals(listOf(1, 2), mergedInputs)
        assertEquals(2, uploadAttempts)
        assertEquals(1, refetchCount)
        assertEquals("merged-2", result.merged)
        assertEquals("v3", result.remoteVersion)
        assertTrue(result.uploadPerformed)
        assertTrue(result.remoteChangedDuringSync)
    }

    @Test
    fun `stops immediately on non conflict failure`() = runTest {
        var refetchCalled = false

        val result = SyncUploadRetryExecutor.execute(
            initialRemote = 1,
            initialVersion = "v1",
            initialRemoteChangedDuringSync = false,
            merge = { "merged-$it" },
            hasMeaningfulChange = { _, _ -> true },
            upload = { _, _ -> Result.failure(IOException("network down")) },
            refetch = {
                refetchCalled = true
                Result.success(2 to "v2")
            },
            isConflict = { false }
        )

        assertTrue(result.isFailure)
        assertFalse(refetchCalled)
        assertEquals("network down", result.exceptionOrNull()?.message)
    }

    @Test
    fun `fails after conflict retry budget exhausted`() = runTest {
        var refetchCount = 0

        val result = SyncUploadRetryExecutor.execute(
            initialRemote = 1,
            initialVersion = "v1",
            initialRemoteChangedDuringSync = false,
            maxConflictRetries = 1,
            merge = { "merged-$it" },
            hasMeaningfulChange = { _, _ -> true },
            upload = { _, _ ->
                Result.failure(ConflictException("still conflicting"))
            },
            refetch = {
                refetchCount++
                Result.success(2 to "v2")
            },
            isConflict = { it is ConflictException }
        )

        assertTrue(result.isFailure)
        assertEquals(1, refetchCount)
        assertEquals("still conflicting", result.exceptionOrNull()?.message)
    }

    @Test fun `comparison cancellation closes both owned datasets and retains cleanup failures`() = runTest {
        val cancellation = CancellationException("comparison cancelled")
        val closes = ArrayList<String>()
        val mergedCleanup = IOException("merged cleanup")
        val remoteCleanup = IOException("remote cleanup")
        val failure = runCatching {
            SyncUploadRetryExecutor.execute(
                initialRemote = "remote", initialVersion = 1, initialRemoteChangedDuringSync = false,
                merge = { "merged" }, hasMeaningfulChange = { _, _ -> throw cancellation },
                upload = { _, _ -> error("comparison has not approved upload") }, refetch = { error("no conflict") },
                isConflict = { false },
                disposeMerged = { closes.add(it); throw mergedCleanup },
                disposeRemote = { closes.add(it); throw remoteCleanup }
            )
        }.exceptionOrNull()
        assertSame(cancellation, failure)
        assertEquals(listOf("merged", "remote"), closes)
        assertEquals(listOf(mergedCleanup, remoteCleanup), cancellation.suppressed.toList())
    }

    @Test fun `merge failure and upload cancellation release exactly the resources they own`() = runTest {
        for (duringMerge in listOf(true, false)) {
            val cancellation = CancellationException("operation cancelled")
            val closes = ArrayList<String>()
            val failure = runCatching {
                SyncUploadRetryExecutor.execute(
                    initialRemote = "remote", initialVersion = 1, initialRemoteChangedDuringSync = false,
                    merge = { if (duringMerge) throw cancellation else "merged" },
                    hasMeaningfulChange = { _, _ -> true }, upload = { _, _ -> throw cancellation },
                    refetch = { error("cancel must propagate") }, isConflict = { false },
                    disposeMerged = { closes.add(it) }, disposeRemote = { closes.add(it) }
                )
            }.exceptionOrNull()
            assertSame(cancellation, failure)
            assertEquals(if (duringMerge) listOf("remote") else listOf("remote", "merged"), closes)
        }
    }

    @Test fun `conflict retry disposes old inputs and transfers only the successful result`() = runTest {
        val closes = ArrayList<String>()
        val result = SyncUploadRetryExecutor.execute(
            initialRemote = "remote-1", initialVersion = 1, initialRemoteChangedDuringSync = false,
            merge = { "merged-$it" }, hasMeaningfulChange = { _, _ -> true },
            upload = { _, version -> if (version == 1) Result.failure(ConflictException("changed")) else Result.success(3) },
            refetch = { Result.success("remote-2" to 2) }, isConflict = { it is ConflictException },
            disposeMerged = { closes.add(it) }, disposeRemote = { closes.add(it) }
        ).getOrThrow()
        assertEquals(listOf("remote-1", "merged-remote-1", "remote-2"), closes)
        assertEquals("merged-remote-2", result.merged)
        assertTrue(result.remoteChangedDuringSync)
        assertTrue(result.uploadPerformed)
        assertEquals(3, result.remoteVersion)
    }

    @Test fun `refetch failures do not reclaim a disposed snapshot or acknowledge an upload`() = runTest {
        for (cancelled in listOf(false, true)) {
            val closes = ArrayList<String>()
            val failure = IOException("refetch unavailable")
            val cancellation = CancellationException("refetch cancelled")
            val result = runCatching {
                SyncUploadRetryExecutor.execute(
                    initialRemote = "remote", initialVersion = 1, initialRemoteChangedDuringSync = false,
                    merge = { "merged" }, hasMeaningfulChange = { _, _ -> true },
                    upload = { _, _ -> Result.failure(ConflictException("changed")) },
                    refetch = { if (cancelled) throw cancellation else Result.failure(failure) },
                    isConflict = { it is ConflictException },
                    disposeMerged = { closes.add(it) }, disposeRemote = { closes.add(it) }
                )
            }
            if (cancelled) assertSame(cancellation, result.exceptionOrNull())
            else assertSame(failure, result.getOrThrow().exceptionOrNull())
            assertEquals(listOf("remote", "merged"), closes)
        }
    }
    private class ConflictException(message: String) : IOException(message)
}
