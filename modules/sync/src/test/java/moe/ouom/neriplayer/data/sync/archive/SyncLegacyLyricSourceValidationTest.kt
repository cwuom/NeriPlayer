@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package moe.ouom.neriplayer.data.sync.archive

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.sync.dataset.disk.FileSyncPlaybackDatasetStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncLegacyLyricSourceValidationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val legacy = SyncSong(id = 7, album = "netease", matchedLyric = "old user text")

    @Test
    fun `valid nested migration indexes restore the preserved candidate into normal data`() = runTest {
        val fixture = archive(listOf(15 to legacy))
        val cache = SyncArchiveCache(temporary.newFolder())
        val inner = cache.store(ProtoBuf.encodeToByteArray(SyncArchiveIndex(listOf(fixture.source.root))), index = true)
        val outer = cache.store(ProtoBuf.encodeToByteArray(SyncArchiveIndex(listOf(inner))), index = true)
        val source = fixture.source.copy(root = outer)
        val objects = fixture.objects + mapOf(inner.path to cache.readCompressed(inner), outer.path to cache.readCompressed(outer))
        val recovery = MemoryRecovery()
        val reader = SyncArchiveRepository(temporary.newFolder(), recovery)
        val received = reader.read(content(source)) { Result.success(objects.getValue(it)) }.getOrThrow()

        assertEquals(listOf(legacy), recovery.data.single().lyricOverrides)
        assertTrue(recovery.isCompleted(source.hash))
        assertEquals(legacy.matchedLyric, received.lyricOverrides.single().matchedLyric)
        assertEquals(true, received.lyricOverrides.single().lyricSyncEdited)
        assertEquals(1L, received.lyricOverrides.single().lyricSyncRevision)
        assertEquals(objects.keys, reader.lastReferencedPaths)
    }

    @Test
    fun `semantically invalid source tails never partially restore or acknowledge valid leading lyrics`() = runTest {
        val invalidTails = listOf(
            1 to legacy,
            15 to legacy.copy(lyricSyncEdited = true, lyricSyncRevision = 20),
            15 to legacy.copy(lyricSyncEdited = false),
            15 to legacy.copy(matchedLyric = null),
            15 to legacy.copy(lyricSyncRevision = 20)
        )
        for (tail in invalidTails) {
            val fixture = archive(listOf(15 to legacy, tail))
            val recovery = MemoryRecovery()
            val reader = SyncArchiveRepository(temporary.newFolder(), recovery)
            val staging = temporary.newFolder()
            val result = reader.readDataset(content(fixture.source), FileSyncPlaybackDatasetStore(staging), { it }, { it }) {
                Result.success(fixture.objects.getValue(it))
            }

            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
            assertTrue(recovery.data.isEmpty())
            assertFalse(recovery.isCompleted(fixture.source.hash))
            assertTrue(staging.listFiles().orEmpty().isEmpty())
        }
    }

    private fun archive(records: List<Pair<Int, SyncSong>>): Fixture {
        val raw = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                for ((kind, song) in records) {
                    val payload = ProtoBuf.encodeToByteArray(song)
                    output.writeByte(kind)
                    output.writeInt(payload.size)
                    output.write(payload)
                }
            }
        }.toByteArray()
        val cache = SyncArchiveCache(temporary.newFolder())
        val ref = cache.store(raw, index = false)
        return Fixture(SyncLegacyLyricSource(ref, records.size.toLong(), raw.size.toLong(), 1),
            mapOf(ref.path to cache.readCompressed(ref)))
    }

    private fun content(source: SyncLegacyLyricSource): ByteArray =
        SyncArchiveCodec.manifest(SyncArchiveManifest(3, SyncData(), null, 0, 0, 0, source))

    private data class Fixture(val source: SyncLegacyLyricSource, val objects: Map<String, ByteArray>)

    private class MemoryRecovery : SyncLegacyLyricRecovery {
        private val completed = mutableSetOf<String>()
        val data = mutableListOf<SyncData>()
        override fun isCompleted(sourceHash: String): Boolean = sourceHash in completed
        override fun recover(sourceHash: String, data: SyncData) {
            this.data += data
            completed += sourceHash
        }
    }
}
