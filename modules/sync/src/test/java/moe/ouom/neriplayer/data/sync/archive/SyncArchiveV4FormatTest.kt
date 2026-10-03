@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package moe.ouom.neriplayer.data.sync.archive

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Random
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import moe.ouom.neriplayer.data.model.sync.SyncData
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import moe.ouom.neriplayer.data.sync.archive.v4.SyncArchiveV4Format
import moe.ouom.neriplayer.data.sync.archive.v4.SyncArchiveV4Manifest
import moe.ouom.neriplayer.data.sync.archive.v4.SyncArchiveV4Stream
import moe.ouom.neriplayer.data.sync.archive.v4.SyncArchiveV4Objects
import moe.ouom.neriplayer.data.sync.archive.v4.SyncArchiveV4TreeWriter
import moe.ouom.neriplayer.data.sync.archive.v4.SyncArchiveV4TreeReader
import moe.ouom.neriplayer.data.sync.archive.v4.SyncArchiveV4InputStream

class SyncArchiveV4FormatTest {
    @get:Rule val temporary = TemporaryFolder()
    private val empty = SyncArchiveV4Stream(null, 0, 0)
    private val manifest = SyncArchiveV4Manifest(4, 1,
        SyncArchiveManifest(3, SyncData(lastModified = 1), null, 0, 0, 0), empty, empty, empty,
        SyncArchiveCodec.digest(byteArrayOf()), SyncArchiveCodec.digest(byteArrayOf()))

    @Test fun requiredVersionsArePresentAndV3CannotReadV4() {
        val raw = ProtoBuf.encodeToByteArray(manifest)
        assertArrayEquals(byteArrayOf(8, 4, 16, 1), raw.copyOf(4))
        val wire = SyncArchiveV4Format.manifest(manifest)
        assertEquals(manifest, SyncArchiveV4Format.readManifest(wire))
        assertFalse(SyncArchiveCodec.isManifest(wire))
        assertThrows(IllegalArgumentException::class.java) { SyncArchiveCodec.readManifest(wire) }
        assertTrue(runCatching { SyncArchiveV4Format.readManifest(envelope(raw.copyOfRange(4, raw.size))) }.isFailure)
        for (value in listOf(manifest.copy(protocol = 5), manifest.copy(schema = 2),
            manifest.copy(original = manifest.original.copy(protocol = 4)))) {
            assertTrue(runCatching { SyncArchiveV4Format.readManifest(envelope(ProtoBuf.encodeToByteArray(value))) }.isFailure)
        }
    }

    @Test fun webDavPublicationChangesOnlyTheOptionalPublicationIdentity() {
        val original = SyncArchiveV4Format.manifest(manifest)
        val first = SyncArchiveRepository.webDavPublicationContent(original)
        val second = SyncArchiveRepository.webDavPublicationContent(original)
        assertFalse(first.contentEquals(second))
        val decodedFirst = SyncArchiveV4Format.readManifest(first)
        val decodedSecond = SyncArchiveV4Format.readManifest(second)
        assertNotNull(decodedFirst.publicationId)
        assertNotEquals(decodedFirst.publicationId, decodedSecond.publicationId)
        assertEquals(manifest, decodedFirst.copy(publicationId = null))
        assertEquals(manifest, decodedSecond.copy(publicationId = null))
        assertNull(SyncArchiveV4Format.readManifest(original).publicationId)
        assertEquals(4, SyncArchiveRepository.protocolVersion(first))
        assertThrows(IllegalArgumentException::class.java) { SyncArchiveRepository.webDavPublicationContent(byteArrayOf(1, 2)) }
    }

    @Test fun corruptEnvelopeAndImpossibleTotalsAreRejected() {
        val wire = SyncArchiveV4Format.manifest(manifest)
        assertTrue(runCatching { SyncArchiveV4Format.readManifest(wire.copyOf(12)) }.isFailure)
        assertTrue(runCatching { SyncArchiveV4Format.readManifest(wire.clone().also { it[44] = 0 }) }.isFailure)
        assertTrue(runCatching { SyncArchiveV4Format.readManifest(envelope(ProtoBuf.encodeToByteArray(
            manifest.copy(main = empty.copy(rawBytes = -1))))) }.isFailure)
        assertTrue(runCatching { SyncArchiveV4Format.readManifest(envelope(ProtoBuf.encodeToByteArray(
            manifest.copy(pool = empty.copy(chunks = 1, rawBytes = 1))))) }.isFailure)
    }

