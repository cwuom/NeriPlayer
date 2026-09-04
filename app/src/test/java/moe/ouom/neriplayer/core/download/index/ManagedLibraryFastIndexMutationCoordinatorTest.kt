package moe.ouom.neriplayer.core.download.index

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import moe.ouom.neriplayer.core.download.DownloadedAudioEmbeddingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedLibraryFastIndexMutationCoordinatorTest {
    @Test
    fun `delta after scan token makes stale full rebuild ineligible`() = runTest {
        val storage = FakeShardStorage()
        val mutator = ManagedLibraryFastIndexMutator(generatedAtMs = { 20L })
        val coordinator = ManagedLibraryFastIndexMutationCoordinator()
        val shard = ManagedLibraryFastIndex.shardFor(BASELINE_KEY)
        val freshKey = keyInShard(shard, BASELINE_KEY)
        storage.writeShard(
            ROOT_IDENTITY,
            shard,
            payload(shard, listOf(entry(BASELINE_KEY)), generatedAtMs = 10L)
        )
        val scanToken = coordinator.capture(ROOT_IDENTITY)

        coordinator.mutate(ROOT_IDENTITY) {
            mutator.upsertCompleteEntry(
                rootIdentity = ROOT_IDENTITY,
                libraryId = LIBRARY_ID,
                entry = entry(freshKey),
                storage = storage
            )
        }
        var rebuildExecuted = false
        val result = coordinator.rebuild(scanToken, ROOT_IDENTITY) {
            rebuildExecuted = true
            storage.writeShard(
                ROOT_IDENTITY,
                shard,
                payload(shard, listOf(entry(BASELINE_KEY)), generatedAtMs = 30L)
            )
        }

        assertEquals(ManagedLibraryFastIndexRebuildResult.Stale, result)
        assertFalse(rebuildExecuted)
        assertEquals(
            setOf(BASELINE_KEY, freshKey),
            storage.entries(ROOT_IDENTITY, shard).mapTo(linkedSetOf()) { it.stableKey }
        )
    }

    @Test
    fun `delta waits for admitted rebuild and is the final shard writer`() = runTest {
        val storage = FakeShardStorage()
        val mutator = ManagedLibraryFastIndexMutator(generatedAtMs = { 20L })
        val coordinator = ManagedLibraryFastIndexMutationCoordinator()
        val shard = ManagedLibraryFastIndex.shardFor(BASELINE_KEY)
        val freshKey = keyInShard(shard, BASELINE_KEY)
        val rebuildEntered = CompletableDeferred<Unit>()
        val releaseRebuild = CompletableDeferred<Unit>()
        val scanToken = coordinator.capture(ROOT_IDENTITY)

        val rebuild = async {
            coordinator.rebuild(scanToken, ROOT_IDENTITY) {
                storage.writeShard(
                    ROOT_IDENTITY,
                    shard,
                    payload(shard, listOf(entry(BASELINE_KEY)), generatedAtMs = 10L)
                )
                rebuildEntered.complete(Unit)
                releaseRebuild.await()
            }
        }
        rebuildEntered.await()
        val mutation = async {
            coordinator.mutate(ROOT_IDENTITY) {
                mutator.upsertCompleteEntry(
                    rootIdentity = ROOT_IDENTITY,
                    libraryId = LIBRARY_ID,
                    entry = entry(freshKey),
                    storage = storage
                )
            }
        }
        yield()

        assertFalse(mutation.isCompleted)
        releaseRebuild.complete(Unit)
        assertTrue(
            rebuild.await() is ManagedLibraryFastIndexRebuildResult.Applied<*>
        )
        assertTrue(mutation.await() is ManagedLibraryFastIndexMutationResult.Updated)
        assertEquals(
            setOf(BASELINE_KEY, freshKey),
            storage.entries(ROOT_IDENTITY, shard).mapTo(linkedSetOf()) { it.stableKey }
        )
    }

    private fun payload(
        shard: String,
        entries: List<ManagedLibraryIndexEntry>,
        generatedAtMs: Long
    ): String {
        return ManagedLibraryFastIndex.encode(
            libraryId = LIBRARY_ID,
            shard = shard,
            entries = entries,
            generatedAtMs = generatedAtMs
        )
    }

    private fun entry(stableKey: String): ManagedLibraryIndexEntry {
        return ManagedLibraryIndexEntry(
            stableKey = stableKey,
            artifactId = "artifact:$stableKey",
            audioName = "$stableKey.flac",
            audioReference = "/downloads/$stableKey.flac",
            metadataName = "$stableKey.flac.npmeta.json",
            state = "FINALIZED",
            metadataEmbeddingState = DownloadedAudioEmbeddingState.EMBEDDED_VERIFIED,
            downloadTimeMs = 1L,
            updatedAtMs = 1L,
            songId = 1L,
            title = stableKey,
            artist = "artist",
            album = "album",
            mediaUri = "file:///downloads/$stableKey.flac",
            channelId = null,
            audioId = null,
            subAudioId = null,
            playlistContextId = null,
            durationMs = 1_000L,
            coverPath = null
        )
    }

    private fun keyInShard(shard: String, excluded: String): String {
        return generateSequence(0) { it + 1 }
            .map { index -> "fresh-$index" }
            .first { candidate ->
                candidate != excluded && ManagedLibraryFastIndex.shardFor(candidate) == shard
            }
    }

    private class FakeShardStorage : ManagedLibraryFastIndexShardStorage {
        private val payloads = ConcurrentHashMap<Pair<String, String>, String>()

        override suspend fun readShard(
            rootIdentity: String,
            shard: String
        ): ManagedLibraryFastIndexShardReadResult {
            return payloads[rootIdentity to shard]
                ?.let(ManagedLibraryFastIndexShardReadResult::Found)
                ?: ManagedLibraryFastIndexShardReadResult.Missing
        }

        override suspend fun writeShard(
            rootIdentity: String,
            shard: String,
            payload: String
        ): ManagedLibraryFastIndexShardWriteResult {
            payloads[rootIdentity to shard] = payload
            return ManagedLibraryFastIndexShardWriteResult.Written
        }

        fun entries(rootIdentity: String, shard: String): List<ManagedLibraryIndexEntry> {
            return payloads[rootIdentity to shard]
                ?.let(ManagedLibraryFastIndex::decode)
                ?.entries
                .orEmpty()
        }
    }

    private companion object {
        const val ROOT_IDENTITY = "file:/downloads"
        const val LIBRARY_ID = "library"
        const val BASELINE_KEY = "baseline"
    }
}
