package moe.ouom.neriplayer.data.sync.github

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SyncUploadRetryExecutorTest {
    @Test
    fun `skips upload when merged state has no meaningful change`() = runTest {
        var uploadCalled = false

        val result = SyncUploadRetryExecutor.execute<Int, String, String>(
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

        val result = SyncUploadRetryExecutor.execute<Int, String, String>(
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
                    Result.failure(GitHubContentConflictException(409, "sha mismatch"))
                } else {
                    Result.success("v3")
                }
            },
            refetch = {
                refetchCount++
                Result.success(2 to "v2")
            },
            isConflict = { it is GitHubContentConflictException }
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

        val result = SyncUploadRetryExecutor.execute<Int, String, String>(
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

        val result = SyncUploadRetryExecutor.execute<Int, String, String>(
            initialRemote = 1,
            initialVersion = "v1",
            initialRemoteChangedDuringSync = false,
            maxConflictRetries = 1,
            merge = { "merged-$it" },
            hasMeaningfulChange = { _, _ -> true },
            upload = { _, _ ->
                Result.failure(GitHubContentConflictException(409, "still conflicting"))
            },
            refetch = {
                refetchCount++
                Result.success(2 to "v2")
            },
            isConflict = { it is GitHubContentConflictException }
        )

        assertTrue(result.isFailure)
        assertEquals(1, refetchCount)
        assertEquals("still conflicting", result.exceptionOrNull()?.message)
    }
}