    @Test fun manifestValidationRejectsInvalidRootsCountsAndDecodedExpansion() {
        val cache = SyncArchiveV4Objects(temporary.newFolder())
        val ref = checkNotNull(cache.store(byteArrayOf(1), false))
        val stream = SyncArchiveV4Stream(ref, 1, 1)
        val invalid = listOf(
            manifest.copy(mainRawHash = "BAD"), manifest.copy(legacyRawHash = "BAD"),
            manifest.copy(main = stream.copy(chunks = 0)),
            manifest.copy(main = stream.copy(chunks = -1)),
            manifest.copy(main = stream.copy(chunks = 2)),
            manifest.copy(main = stream.copy(root = ref.copy(hash = "BAD"))),
            manifest.copy(main = stream.copy(rawBytes = 8L * SyncArchiveV4Format.MAX_RAW_BYTES + 1)),
            manifest.copy(main = stream.copy(rawBytes = Long.MAX_VALUE), legacy = stream),
            manifest.copy(original = manifest.original.copy(rawDataBytes = Long.MAX_VALUE, recordCount = 1,
                chunkCount = 1, root = SyncArchiveRef(ref.hash, ref.rawHash, 1, ref.compressedBytes)))
        )
        for (value in invalid) {
            assertTrue(runCatching { SyncArchiveV4Format.readManifest(envelope(ProtoBuf.encodeToByteArray(value))) }.isFailure)
        }
        assertTrue(SyncArchiveRepository.isManifest(SyncArchiveV4Format.manifest(manifest)))
        assertTrue(SyncArchiveRepository.isManifest("NPSYNC99".toByteArray()))
        assertFalse(SyncArchiveRepository.isManifest("NPSYNC".toByteArray()))
        assertFalse(SyncArchiveRepository.isManifest(ByteArray(8)))
        assertThrows(IllegalArgumentException::class.java) { SyncContentChunker(0) {} }
        assertThrows(IllegalArgumentException::class.java) { SyncContentChunker(SyncArchiveV4Format.MAX_RAW_BYTES + 1) {} }
    }

    @Test fun incompressibleFourMiBIsSplitWithinTheWireBudgetAndRestored() = runBlocking {
        val directory = temporary.newFolder()
        val cache = SyncArchiveV4Objects(directory)
        val raw = ByteArray(SyncArchiveV4Format.MAX_RAW_BYTES).also { Random(17).nextBytes(it) }
        val file = temporary.newFile().also { it.writeBytes(raw) }
        val writer = SyncArchiveV4TreeWriter(cache) {}
        val stream = writer.write(listOf(file))
        assertTrue(stream.chunks > 1)
        assertTrue(writer.objects.values.all { it.compressedBytes <= SyncArchiveLimits.MAX_OBJECT_BYTES })
        val reader = SyncArchiveV4TreeReader(cache, { error("writer cache must be complete") }, false)
        val refs = reader.read(stream)
        SyncArchiveV4InputStream(refs, cache).use { assertArrayEquals(raw, it.readBytes()) }
        val leaf = refs.first()
        val compressed = cache.readCompressed(leaf)
        assertTrue(runCatching { SyncArchiveV4Format.decode(leaf.copy(rawHash = "0".repeat(64)), compressed) }.isFailure)
        assertTrue(runCatching { SyncArchiveV4Format.decode(leaf.copy(rawBytes = leaf.rawBytes - 1), compressed) }.isFailure)
        assertTrue(runCatching { SyncArchiveV4Format.decode(leaf.copy(rawBytes = SyncArchiveV4Format.MAX_RAW_BYTES + 1), compressed) }.isFailure)
    }

    @Test fun remoteVerificationCannotTrustAValidLocalCacheAfterRemoteDeletion() = runBlocking {
        val cache = SyncArchiveV4Objects(temporary.newFolder())
        val ref = checkNotNull(cache.store(byteArrayOf(1, 2), false))
        val stream = SyncArchiveV4Stream(ref, 2, 1)
        val cached = SyncArchiveV4TreeReader(cache, { Result.failure(java.io.IOException("deleted remotely")) }, false)
        assertEquals(listOf(ref), cached.read(stream))
        val verified = SyncArchiveV4TreeReader(cache, { Result.failure(java.io.IOException("deleted remotely")) }, true)
        assertTrue(runCatching { verified.read(stream) }.isFailure)
        assertTrue(verified.paths.isEmpty())
    }

    private fun envelope(raw: ByteArray): ByteArray {
        val compressed = SyncArchiveCodec.compress(raw)
        return ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { output ->
                output.write("NPSYNC04".toByteArray(Charsets.US_ASCII))
                output.writeInt(raw.size)
                output.write(MessageDigest.getInstance("SHA-256").digest(compressed))
                output.write(compressed)
            }
        }.toByteArray()
    }
}
