package moe.ouom.neriplayer.core.download.index

import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedLibraryFastIndexMutatorTest {
    @Test
    fun `upsert reads and writes only the target shard`() = runTest {
        val stableKey = "target-song"
        val targetShard = ManagedLibraryFastIndex.shardFor(stableKey)
        val otherKey = keyOutsideShard(targetShard)
        val otherShard = ManagedLibraryFastIndex.shardFor(otherKey)
        val storage = FakeShardStorage().apply {
            put(
                rootIdentity = FILE_ROOT,
                shard = otherShard,
                payload = payload(otherShard, listOf(entry(otherKey)))
            )
        }
        val mutator = ManagedLibraryFastIndexMutator(generatedAtMs = { 42L })

        val result = mutator.upsertCompleteEntry(
            rootIdentity = FILE_ROOT,
            libraryId = LIBRARY_ID,
            entry = entry(stableKey),
            storage = storage
        )

        assertEquals(
            ManagedLibraryFastIndexMutationResult.Updated(targetShard, 1),
            result
        )
        assertEquals(listOf(ShardKey(FILE_ROOT, targetShard)), storage.reads)
        assertEquals(listOf(ShardKey(FILE_ROOT, targetShard)), storage.writes)
        assertEquals(
            listOf(stableKey),
            storage.decodedEntries(FILE_ROOT, targetShard).map { it.stableKey }
        )
        assertEquals(
            listOf(otherKey),
            storage.decodedEntries(FILE_ROOT, otherShard).map { it.stableKey }
        )
    }

    @Test
    fun `remove of a missing stable key leaves the shard untouched`() = runTest {
        val existingKey = "existing-song"
        val shard = ManagedLibraryFastIndex.shardFor(existingKey)
        val missingKey = keyInShard(shard, excluded = setOf(existingKey))
        val originalPayload = payload(shard, listOf(entry(existingKey)))
        val storage = FakeShardStorage().apply {
            put(FILE_ROOT, shard, originalPayload)
        }

        val result = ManagedLibraryFastIndexMutator().remove(
            rootIdentity = FILE_ROOT,
            libraryId = LIBRARY_ID,
            stableKey = missingKey,
            storage = storage
        )

        assertEquals(ManagedLibraryFastIndexMutationResult.Unchanged(shard), result)
        assertTrue(storage.writes.isEmpty())
        assertEquals(originalPayload, storage.payload(FILE_ROOT, shard))
    }

    @Test
    fun `same shard concurrent upserts retain both entries and release their lock`() = runTest {
        val firstKey = "first-song"
        val shard = ManagedLibraryFastIndex.shardFor(firstKey)
        val secondKey = keyInShard(shard, excluded = setOf(firstKey))
        val locks = ManagedLibraryFastIndexMutationLocks()
        val storage = FakeShardStorage().apply {
            beforeRead = { delay(10L) }
            beforeWrite = { delay(10L) }
        }
        val mutator = ManagedLibraryFastIndexMutator(
            generatedAtMs = { 42L },
            mutationLocks = locks
        )

        val results = listOf(firstKey, secondKey).map { stableKey ->
            async {
                mutator.upsertCompleteEntry(
                    rootIdentity = FILE_ROOT,
                    libraryId = LIBRARY_ID,
                    entry = entry(stableKey),
                    storage = storage
                )
            }
        }.awaitAll()

        assertTrue(results.all { result ->
            result is ManagedLibraryFastIndexMutationResult.Updated
        })
        assertEquals(
            setOf(firstKey, secondKey),
            storage.decodedEntries(FILE_ROOT, shard).mapTo(linkedSetOf()) { it.stableKey }
        )
        assertEquals(0, locks.activeLockCount())
    }

    @Test
    fun `same shard in different roots can mutate concurrently without sharing state`() = runTest {
        val stableKey = "root-isolated-song"
        val shard = ManagedLibraryFastIndex.shardFor(stableKey)
        val enteredReads = AtomicInteger()
        val bothReadsEntered = CompletableDeferred<Unit>()
        val storage = FakeShardStorage().apply {
            beforeRead = {
                if (enteredReads.incrementAndGet() == 2) {
                    bothReadsEntered.complete(Unit)
                }
                bothReadsEntered.await()
            }
        }
        val mutator = ManagedLibraryFastIndexMutator(generatedAtMs = { 42L })

        val results = withTimeout(1_000L) {
            listOf(FILE_ROOT, SAF_ROOT).map { rootIdentity ->
                async {
                    mutator.upsertCompleteEntry(
                        rootIdentity = rootIdentity,
                        libraryId = LIBRARY_ID,
                        entry = entry(stableKey).copy(audioReference = "$rootIdentity/audio"),
                        storage = storage
                    )
                }
            }.awaitAll()
        }

        assertTrue(results.all { result ->
            result is ManagedLibraryFastIndexMutationResult.Updated
        })
        assertEquals(
            "$FILE_ROOT/audio",
            storage.decodedEntries(FILE_ROOT, shard).single().audioReference
        )
        assertEquals(
            "$SAF_ROOT/audio",
            storage.decodedEntries(SAF_ROOT, shard).single().audioReference
        )
    }

    @Test
    fun `patch updates selected fields without erasing the complete entry`() = runTest {
        val stableKey = "edited-song"
        val shard = ManagedLibraryFastIndex.shardFor(stableKey)
        val original = entry(stableKey).copy(
            title = "old title",
            artist = "kept artist",
            coverPath = "content://cover/kept"
        )
        val storage = FakeShardStorage().apply {
            put(FILE_ROOT, shard, payload(shard, listOf(original)))
        }

        val result = ManagedLibraryFastIndexMutator(generatedAtMs = { 42L })
            .updateExistingEntry(
                rootIdentity = FILE_ROOT,
                libraryId = LIBRARY_ID,
                stableKey = stableKey,
                storage = storage
            ) { existing ->
                existing.copy(title = "new title", updatedAtMs = 99L)
            }

        assertEquals(ManagedLibraryFastIndexMutationResult.Updated(shard, 1), result)
        assertEquals(
            original.copy(title = "new title", updatedAtMs = 99L),
            storage.decodedEntries(FILE_ROOT, shard).single()
        )
    }

    @Test
    fun `provider read failure does not attempt a shard write`() = runTest {
        val stableKey = "provider-read-failure"
        val shard = ManagedLibraryFastIndex.shardFor(stableKey)
        val failure = IOException("provider read failed")
        val storage = FakeShardStorage().apply {
            readFailures[ShardKey(SAF_ROOT, shard)] = failure
        }

        val result = ManagedLibraryFastIndexMutator().upsertCompleteEntry(
            rootIdentity = SAF_ROOT,
            libraryId = LIBRARY_ID,
            entry = entry(stableKey),
            storage = storage
        )

        assertTrue(result is ManagedLibraryFastIndexMutationResult.Failed)
        assertEquals(failure, (result as ManagedLibraryFastIndexMutationResult.Failed).error)
        assertTrue(storage.writes.isEmpty())
    }

    @Test
    fun `provider write failure keeps the previous shard payload`() = runTest {
        val stableKey = "provider-write-failure"
        val shard = ManagedLibraryFastIndex.shardFor(stableKey)
        val original = entry(stableKey).copy(title = "old title")
        val originalPayload = payload(shard, listOf(original))
        val failure = IOException("provider write failed")
        val storage = FakeShardStorage().apply {
            put(SAF_ROOT, shard, originalPayload)
            writeFailures[ShardKey(SAF_ROOT, shard)] = failure
        }

        val result = ManagedLibraryFastIndexMutator().upsertCompleteEntry(
            rootIdentity = SAF_ROOT,
            libraryId = LIBRARY_ID,
            entry = original.copy(title = "new title"),
            storage = storage
        )

        assertTrue(result is ManagedLibraryFastIndexMutationResult.Failed)
        assertEquals(failure, (result as ManagedLibraryFastIndexMutationResult.Failed).error)
        assertEquals(originalPayload, storage.payload(SAF_ROOT, shard))
    }

    private fun payload(
        shard: String,
        entries: List<ManagedLibraryIndexEntry>
    ): String {
        return ManagedLibraryFastIndex.encode(
            libraryId = LIBRARY_ID,
            shard = shard,
            entries = entries,
            generatedAtMs = 1L
        )
    }

    private fun entry(stableKey: String): ManagedLibraryIndexEntry {
        return ManagedLibraryIndexEntry(
            stableKey = stableKey,
            artifactId = "artifact:$stableKey",
            audioName = "$stableKey.mp3",
            audioReference = "content://audio/$stableKey",
            metadataName = "$stableKey.mp3.npmeta.json",
            state = "CORE_COMMITTED",
            downloadTimeMs = 1L,
            updatedAtMs = 2L,
            songId = 3L,
            title = "title",
            artist = "artist",
            album = "album",
            mediaUri = "content://media/$stableKey",
            channelId = "channel",
            audioId = "audio",
            subAudioId = "sub-audio",
            playlistContextId = "playlist",
            durationMs = 4L,
            coverPath = "content://cover/$stableKey"
        )
    }

    private fun keyInShard(shard: String, excluded: Set<String>): String {
        return generateSequence(0) { value -> value + 1 }
            .map { value -> "same-shard-$value" }
            .first { candidate ->
                candidate !in excluded && ManagedLibraryFastIndex.shardFor(candidate) == shard
            }
    }

    private fun keyOutsideShard(shard: String): String {
        return generateSequence(0) { value -> value + 1 }
            .map { value -> "other-shard-$value" }
            .first { candidate -> ManagedLibraryFastIndex.shardFor(candidate) != shard }
    }

    private data class ShardKey(
        val rootIdentity: String,
        val shard: String
    )

    private class FakeShardStorage : ManagedLibraryFastIndexShardStorage {
        private val payloads = ConcurrentHashMap<ShardKey, String>()
        val reads = Collections.synchronizedList(mutableListOf<ShardKey>())
        val writes = Collections.synchronizedList(mutableListOf<ShardKey>())
        val readFailures = ConcurrentHashMap<ShardKey, Throwable>()
        val writeFailures = ConcurrentHashMap<ShardKey, Throwable>()
        var beforeRead: suspend (ShardKey) -> Unit = {}
        var beforeWrite: suspend (ShardKey) -> Unit = {}

        override suspend fun readShard(
            rootIdentity: String,
            shard: String
        ): ManagedLibraryFastIndexShardReadResult {
            val key = ShardKey(rootIdentity, shard)
            reads += key
            beforeRead(key)
            readFailures[key]?.let { error ->
                return ManagedLibraryFastIndexShardReadResult.Unavailable(error)
            }
            val payload = payloads[key]
                ?: return ManagedLibraryFastIndexShardReadResult.Missing
            return ManagedLibraryFastIndexShardReadResult.Found(payload)
        }

        override suspend fun writeShard(
            rootIdentity: String,
            shard: String,
            payload: String
        ): ManagedLibraryFastIndexShardWriteResult {
            val key = ShardKey(rootIdentity, shard)
            writes += key
            beforeWrite(key)
            writeFailures[key]?.let { error ->
                return ManagedLibraryFastIndexShardWriteResult.Unavailable(error)
            }
            payloads[key] = payload
            return ManagedLibraryFastIndexShardWriteResult.Written
        }

        fun put(rootIdentity: String, shard: String, payload: String) {
            payloads[ShardKey(rootIdentity, shard)] = payload
        }

        fun payload(rootIdentity: String, shard: String): String? {
            return payloads[ShardKey(rootIdentity, shard)]
        }

        fun decodedEntries(
            rootIdentity: String,
            shard: String
        ): List<ManagedLibraryIndexEntry> {
            return payload(rootIdentity, shard)
                ?.let(ManagedLibraryFastIndex::decode)
                ?.entries
                .orEmpty()
        }
    }

    private companion object {
        const val LIBRARY_ID = "library-id"
        const val FILE_ROOT = "file:/downloads"
        const val SAF_ROOT = "tree:content://provider/root"
    }
}
