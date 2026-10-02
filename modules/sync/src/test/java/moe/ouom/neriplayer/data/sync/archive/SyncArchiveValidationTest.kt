@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package moe.ouom.neriplayer.data.sync.archive

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncSong
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class SyncArchiveValidationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun manifestRejectsUnsupportedProtocolInconsistentTotalsAndEmbeddedRecords() {
        val empty = SyncArchiveManifest(3, SyncData(lastModified = 1), null, 0, 0, 0)
        assertEquals(empty, SyncArchiveCodec.readManifest(SyncArchiveCodec.manifest(empty)))
        listOf(
            empty.copy(protocol = 4), empty.copy(recordCount = -1), empty.copy(rawDataBytes = -1),
            empty.copy(chunkCount = -1), empty.copy(chunkCount = 1, rawDataBytes = 1),
            empty.copy(recordCount = 1, rawDataBytes = 4), empty.copy(rawDataBytes = 5),
            empty.copy(header = empty.header.copy(playlists = listOf(SyncPlaylist(id = 1))))
        ).forEach { invalid ->
            assertTrue("invalid manifest accepted: $invalid", runCatching {
                SyncArchiveCodec.readManifest(SyncArchiveCodec.manifest(invalid))
            }.isFailure)
        }
        assertFalse(SyncArchiveCodec.isManifest(byteArrayOf(1)))
        assertFalse(SyncArchiveCodec.isManifest(ByteArray(50)))
        assertTrue(runCatching { SyncArchiveCodec.readManifest(byteArrayOf(1)) }.isFailure)
        assertTrue(runCatching { SyncArchiveCodec.readManifest(ByteArray(50)) }.isFailure)
    }

    @Test fun descriptorsAndFramesRejectUnsafeSizesHashesAndExcessDecodedData() {
        val raw = ByteArray(50) { it.toByte() }
        val compressed = SyncArchiveCodec.compress(raw)
        val valid = SyncArchiveRef(SyncArchiveCodec.digest(compressed), SyncArchiveCodec.digest(raw), raw.size, compressed.size, false)
        assertArrayEquals(raw, SyncArchiveCodec.decodeObject(valid, compressed))
        listOf(valid.copy(hash = "../bad"), valid.copy(rawHash = "A".repeat(64)), valid.copy(rawBytes = 0),
            valid.copy(rawBytes = SyncArchiveLimits.MAX_RAW_BYTES + 1), valid.copy(compressedBytes = 0),
            valid.copy(compressedBytes = SyncArchiveLimits.MAX_OBJECT_BYTES + 1)).forEach { ref ->
            assertTrue(runCatching { SyncArchiveCodec.validate(ref) }.isFailure)
        }
        assertTrue(runCatching { SyncArchiveCodec.compress(ByteArray(0)) }.isFailure)
        assertTrue(runCatching { SyncArchiveCodec.decodeObject(valid.copy(hash = "0".repeat(64)), compressed) }.isFailure)
        assertTrue(runCatching { SyncArchiveCodec.decodeObject(valid.copy(rawHash = "0".repeat(64)), compressed) }.isFailure)
        assertTrue(runCatching { SyncArchiveCodec.decompress(compressed, raw.size - 1) }.isFailure)
        assertTrue(runCatching { SyncArchiveCodec.decompress(compressed, raw.size + 1) }.isFailure)
        assertTrue(runCatching { SyncArchiveCodec.decompress(ByteArray(0), raw.size) }.isFailure)
    }

    @Test fun recordFramesRejectTruncationExtraRowsInvalidKindsAndLengthBudgets() {
        val record = frame(8, byteArrayOf(1))
        fun visit(bytes: ByteArray, count: Long = 1, size: Long = bytes.size.toLong()) =
            SyncArchiveRecords.visit(ByteArrayInputStream(bytes), count, size) { _, _ -> }
        visit(record)
        assertTrue(runCatching { visit(record, count = 0) }.isFailure)
        assertTrue(runCatching { visit(frame(17, byteArrayOf(1))) }.isFailure)
        assertTrue(runCatching { visit(record, size = 4) }.isFailure)
        assertTrue(runCatching { visit(frame(8, ByteArray(0), declared = -1)) }.isFailure)
        assertTrue(runCatching { visit(frame(8, ByteArray(0), declared = Int.MAX_VALUE)) }.isFailure)
        assertTrue(runCatching { visit(record, size = 5) }.isFailure)
        assertTrue(runCatching { visit(record.copyOf(record.size - 1), size = 6) }.isFailure)
        assertTrue(runCatching { visit(record, count = 2) }.isFailure)
        assertTrue(runCatching { visit(record, size = 7) }.isFailure)
    }

    @Test fun songOwnershipSectionOrderingAndLyricReferencesAreValidated() {
        val song = SyncSong(id = 1)
        val header = SyncData(lastModified = 1)
        fun read(bytes: ByteArray, records: Long) = SyncArchiveRecords.read(header, ByteArrayInputStream(bytes), records, bytes.size.toLong())
        assertTrue(runCatching { read(frame(2, ProtoBuf.encodeToByteArray(song)), 1) }.isFailure)
        assertTrue(runCatching { read(frame(4, ProtoBuf.encodeToByteArray(song)), 1) }.isFailure)
        assertTrue(runCatching { read(frame(1, ProtoBuf.encodeToByteArray(SyncPlaylist(id = 1, songs = listOf(song)))), 1) }.isFailure)
        val latePlaylist = frame(6, ProtoBuf.encodeToByteArray(moe.ouom.neriplayer.data.model.sync.SyncLogEntry())) +
            frame(1, ProtoBuf.encodeToByteArray(SyncPlaylist(id = 1)))
        assertTrue(runCatching { read(latePlaylist, 2) }.isFailure)
        val edited = song.copy(lyricSyncEdited = true, lyricSyncRevision = 5)
        val input = header.copy(playlists = listOf(SyncPlaylist(id = 1, songs = listOf(edited))))
        assertTrue(runCatching { SyncArchiveLyricProjection.restore(input) }.isFailure)
        assertTrue(runCatching { SyncArchiveLyricProjection.restore(input.copy(lyricOverrides = listOf(edited.copy(lyricSyncRevision = 4)))) }.isFailure)
        assertEquals(5L, SyncArchiveLyricProjection.restore(input.copy(lyricOverrides = listOf(edited.copy(matchedLyric = "edited"))))
            .playlists.single().songs.single().lyricSyncRevision)
    }

    @Test fun treeFanoutDepthAndDataTotalsRejectMalformedCommittedIndexes() = runBlocking {
        val directory = temporary.newFolder()
        val cache = SyncArchiveCache(directory)
        val raw = frame(8, ByteArray(0))
        val leaf = cache.store(raw, false)
        suspend fun rejected(root: SyncArchiveRef, chunks: Long = 1, bytes: Long = 5) {
            val manifest = SyncArchiveManifest(3, SyncData(lastModified = 1), root, 1, bytes, chunks)
            assertTrue(SyncArchiveRepository(directory).read(SyncArchiveCodec.manifest(manifest)) {
                error("cache must be complete")
            }.isFailure)
        }
        rejected(cache.store(byteArrayOf(16, 0), true))
        rejected(cache.store(ProtoBuf.encodeToByteArray(SyncArchiveIndex(List(513) { leaf })), true))
        rejected(leaf, chunks = 2)
        rejected(leaf, bytes = 6)
        rejected(cache.store(ProtoBuf.encodeToByteArray(SyncArchiveIndex(listOf(leaf, leaf))), true))
        var nested = leaf
        repeat(10) { nested = cache.store(ProtoBuf.encodeToByteArray(SyncArchiveIndex(listOf(nested))), true) }
        rejected(nested)
    }

    @Test fun cachedPointersAndStreamBoundariesNeverTrustInvalidLocalState() {
        val notDirectory = temporary.newFile()
        assertTrue(runCatching { SyncArchiveCache(notDirectory) }.isFailure)
        val directory = temporary.newFolder()
        val cache = SyncArchiveCache(directory)
        val first = cache.store(byteArrayOf(1, 2), false)
        val second = cache.store(byteArrayOf(3), false)
        SyncArchiveInputStream(listOf(first, second), cache).use { input ->
            assertEquals(1, input.read())
            val target = ByteArray(4)
            assertEquals(1, input.read(target, 1, 3))
            assertEquals(2, target[1].toInt())
            assertEquals(1, input.read(target, 0, 4))
            assertEquals(3, target[0].toInt())
            assertEquals(-1, input.read(target, 0, 4))
            assertEquals(0, input.read(target, 4, 0))
            assertTrue(runCatching { input.read(target, -1, 1) }.isFailure)
            assertTrue(runCatching { input.read(target, 3, 2) }.isFailure)
        }
        val pointer = directory.listFiles().orEmpty().first { it.name == "${first.rawHash}-false.ref" }
        listOf(first.copy(rawHash = "0".repeat(64)), first.copy(index = true), first.copy(rawBytes = 1)).forEach { wrongRef ->
            pointer.writeBytes(ProtoBuf.encodeToByteArray(wrongRef))
            assertEquals(first, cache.store(byteArrayOf(1, 2), false))
        }
        pointer.writeBytes(ByteArray(1024))
        assertEquals(first, cache.store(byteArrayOf(1, 2), false))
        val chunks = ArrayList<ByteArray>()
        SyncContentChunker(chunks::add).use { chunker ->
            chunker.write(byteArrayOf(9, 1, 2, 8), 1, 2)
            chunker.write(byteArrayOf(3), 0, 0)
            assertTrue(runCatching { chunker.write(ByteArray(1), -1, 1) }.isFailure)
            assertTrue(runCatching { chunker.write(ByteArray(1), 1, 1) }.isFailure)
        }
        assertArrayEquals(byteArrayOf(1, 2), chunks.single())
    }

    private fun frame(kind: Int, payload: ByteArray, declared: Int = payload.size): ByteArray = ByteArrayOutputStream().also { output ->
        DataOutputStream(output).use { it.writeByte(kind); it.writeInt(declared); it.write(payload) }
    }.toByteArray()
}
