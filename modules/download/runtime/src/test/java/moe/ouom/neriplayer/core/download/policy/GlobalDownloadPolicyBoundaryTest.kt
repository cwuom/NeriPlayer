package moe.ouom.neriplayer.core.download.policy

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.model.download.DownloadedSong
import moe.ouom.neriplayer.data.model.download.ManagedDownloadRestorableMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalDownloadPolicyBoundaryTest {

    @Test
    fun `durable operations recover the most advanced states first`() {
        val expected = linkedMapOf(
            "DEGRADED_COMPLETE" to 8,
            "ASSETS_ENRICHING" to 7,
            "CORE_COMMITTED" to 6,
            "COMMITTING" to 5,
            "RUNNING" to 4,
            "RETRYABLE" to 3,
            "QUEUED" to 2,
            "PENDING_QUEUE" to 1,
            "running" to 0,
            "" to 0
        )

        expected.forEach { (state, priority) ->
            assertEquals(state, priority, durableOperationRecoveryPriority(state))
        }
        assertEquals(
            listOf("CORE_COMMITTED", "RUNNING", "QUEUED", "UNKNOWN"),
            listOf("QUEUED", "UNKNOWN", "CORE_COMMITTED", "RUNNING")
                .sortedByDescending(::durableOperationRecoveryPriority)
        )
    }

    @Test
    fun `catalog references are observed through any snapshot reference form`() {
        val snapshot = ManagedDownloadStorage.emptyDownloadLibrarySnapshot().copy(
            audioEntries = listOf(
                ManagedDownloadStorage.StoredEntry(
                    name = "a.mp3",
                    reference = "content://tree/a",
                    mediaUri = "content://media/a",
                    localFilePath = "/music/a.mp3",
                    sizeBytes = 1L,
                    lastModifiedMs = 1L
                )
            ),
            pendingAudioEntries = listOf(
                ManagedDownloadStorage.StoredEntry(
                    name = "p.mp3.npdl_pending.op.pending",
                    reference = "content://tree/p",
                    mediaUri = "content://tree/p",
                    localFilePath = null,
                    sizeBytes = 1L,
                    lastModifiedMs = 1L
                )
            )
        )
        val songs = listOf(
            downloaded(id = 1L, filePath = " /music/a.mp3 "),
            downloaded(id = 2L, filePath = "  ", mediaUri = null),
            downloaded(id = 3L, filePath = "/stale/p.mp3", mediaUri = "content://tree/p"),
            downloaded(id = 4L, filePath = "/gone.mp3", mediaUri = " /gone.mp3 ")
        )

        assertEquals(
            DownloadedSongReferenceCoverage(knownReferenceCount = 3, missingReferenceCount = 1),
            observeDownloadedSongReferencesFromSnapshot(songs, snapshot)
        )
    }

    @Test
    fun `temporary write targets keep only pending names that belong to the audio name`() {
        assertEquals(
            listOf(
                "Song.mp3.npdl_pending.op-1.pending",
                "Song.mp3",
                "Song.mp3.npmeta.json",
                "Song.mp3.npmeta.pending.json"
            ),
            finalizedTemporaryWriteTargetNames("Song.mp3", " Song.mp3.npdl_pending.op-1.pending ")
        )
        assertEquals(
            listOf("Song.mp3", "Song.mp3.npmeta.json", "Song.mp3.npmeta.pending.json"),
            finalizedTemporaryWriteTargetNames("Song.mp3", "Other.mp3.npdl_pending.op-1.pending")
        )
        assertEquals(
            listOf("Song.mp3", "Song.mp3.npmeta.json", "Song.mp3.npmeta.pending.json"),
            finalizedTemporaryWriteTargetNames("Song.mp3", "Song.mp3")
        )
    }

    @Test
    fun `bounded cancellation cleanup rejects non positive parallelism`() = runTest {
        var invoked = false

        val failure = runCatching {
            runBoundedDownloadCancellationCleanup(listOf(1), parallelism = 0) { invoked = true }
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals("parallelism must be positive", failure?.message)
        assertEquals(false, invoked)
    }

    @Test
    fun `bounded cancellation cleanup visits every item exactly once`() = runTest {
        val visited = mutableListOf<Int>()

        runBoundedDownloadCancellationCleanup(emptyList<Int>(), parallelism = 2) { visited += it }
        assertEquals(emptyList<Int>(), visited)

        runBoundedDownloadCancellationCleanup((1..5).toList(), parallelism = 2) { visited += it }
        assertEquals((1..5).toList(), visited.sorted())
    }

    @Test
    fun `unverified local cover references are dropped while remote ones survive`() = runTest {
        listOf("/old/Covers/a.jpg", "file:///old/Covers/a.jpg", "CONTENT://old/Covers/a.jpg").forEach { reference ->
            assertNull(reference, resolveUnverifiedCover(reference))
        }
        assertEquals("https://example.com/a.jpg", resolveUnverifiedCover(" https://example.com/a.jpg "))
    }

    private suspend fun resolveUnverifiedCover(reference: String): String? {
        return resolveRestorableCoverReference(
            metadata = ManagedDownloadRestorableMetadata(
                sourceStableKey = "1|netease|",
                baseline = ManagedDownloadRestorableMetadata.Baseline(coverReference = reference),
                overrides = ManagedDownloadRestorableMetadata.Overrides(),
                baselineCoverAssetHash = "e".repeat(64)
            ),
            baseline = true,
            fingerprintReference = { null },
            findManagedReferenceByName = { null },
            findContentAddressedReference = { null }
        )
    }

    private fun downloaded(id: Long, filePath: String, mediaUri: String? = null): DownloadedSong {
        return DownloadedSong(
            id = id,
            name = "song-$id",
            artist = "artist",
            album = "album",
            filePath = filePath,
            fileSize = 1L,
            downloadTime = id,
            mediaUri = mediaUri
        )
    }
}
