@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package moe.ouom.neriplayer.data.sync.archive

import java.io.File
import java.io.IOException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlayDeletion
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlay
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistUsageStat
import moe.ouom.neriplayer.data.sync.archive.budget.SyncArchiveMetadataLimits
import moe.ouom.neriplayer.data.sync.archive.budget.SyncArchiveMetadataBudget
import moe.ouom.neriplayer.data.sync.dataset.disk.FileSyncPlaybackDatasetStore
import moe.ouom.neriplayer.data.sync.runtime.dataset.readForTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncArchiveMetadataBudgetTest {
    @get:Rule val temporary = TemporaryFolder()

    private val song = SyncSong(id = 1L, name = "完整歌曲", lyricSyncEdited = false)
    private val playlist = SyncPlaylist(id = 1L, songs = listOf(song))
    private val input = SyncData(lastModified = 1L, playlists = listOf(playlist))

    @Test fun v3AndV4RejectAggregateMetadataBytesWithoutApprovingOrRetainingAStagedDataset() = runBlocking {
        val bytes = ProtoBuf.encodeToByteArray(playlist.copy(songs = emptyList())).size + ProtoBuf.encodeToByteArray(song).size
        for (protocol in listOf(3, 4)) {
            fixture(input, protocol).use { archive ->
                val reader = repository(SyncArchiveMetadataLimits(maxPayloadBytes = bytes - 1L))
                val staging = temporary.newFolder()
                val result = reader.readDataset(archive.content, FileSyncPlaybackDatasetStore(staging), { it }, { it }, archive::fetch)
                result.getOrNull()?.close()
                assertTrue("protocol $protocol approved metadata beyond its byte budget", result.isFailure)
                assertTrue(result.exceptionOrNull() is IOException)
                assertTrue(reader.lastReferencedPaths.isEmpty())
                assertTrue(staging.listFiles().orEmpty().isEmpty())
                assertNoWorkspace(readerDirectory(reader))
            }
        }
    }

    @Test fun v3AndV4KeepTheExactMetadataByteAndObjectBoundary() = runBlocking {
        val bytes = ProtoBuf.encodeToByteArray(playlist.copy(songs = emptyList())).size + ProtoBuf.encodeToByteArray(song).size
        for (protocol in listOf(3, 4)) {
            fixture(input, protocol).use { archive ->
                val reader = repository(SyncArchiveMetadataLimits(maxPayloadBytes = bytes.toLong(), maxObjects = 2))
                reader.readDataset(archive.content, reader.playbackDatasets, { it }, { it }, archive::fetch).getOrThrow().use {
                    assertEquals(input, it.readForTest())
                    reader.prepareCancellable(it).use { prepared ->
                        assertEquals(4, SyncArchiveRepository.protocolVersion(prepared.content))
                    }
                }
            }
        }
    }

    @Test fun materializedReadsAlsoChargeStatisticsThatRemainInLists() = runBlocking {
        val data = SyncData(lastModified = 1, playbackStats = (1L..3L).map { SyncTrackStat(identityKey = "track-$it", id = it) })
        for (protocol in listOf(3, 4)) {
            fixture(data, protocol).use { archive ->
                val reader = repository(SyncArchiveMetadataLimits(maxObjects = 2))
                val result = reader.read(archive.content, archive::fetch)
                assertTrue("materialized statistics bypassed the retained-object budget", result.isFailure)
                assertTrue(result.exceptionOrNull() is IOException)
            }
        }
    }

    @Test fun pagedStatisticsDoNotConsumeTheMetadataAggregateBudget() = runBlocking {
        val data = SyncData(lastModified = 1, playbackStats = (1L..700L).map { SyncTrackStat(identityKey = "track-$it", id = it) })
        for (protocol in listOf(3, 4)) {
            fixture(data, protocol).use { archive ->
                val reader = repository(SyncArchiveMetadataLimits(maxObjects = 1))
                reader.readDataset(archive.content, reader.playbackDatasets, { it }, { it }, archive::fetch).getOrThrow().use { dataset ->
                    assertEquals(700, dataset.readForTest().playbackStats.size)
                    reader.prepareCancellable(dataset).use { output ->
                        val recovered = SyncArchiveRepository(temporary.newFolder()).readDataset(output.content,
                            FileSyncPlaybackDatasetStore(temporary.newFolder()), { it }, { it }) { path ->
                            Result.success(output.objects.first { it.path == path }.content)
                        }.getOrThrow()
                        recovered.use { assertEquals(data.playbackStats.toSet(), it.readForTest().playbackStats.toSet()) }
                    }
                }
            }
        }
    }

    @Test fun aPagedStatisticsRecordWithDenseNestedShardsStillHasItsOwnBudget() = runBlocking {
        val oversized = SyncTrackStat(identityKey = "dense", counterShards = List(3) { SyncPlaybackCounterShard() })
        val data = SyncData(lastModified = 1,
            playbackStats = (1L..300L).map { SyncTrackStat(identityKey = "prefix-$it", id = it) } + oversized)
        for (protocol in listOf(3, 4)) {
            fixture(data, protocol).use { archive ->
                val reader = repository(SyncArchiveMetadataLimits(maxObjects = 3))
                val staging = temporary.newFolder()
                val result = reader.readDataset(archive.content, FileSyncPlaybackDatasetStore(staging), { it }, { it }, archive::fetch)
                result.getOrNull()?.close()
                assertTrue("dense shards were decoded beyond the independent row budget", result.isFailure)
                assertTrue(result.exceptionOrNull() is IOException)
                assertTrue(staging.listFiles().orEmpty().isEmpty())
                assertTrue(reader.lastReferencedPaths.isEmpty())
            }
        }
    }

    @Test fun retainedMetadataCountsItsNestedShardsBeforeDecodingThem() = runBlocking {
        val data = SyncData(lastModified = 1, playlistUsageStats = listOf(
            SyncPlaylistUsageStat(playlistKey = "playlist:1", counterShards = List(3) { SyncPlaybackCounterShard() })
        ))
        for (protocol in listOf(3, 4)) {
            fixture(data, protocol).use { archive ->
                val reader = repository(SyncArchiveMetadataLimits(maxObjects = 3))
                val result = reader.readDataset(archive.content, reader.playbackDatasets, { it }, { it }, archive::fetch)
                result.getOrNull()?.close()
                assertTrue("nested retained objects bypassed the metadata budget", result.isFailure)
                assertTrue(result.exceptionOrNull() is IOException)
            }
        }
    }

    @Test fun pagedStatisticsWriterRefusesAnOversizedSingleRow() = runBlocking {
        val data = SyncData(lastModified = 1, playbackStats = listOf(
            SyncTrackStat(identityKey = "dense", counterShards = List(3) { SyncPlaybackCounterShard() })
        ))
        val writer = repository(SyncArchiveMetadataLimits(maxObjects = 3))
        FileSyncPlaybackDatasetStore(temporary.newFolder()).fromLegacy(data).use { dataset ->
            val failure = runCatching { writer.prepareCancellable(dataset).close() }.exceptionOrNull()
            assertTrue("paged writer published an oversized single row", failure is IOException)
        }
        assertTrue(writer.lastReferencedPaths.isEmpty())
        assertNoWorkspace(readerDirectory(writer))
    }

    @Test fun syncDataAndDatasetWritersRefuseAnArchiveTheirReaderCannotRetain() = runBlocking {
        val limits = SyncArchiveMetadataLimits(maxObjects = 1)
        val writer = repository(limits)
        assertThrows(IOException::class.java) { writer.prepare(input).close() }
        val datasetStore = FileSyncPlaybackDatasetStore(temporary.newFolder())
        datasetStore.fromLegacy(input).use { dataset ->
            val error = runCatching { writer.prepareCancellable(dataset).close() }.exceptionOrNull()
            assertTrue("dataset writer published metadata beyond its reader budget", error is IOException)
        }
        assertTrue(writer.lastReferencedPaths.isEmpty())
        assertNoWorkspace(readerDirectory(writer))
    }

    @Test fun aLargeMetadataPayloadIsRejectedByTheWriterBeforePublishingAnyManifest() {
        val writer = repository(SyncArchiveMetadataLimits(maxPayloadBytes = 16))
        val oversized = input.copy(recentPlayDeletions = listOf(SyncRecentPlayDeletion(songId = 2, album = "保留全文".repeat(50))))
        assertThrows(IOException::class.java) { writer.prepare(oversized).close() }
        assertTrue(writer.lastReferencedPaths.isEmpty())
        assertNoWorkspace(readerDirectory(writer))
    }

    @Test fun mainMetadataAndLegacyCandidatesShareOneRetainedObjectBudget() = runBlocking {
        val legacy = SyncSong(id = 9, matchedLyric = "原始全文", originalRomanizedLyric = "", lyricSyncEdited = null)
        for (protocol in listOf(3, 4)) {
            fixture(input, protocol, legacy).use { archive ->
                val reader = repository(SyncArchiveMetadataLimits(maxObjects = 2))
                val result = reader.readDataset(archive.content, reader.playbackDatasets, { it }, { it }, archive::fetch)
                result.getOrNull()?.close()
                assertTrue("legacy candidates did not share the main metadata budget", result.isFailure)
                assertTrue(result.exceptionOrNull() is IOException)
                assertTrue(reader.lastReferencedPaths.isEmpty())
            }
        }
    }

    @Test fun completedLegacyRecoveryDoesNotBypassTheRetainedBudget() = runBlocking {
        val legacy = SyncSong(id = 9, matchedLyric = "原始全文", originalRomanizedLyric = "", lyricSyncEdited = null)
        fixture(input, 4, legacy).use { archive ->
            val recovery = object : SyncLegacyLyricRecovery {
                override fun isCompleted(sourceHash: String) = true
                override fun recover(sourceHash: String, data: SyncData) = error("completed recovery must remain completed")
                override fun preservedLyrics() = listOf(legacy)
            }
            val reader = repository(SyncArchiveMetadataLimits(maxObjects = 2), recovery)
            val result = reader.readDataset(archive.content, reader.playbackDatasets, { it }, { it }, archive::fetch)
            result.getOrNull()?.close()
            assertTrue("completed legacy recovery returned unchecked retained candidates", result.isFailure)
            assertTrue(result.exceptionOrNull() is IOException)
        }
    }

    @Test fun aCapturedLegacySourceAndNewMetadataShareThePublicationBudget() {
        val writer = repository(SyncArchiveMetadataLimits(maxObjects = 2))
        writer.captureLegacyLyrics(SyncData(lyricOverrides = listOf(SyncSong(id = 9, matchedLyric = "原始全文"))))
        assertThrows(IOException::class.java) { writer.prepare(input).close() }
        assertTrue(writer.lastReferencedPaths.isEmpty())
        assertNoWorkspace(readerDirectory(writer))
    }

    @Test fun legacyCaptureRejectsCapacityWithoutDroppingAnyCandidateSilently() {
        val writer = repository(SyncArchiveMetadataLimits(maxObjects = 1))
        val candidates = listOf(SyncSong(id = 1, matchedLyric = "一"), SyncSong(id = 2, matchedLyric = "二"))
        assertThrows(IOException::class.java) { writer.captureLegacyLyrics(SyncData(lyricOverrides = candidates)) }
        assertTrue(writer.lastReferencedPaths.isEmpty())
    }

    @Test fun firstAndCompletedRecoveryKeepTheSameExactCapacityForTheSameCandidate() = runBlocking {
        val legacy = SyncSong(id = 9, matchedLyric = "原始全文", originalRomanizedLyric = "", lyricSyncEdited = null)
        for (protocol in listOf(3, 4)) {
            fixture(input, protocol, legacy).use { archive ->
                val recovery = MemoryRecovery()
                val bytes = ProtoBuf.encodeToByteArray(playlist.copy(songs = emptyList())).size +
                    ProtoBuf.encodeToByteArray(song).size + ProtoBuf.encodeToByteArray(legacy).size
                val reader = repository(SyncArchiveMetadataLimits(maxPayloadBytes = bytes.toLong(), maxObjects = 3), recovery)
                repeat(2) {
                    reader.readDataset(archive.content, reader.playbackDatasets, { it }, { it }, archive::fetch)
                        .getOrThrow().use { dataset ->
                            assertTrue(dataset.data.lyricOverrides.any { it.id == legacy.id && it.matchedLyric == legacy.matchedLyric })
                        }
                }
                assertEquals(1, recovery.recoveries)
                assertEquals(listOf(legacy), recovery.preservedLyrics())
            }
        }
    }

    @Test fun differentPreservedVariantsAreCheckedBeforeRecoveryIsCommitted() = runBlocking {
        val legacy = SyncSong(id = 9, matchedLyric = "源中的全文")
        val earlier = legacy.copy(matchedLyric = "另一份全文")
        fixture(input, 4, legacy).use { archive ->
            val recovery = MemoryRecovery(listOf(earlier))
            val reader = repository(SyncArchiveMetadataLimits(maxObjects = 3), recovery)
            val failure = reader.readDataset(archive.content, reader.playbackDatasets, { it }, { it }, archive::fetch)
            failure.getOrNull()?.close()
            assertTrue(failure.exceptionOrNull() is IOException)
            assertEquals(0, recovery.recoveries)
            assertEquals(listOf(earlier), recovery.preservedLyrics())
        }
    }

    @Test fun omittedDefaultRecentSongStillConsumesItsDecodedObjectOnBothSides() {
        val recent = SyncRecentPlay()
        val raw = ProtoBuf.encodeToByteArray(recent)
        val exact = SyncArchiveMetadataBudget(SyncArchiveMetadataLimits(maxObjects = 2))
        assertTrue(exact.encode(SyncRecentPlay.serializer(), recent) {}.contentEquals(raw))
        assertThrows(IOException::class.java) {
            SyncArchiveMetadataBudget(SyncArchiveMetadataLimits(maxObjects = 1)).encode(SyncRecentPlay.serializer(), recent) {}
        }
        val framed = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { data -> data.writeByte(5); data.writeInt(raw.size); data.write(raw) }
        }.toByteArray()
        val restored = SyncArchiveRecords.read(SyncData(lastModified = 1), ByteArrayInputStream(framed), 1, framed.size.toLong(),
            metadataBudget = SyncArchiveMetadataBudget(SyncArchiveMetadataLimits(maxObjects = 2)))
        assertEquals(1, restored.recentPlays.size)
        assertThrows(IOException::class.java) {
            SyncArchiveRecords.read(SyncData(lastModified = 1), ByteArrayInputStream(framed), 1, framed.size.toLong(),
                metadataBudget = SyncArchiveMetadataBudget(SyncArchiveMetadataLimits(maxObjects = 1)))
        }
    }

    @Test fun writerRetainsExactUtf8AndProtoBufByteBoundariesIncludingSurrogates() {
        for (name in listOf("汉字", "😀\uD800\uDC00", "x\uD800x", "\uDC00\uD800")) {
            val value = SyncSong(name = name)
            val raw = ProtoBuf.encodeToByteArray(value)
            val exact = SyncArchiveMetadataBudget(SyncArchiveMetadataLimits(maxPayloadBytes = raw.size.toLong()))
            assertTrue(exact.encode(SyncSong.serializer(), value) {}.contentEquals(raw))
            assertThrows(IOException::class.java) {
                SyncArchiveMetadataBudget(SyncArchiveMetadataLimits(maxPayloadBytes = raw.size - 1L))
                    .encode(SyncSong.serializer(), value) {}
            }
        }
    }

    @Test fun nestedEmptyShardObjectsKeepTheirExactWriterAndReaderBoundary() {
        val value = SyncTrackStat(counterShards = List(3) { SyncPlaybackCounterShard() })
        val raw = SyncArchiveMetadataBudget(SyncArchiveMetadataLimits(maxObjects = 4)).encode(SyncTrackStat.serializer(), value) {}
        val reader = SyncArchiveMetadataBudget(SyncArchiveMetadataLimits(maxObjects = 4))
        reader.beginRecord(raw.size)
        reader.inspect(8, raw) {}
        assertThrows(IOException::class.java) {
            SyncArchiveMetadataBudget(SyncArchiveMetadataLimits(maxObjects = 3)).encode(SyncTrackStat.serializer(), value) {}
        }
    }

    private class MemoryRecovery(candidates: List<SyncSong> = emptyList()) : SyncLegacyLyricRecovery {
        private val completed = HashSet<String>()
        private var retained = candidates
        var recoveries = 0
            private set
        override fun isCompleted(sourceHash: String): Boolean = sourceHash in completed
        override fun recover(sourceHash: String, data: SyncData) {
            retained = (retained + data.lyricOverrides).distinct()
            completed += sourceHash
            recoveries++
        }
        override fun preservedLyrics(): List<SyncSong> = retained
    }

    private val directories = java.util.IdentityHashMap<SyncArchiveRepository, File>()

    private fun repository(limits: SyncArchiveMetadataLimits, recovery: SyncLegacyLyricRecovery? = null): SyncArchiveRepository {
        val directory = temporary.newFolder()
        return SyncArchiveRepository(directory, limits, recovery).also { directories[it] = directory }
    }

    private fun readerDirectory(repository: SyncArchiveRepository): File = directories.getValue(repository)

    private fun assertNoWorkspace(directory: File) {
        assertFalse(directory.listFiles().orEmpty().any { it.isDirectory && it.name.startsWith("sync-stage-") })
    }

    private fun fixture(data: SyncData, protocol: Int, legacy: SyncSong? = null): Fixture {
        val writer = SyncArchiveRepository(temporary.newFolder())
        if (legacy != null) writer.captureLegacyLyrics(SyncData(lyricOverrides = listOf(legacy)))
        val archive = if (protocol == 3) writer.prepareOriginal(data) else writer.prepare(data)
        return Fixture(archive, archive.objects.associate { it.path to it.content })
    }

    private class Fixture(val archive: SyncPreparedArchive, private val objects: Map<String, ByteArray>) : java.io.Closeable {
        val content get() = archive.content
        fun fetch(path: String) = Result.success(objects.getValue(path))
        override fun close() = archive.close()
    }
}
