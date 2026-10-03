@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package moe.ouom.neriplayer.data.sync.archive

import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.sync.archive.v4.SyncArchiveV4Format
import moe.ouom.neriplayer.data.sync.archive.v4.SyncArchiveV4Bridge
import moe.ouom.neriplayer.data.sync.archive.v4.SyncArchiveV4Index
import moe.ouom.neriplayer.data.sync.archive.v4.SyncArchiveV4InputStream
import moe.ouom.neriplayer.data.sync.archive.v4.SyncArchiveV4Objects
import moe.ouom.neriplayer.data.sync.archive.v4.SyncArchiveV4Ref
import moe.ouom.neriplayer.data.sync.archive.v4.SyncArchiveV4Stream
import moe.ouom.neriplayer.data.sync.archive.v4.SyncArchiveV4TreeReader
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncArchiveV4TreeSafetyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun malformedLocalIndexesCannotBePublishedDuringV3Conversion() {
        for (children in listOf(0, SyncArchiveLimits.INDEX_FANOUT + 1)) {
            val directory = temporary.newFolder()
            val cache = SyncArchiveCache(directory)
            val leaf = cache.store(byteArrayOf(2, 0, 0, 0, 0), false)
            val raw = if (children == 0) byteArrayOf(16, 0) else
                ProtoBuf.encodeToByteArray(SyncArchiveIndex(List(children) { leaf }))
            val root = cache.store(raw, true)
            val content = SyncArchiveCodec.manifest(SyncArchiveManifest(3, SyncData(), root, 1, 5, 1))
            val original = SyncPreparedArchive(content, setOf(root.path, leaf.path), { emptySequence() }, cache::trim)
            val failure = assertThrows(IllegalArgumentException::class.java) {
                SyncArchiveV4Bridge(directory, cache).prepare(original, null, null, emptySet()) {}.close()
            }
            assertEquals("Invalid sync local index fanout", failure.message)
            assertFalse(directory.listFiles().orEmpty().any { it.name.startsWith("sync-stage-") })
        }
    }

    @Test fun malformedTreesCannotEscapeDeclaredBudgets() = runBlocking {
        val cache = SyncArchiveV4Objects(temporary.newFolder())
        val leaf = checkNotNull(cache.store(byteArrayOf(1, 2), false))
        fun index(children: List<SyncArchiveV4Ref>) = checkNotNull(cache.store(
            ProtoBuf.encodeToByteArray(SyncArchiveV4Index(children)), true))
        val emptyIndex = checkNotNull(cache.store(byteArrayOf(16, 0), true))
        var tooDeep = leaf
        repeat(SyncArchiveLimits.MAX_TREE_DEPTH + 1) { tooDeep = index(listOf(tooDeep)) }
        var repeated = leaf
        repeat(SyncArchiveLimits.MAX_TREE_DEPTH - 1) { repeated = index(listOf(repeated)) }
        val cases = listOf(
            SyncArchiveV4Stream(emptyIndex, 2, 1),
            SyncArchiveV4Stream(index(List(SyncArchiveLimits.INDEX_FANOUT + 1) { leaf }), 1026, 513),
            SyncArchiveV4Stream(tooDeep, 2, 1),
            SyncArchiveV4Stream(index(listOf(repeated, leaf)), 2, 1),
            SyncArchiveV4Stream(index(listOf(leaf, leaf)), 2, 1),
            SyncArchiveV4Stream(leaf, 2, 2),
            SyncArchiveV4Stream(leaf, 3, 1),
            SyncArchiveV4Stream(null, 2, 1)
        )
        for (stream in cases) {
            val reader = SyncArchiveV4TreeReader(cache, { error("local fixture missing") }, false)
            assertTrue("invalid tree must fail", runCatching { reader.read(stream) }.isFailure)
        }
        val empty = SyncArchiveV4TreeReader(cache, { error("empty tree cannot fetch") }, false)
        assertTrue(empty.read(SyncArchiveV4Stream(null, 0, 0)).isEmpty())
        assertTrue(empty.paths.isEmpty())
    }

    @Test fun cachePointersAndObjectsAreHintsAndCorruptionIsRepairedBeforeReuse() = runBlocking {
        val directory = temporary.newFolder()
        val cache = SyncArchiveV4Objects(directory)
        val raw = byteArrayOf(1, 2, 3)
        val ref = checkNotNull(cache.store(raw, false))
        val pointer = File(directory, "${ref.rawHash}-false.v4ref")
        val compressed = cache.readCompressed(ref)
        for (damaged in listOf(ref.copy(rawHash = "0".repeat(64)), ref.copy(rawBytes = 4), ref.copy(index = true))) {
            pointer.writeBytes(ProtoBuf.encodeToByteArray(damaged))
            assertEquals(ref, cache.store(raw, false))
        }
        pointer.writeBytes(ByteArray(513))
        assertEquals(ref, cache.store(raw, false))
        File(directory, ref.path).writeBytes(compressed.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
        var fetches = 0
        val reader = SyncArchiveV4TreeReader(cache, { fetches++; Result.success(compressed) }, false)
        assertEquals(listOf(ref), reader.read(SyncArchiveV4Stream(ref, 3, 1)))
        assertEquals(1, fetches)
        assertArrayEquals(raw, cache.readRaw(ref))
        val failure = IOException("synthetic fetch failure")
        val verified = SyncArchiveV4TreeReader(cache, { Result.failure(failure) }, true)
        assertSame(failure, runCatching { verified.read(SyncArchiveV4Stream(ref, 3, 1)) }.exceptionOrNull())
    }

    @Test fun streamRangesAndEmptyReadsFollowTheInputStreamContract() {
        val cache = SyncArchiveV4Objects(temporary.newFolder())
        val first = checkNotNull(cache.store(byteArrayOf(1, 2), false))
        val second = checkNotNull(cache.store(byteArrayOf(3), false))
        val buffer = ByteArray(4)
        SyncArchiveV4InputStream(listOf(first, second), cache).use { input ->
            assertEquals(0, input.read(buffer, 4, 0))
            assertThrows(IndexOutOfBoundsException::class.java) { input.read(buffer, -1, 1) }
            assertThrows(IndexOutOfBoundsException::class.java) { input.read(buffer, 5, 0) }
            assertThrows(IndexOutOfBoundsException::class.java) { input.read(buffer, 0, -1) }
            assertThrows(IndexOutOfBoundsException::class.java) { input.read(buffer, 3, 2) }
            assertEquals(2, input.read(buffer, 1, 3))
            assertEquals(1, buffer[1].toInt())
            assertEquals(2, buffer[2].toInt())
            assertEquals(3, input.read())
            assertEquals(-1, input.read(buffer))
            assertEquals(-1, input.read())
            assertEquals(0, input.read(buffer, 4, 0))
        }
    }

    @Test fun invalidObjectDescriptorsNeverAllocateOrDecodeUnboundedData() {
        val cache = SyncArchiveV4Objects(temporary.newFolder())
        val ref = checkNotNull(cache.store(byteArrayOf(1), false))
        val compressed = cache.readCompressed(ref)
        for (invalid in listOf(ref.copy(hash = "A".repeat(64)), ref.copy(rawHash = "bad"),
            ref.copy(rawBytes = 0), ref.copy(rawBytes = SyncArchiveV4Format.MAX_RAW_BYTES + 1),
            ref.copy(compressedBytes = 0), ref.copy(compressedBytes = SyncArchiveLimits.MAX_OBJECT_BYTES + 1),
            ref.copy(index = true, rawBytes = SyncArchiveLimits.MAX_INDEX_RAW_BYTES + 1))) {
            assertThrows(IllegalArgumentException::class.java) { SyncArchiveV4Format.decode(invalid, compressed) }
        }
        assertThrows(IllegalArgumentException::class.java) { SyncArchiveV4Format.compress(byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { SyncArchiveV4Format.compress(ByteArray(SyncArchiveV4Format.MAX_RAW_BYTES + 1)) }
        assertThrows(IllegalArgumentException::class.java) { SyncArchiveV4Format.decode(ref, compressed.copyOf(compressed.size - 1)) }
        val legacy = SyncArchiveRef(ref.hash, ref.rawHash, SyncArchiveLimits.MAX_INDEX_RAW_BYTES + 1, ref.compressedBytes, true)
        assertThrows(IllegalArgumentException::class.java) { SyncArchiveCodec.decodeObject(legacy, compressed) }
    }

    @Test fun aRepeatedLeafCannotExpandPastTheDeclaredStreamBeforeTheFinalCountCheck() = runBlocking {
        val writer = SyncArchiveV4Objects(temporary.newFolder())
        val leaf = checkNotNull(writer.store(ByteArray(1024 * 1024), false))
        val root = checkNotNull(writer.store(ProtoBuf.encodeToByteArray(SyncArchiveV4Index(List(128) { leaf })), true))
        val readerCache = SyncArchiveV4Objects(temporary.newFolder())
        val requested = mutableListOf<String>()
        val reader = SyncArchiveV4TreeReader(readerCache, { path ->
            requested += path
            Result.success(writer.readCompressed(if (path == root.path) root else leaf))
        }, true)
        assertTrue(runCatching { reader.read(SyncArchiveV4Stream(root, 128, 128)) }.isFailure)
        assertEquals(listOf(root.path), requested)
    }

    @Test fun legacyV3MigrationAlsoRejectsRepeatedLeavesBeforeDownloadingAnOversizedStream() = runBlocking {
        val writer = SyncArchiveCache(temporary.newFolder())
        val leaf = writer.store(ByteArray(1024 * 1024), false)
        val root = writer.store(ProtoBuf.encodeToByteArray(SyncArchiveIndex(List(128) { leaf })), true)
        val content = SyncArchiveCodec.manifest(SyncArchiveManifest(3, SyncData(), root, 1, 128, 128))
        val requested = mutableListOf<String>()
        val reader = SyncArchiveRepository(temporary.newFolder())
        val result = reader.read(content, true) { path ->
            requested += path
            Result.success(writer.readCompressed(if (path == root.path) root else leaf))
        }
        assertTrue(result.isFailure)
        assertEquals(listOf(root.path), requested)
        assertTrue(reader.lastReferencedPaths.isEmpty())
    }
}
