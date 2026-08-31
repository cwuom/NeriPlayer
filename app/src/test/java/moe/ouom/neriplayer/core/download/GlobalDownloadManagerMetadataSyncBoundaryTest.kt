package moe.ouom.neriplayer.core.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.DownloadedAudioMetadata
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.StoredEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalDownloadManagerMetadataSyncBoundaryTest {
    @Test
    fun `durable metadata prefers current snapshot reference after migration`() {
        val currentReference =
            "content://com.android.externalstorage.documents/document/primary%3AMusic%2Fsong.mp3"
        val staleReference = "file:///storage/emulated/0/Android/data/app/files/song.mp3"
        val audio = StoredEntry(
            name = "song.mp3",
            reference = currentReference,
            mediaUri = currentReference,
            localFilePath = null,
            sizeBytes = 10L,
            lastModifiedMs = 20L
        )
        val metadata = DownloadedAudioMetadata(
            stableKey = "netease:1",
            mediaUri = staleReference
        )

        assertEquals(
            currentReference,
            GlobalDownloadManager.resolveDurableMetadataPlaybackReference(audio, metadata)
        )
    }

    @Test
    fun `non cancellation failure is observed at metadata task boundary`(): Unit = runBlocking {
        val failure = IllegalArgumentException("sidecar provider failure")
        var observed: Throwable? = null

        val outcome = runDownloadedSongMetadataSyncSafely(
            block = { throw failure },
            onFailure = { observed = it }
        )

        assertNull(outcome)
        assertSame(failure, observed)
    }

    @Test(expected = CancellationException::class)
    fun `cancellation still propagates through metadata task boundary`(): Unit = runBlocking {
        runDownloadedSongMetadataSyncSafely(
            block = { throw CancellationException("cancelled") },
            onFailure = { error -> throw AssertionError("cancellation was swallowed", error) }
        )
    }

    @Test
    fun `successful metadata task keeps its outcome`(): Unit = runBlocking {
        val outcome = runDownloadedSongMetadataSyncSafely(
            block = { GlobalDownloadManager.DownloadedSongMetadataSyncOutcome.SUCCESS },
            onFailure = { error -> throw AssertionError("unexpected failure", error) }
        )

        assertEquals(GlobalDownloadManager.DownloadedSongMetadataSyncOutcome.SUCCESS, outcome)
    }

    @Test
    fun `startup recovery boundary records non cancellation failure`(): Unit = runBlocking {
        val failure = IllegalStateException("startup provider failure")
        var observed: Throwable? = null

        runDownloadStartupRecoverySafely(
            block = { throw failure },
            onFailure = { observed = it }
        )

        assertSame(failure, observed)
    }

    @Test
    fun `startup recovery retries a transient failure within the attempt budget`(): Unit = runBlocking {
        var attempts = 0
        var failures = 0

        val recovered = runDownloadStartupRecoverySafely(
            block = {
                attempts++
                if (attempts < 3) {
                    throw IllegalStateException("transient provider failure")
                }
            },
            onFailure = { failures++ },
            maxAttempts = 3
        )

        assertTrue(recovered)
        assertEquals(3, attempts)
        assertEquals(2, failures)
    }

    @Test
    fun `startup recovery reports exhaustion without swallowing cancellation`(): Unit = runBlocking {
        var attempts = 0

        val recovered = runDownloadStartupRecoverySafely(
            block = {
                attempts++
                throw IllegalStateException("persistent provider failure")
            },
            onFailure = {},
            maxAttempts = 2
        )

        assertFalse(recovered)
        assertEquals(2, attempts)
    }

    @Test(expected = CancellationException::class)
    fun `startup recovery boundary preserves cancellation`(): Unit = runBlocking {
        runDownloadStartupRecoverySafely(
            block = { throw CancellationException("startup cancelled") },
            onFailure = { error -> throw AssertionError("cancellation was swallowed", error) }
        )
    }
}
