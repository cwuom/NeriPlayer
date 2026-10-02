@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package moe.ouom.neriplayer.data.sync.archive

import moe.ouom.neriplayer.data.sync.dataset.disk.FileSyncPlaybackDatasetStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import moe.ouom.neriplayer.data.model.sync.*
import moe.ouom.neriplayer.data.sync.dataset.SyncPlaybackKeyOrder
import moe.ouom.neriplayer.data.sync.runtime.dataset.SYNC_PLAYBACK_PAGE_RECORDS
import moe.ouom.neriplayer.data.sync.runtime.dataset.readForTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class SyncArchiveDatasetTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun legacyV3RecordOrderStagesAndExportsStatisticsWithoutChangingOtherSections() = runBlocking {
        val input = SyncData(lastModified = 1,
            playlists = listOf(SyncPlaylist(id = 7, songs = listOf(SyncSong(id = 2, lyricSyncEdited = false)))),
            playbackStats = (1..1800).reversed().map { SyncTrackStat(identityKey = "track-$it", id = it.toLong(), playCount = it) },
            playbackStatBuckets = listOf(SyncPlaybackStatBucket(identityKey = "a", dayStartAt = 2), SyncPlaybackStatBucket(identityKey = "b", dayStartAt = 1)),
            playlistUsageStats = listOf(SyncPlaylistUsageStat(playlistKey = "playlist:7", openCount = 3)))
        val writer = SyncArchiveRepository(temporary.newFolder())
        writer.prepare(input).use { legacy ->
            val objects = legacy.objects.associate { it.path to it.content }
            val reader = SyncArchiveRepository(temporary.newFolder())
            reader.readDataset(legacy.content, reader.playbackDatasets, { it }, { it }, { Result.success(objects.getValue(it)) })
                .getOrThrow().use { dataset ->
                    assertTrue(dataset.data.playbackStats.isEmpty())
                    assertTrue(dataset.data.playbackStatBuckets.isEmpty())
                    val expected = input.copy(
                        playbackStats = input.playbackStats.sortedWith(compareBy(SyncPlaybackKeyOrder, SyncTrackStat::identityKey)),
                        playbackStatBuckets = input.playbackStatBuckets.sortedBy { it.dayStartAt })
                    assertEquals(expected, dataset.readForTest())
                    reader.prepareCancellable(dataset).use { output ->
                        assertEquals(expected, reader.read(output.content) { error("writer cache must contain every object") }.getOrThrow())
                    }
                }
        }
    }

    @Test fun lateMalformedRecordsCannotSealOrLeaveAnyPartialDataset() = runBlocking {
        val valid = ProtoBuf.encodeToByteArray(SyncTrackStat(identityKey = "good"))
        val bucket = ProtoBuf.encodeToByteArray(SyncPlaybackStatBucket(identityKey = "good"))
        val embedded = ProtoBuf.encodeToByteArray(SyncPlaylist(id = 1, songs = listOf(SyncSong(id = 1))))
        val invalidBodies = listOf(
            frames(8 to valid, 17 to byteArrayOf()),
            frames(9 to bucket, 8 to valid),
            frames(8 to valid, 2 to ProtoBuf.encodeToByteArray(SyncSong(id = 1))),
            frames(1 to embedded),
            frames(8 to valid).dropLast(1).toByteArray()
        )
        for (raw in invalidBodies) {
            val (content, objects) = archive(raw, if (raw.contentEquals(invalidBodies[3])) 1L else if (raw.contentEquals(invalidBodies.last())) 1L else 2L)
            val directory = temporary.newFolder()
            val store = FileSyncPlaybackDatasetStore(directory)
            val result = SyncArchiveRepository(temporary.newFolder()).readDataset(content, store, { it }, { it }) { Result.success(objects.getValue(it)) }
            assertTrue(result.isFailure)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun aFinalChunkChecksumFailureAndCancellationCannotReturnAnApprovedDataset() = runBlocking {
        val writer = SyncArchiveRepository(temporary.newFolder())
        val input = SyncData(playbackStats = (0..9000).map { SyncTrackStat(identityKey = "track-$it", name = "name".repeat(60)) })
        writer.prepare(input).use { prepared ->
            val objects = prepared.objects.associate { it.path to it.content }
            val directory = temporary.newFolder()
            val store = FileSyncPlaybackDatasetStore(directory)
            val reader = SyncArchiveRepository(temporary.newFolder())
            val damagedPath = objects.keys.last()
            val damaged = reader.readDataset(prepared.content, store, { it }, { it }) { path ->
                Result.success(objects.getValue(path).let { if (path == damagedPath) it.copyOf(1) else it })
            }
            assertTrue(damaged.isFailure)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            var records = 0
            val cancellation = CancellationException("capture stopped")
            val failure = runCatching {
                reader.readDataset(prepared.content, store, { if (++records == 600) throw cancellation else it }, { it }) { Result.success(objects.getValue(it)) }
            }.exceptionOrNull()
            assertSame(cancellation, failure)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun cachedCompressedObjectsStillRejectLateRawChecksumAndSizeFailuresWithoutApprovingPartialData() = runBlocking {
        for (damage in 0..2) {
            val cacheDirectory = temporary.newFolder()
            val cache = SyncArchiveCache(cacheDirectory)
            val earlyCount = SYNC_PLAYBACK_PAGE_RECORDS + 1
            val earlyRecords = (0 until earlyCount).map {
                8 to ProtoBuf.encodeToByteArray(SyncTrackStat(identityKey = "early-${it.toString().padStart(4, '0')}"))
            }
            val early = cache.store(frames(*earlyRecords.toTypedArray()), index = false)
            val late = cache.store(frames(8 to ProtoBuf.encodeToByteArray(SyncTrackStat(identityKey = "late"))), index = false)
            val damaged = when (damage) {
                0 -> late.copy(rawHash = "0".repeat(64))
                1 -> late.copy(rawBytes = late.rawBytes + 1)
                else -> late.copy(rawBytes = late.rawBytes - 1)
            }
            assertNotNull(cache.cachedCompressed(damaged))
            val root = cache.store(ProtoBuf.encodeToByteArray(SyncArchiveIndex(listOf(early, damaged))), index = true)
            val manifest = SyncArchiveCodec.manifest(SyncArchiveManifest(
                3, SyncData(lastModified = 1), root, earlyCount + 1L,
                early.rawBytes.toLong() + damaged.rawBytes, 2L
            ))
            val staging = temporary.newFolder()
            var sanitized = 0
            var beforeNormalization = 0
            val reader = SyncArchiveRepository(cacheDirectory) { beforeNormalization++ }
            val result = reader.readDataset(manifest, FileSyncPlaybackDatasetStore(staging), { sanitized++; it }, { it }) {
                error("validated compressed cache must contain every object")
            }
            assertTrue(result.isFailure)
            assertEquals(earlyCount, sanitized)
            assertEquals(0, beforeNormalization)
            assertTrue(staging.listFiles().orEmpty().isEmpty())
            assertTrue(reader.lastReferencedPaths.isEmpty())
            assertTrue(reader.read(manifest) { error("validated compressed cache must contain every object") }.isFailure)
            assertEquals(0, beforeNormalization)
            assertTrue(reader.lastReferencedPaths.isEmpty())
        }
    }

    @Test fun aCachedIndexWithAForgedRawHashFailsBeforeReadingAnyChild() = runBlocking {
        val cacheDirectory = temporary.newFolder()
        val cache = SyncArchiveCache(cacheDirectory)
        val raw = frames(8 to ProtoBuf.encodeToByteArray(SyncTrackStat(identityKey = "child")))
        val child = cache.store(raw, index = false)
        val index = cache.store(ProtoBuf.encodeToByteArray(SyncArchiveIndex(listOf(child))), index = true)
        val damaged = index.copy(rawHash = "0".repeat(64))
        assertNotNull(cache.cachedCompressed(damaged))
        assertTrue(java.io.File(cacheDirectory, child.path).delete())
        val manifest = SyncArchiveCodec.manifest(SyncArchiveManifest(3, SyncData(lastModified = 1), damaged, 1, raw.size.toLong(), 1))
        var downloads = 0
        var beforeNormalization = 0
        val reader = SyncArchiveRepository(cacheDirectory) { beforeNormalization++ }
        val staging = temporary.newFolder()
        val result = reader.readDataset(manifest, FileSyncPlaybackDatasetStore(staging), { it }, { it }) {
            downloads++
            error("invalid index must fail before reading its child")
        }
        assertTrue(result.isFailure)
        assertEquals(0, downloads)
        assertEquals(0, beforeNormalization)
        assertTrue(staging.listFiles().orEmpty().isEmpty())
        assertTrue(reader.lastReferencedPaths.isEmpty())
    }

    private fun frames(vararg records: Pair<Int, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output -> for ((kind, payload) in records) { output.writeByte(kind); output.writeInt(payload.size); output.write(payload) } }
    }.toByteArray()

    private fun archive(raw: ByteArray, count: Long): Pair<ByteArray, Map<String, ByteArray>> {
        val cache = SyncArchiveCache(temporary.newFolder())
        val ref = cache.store(raw, index = false)
        return SyncArchiveCodec.manifest(SyncArchiveManifest(3, SyncData(lastModified = 1), ref, count, raw.size.toLong(), 1L)) to
            mapOf(ref.path to cache.readCompressed(ref))
    }
}
