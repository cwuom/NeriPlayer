package moe.ouom.neriplayer.data.sync.archive

import com.github.luben.zstd.ZstdCompressCtx
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlay
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.sync.archive.compact.SyncArchiveCompactRecords
import moe.ouom.neriplayer.data.sync.archive.v4.SyncArchiveV4Format
import moe.ouom.neriplayer.data.sync.archive.v4.SyncArchiveV4InputStream
import moe.ouom.neriplayer.data.sync.archive.v4.SyncArchiveV4Objects
import moe.ouom.neriplayer.data.sync.archive.v4.SyncArchiveV4Ref
import moe.ouom.neriplayer.data.sync.dataset.disk.FileSyncPlaybackDatasetStore
import moe.ouom.neriplayer.data.sync.merge.song.SyncSongLyricMergePolicy
import moe.ouom.neriplayer.data.sync.runtime.dataset.readForTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncArchiveCompressionCompatibilityTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun representativeCompressionAndDatasetPreparationRemainLossless() = runBlocking {
        val source = representativeData()
        val canonical = SyncSongLyricMergePolicy.prepareLegacy(source)
        measureCompression(canonical)
        val directory = temporary.newFolder()
        val repository = SyncArchiveRepository(directory)
        val captureStarted = System.nanoTime()
        repository.captureLegacyLyrics(source)
        println("SYNC_COMPRESSION_BENCHMARK mode=legacy-capture elapsedNanos=${System.nanoTime() - captureStarted}")
        repository.playbackDatasets.fromLegacy(canonical).use { dataset ->
            val coldStarted = System.nanoTime()
            val cold = repository.prepareCancellable(dataset).use { prepared ->
                val objects = prepared.objects.associate { it.path to it.content }
                report("cold-prepare", coldStarted, prepared, objects)
                assertEquals(objects.keys, prepared.paths)
                prepared.content to objects
            }
            val readerDirectory = temporary.newFolder()
            val reader = SyncArchiveRepository(readerDirectory)
            val staging = temporary.newFolder()
            reader.readDataset(cold.first, FileSyncPlaybackDatasetStore(staging), { it }, { it },
                verifyRemoteObjects = true) { Result.success(cold.second.getValue(it)) }.getOrThrow().use {
                assertEquals(canonical, it.readForTest())
            }
            assertTrue(staging.listFiles().orEmpty().isEmpty())
            val warmStarted = System.nanoTime()
            repository.prepareCancellable(dataset).use { prepared ->
                val objects = prepared.objects.associate { it.path to it.content }
                report("warm-prepare", warmStarted, prepared, objects)
                assertArrayEquals(cold.first, prepared.content)
                assertEquals(cold.second.keys, prepared.paths)
                assertFalse(prepared.objects(cold.second.keys).any())
            }
            assertNoWorkspace(readerDirectory)
        }
        assertNoWorkspace(directory)
    }

    @Test fun level19CacheObjectsRemainReusableBesideNewlyWrittenObjects() {
        val oldRaw = lyricBytes("旧缓存")
        val newRaw = lyricBytes("new content")
        val v3Directory = temporary.newFolder()
        val v3 = SyncArchiveCache(v3Directory)
        val oldV3Compressed = level19(oldRaw, windowLog = 20)
        val oldV3 = v3Ref(oldRaw, oldV3Compressed)
        v3.save(oldV3, oldV3Compressed)
        val v3Pointer = File(v3Directory, "${oldV3.rawHash}-false.ref")
        assertTrue(v3Pointer.setLastModified(42_000L))
        assertEquals(oldV3, v3.store(oldRaw, false))
        assertEquals(42_000L, v3Pointer.lastModified())
        assertArrayEquals(oldV3Compressed, v3.readCompressed(oldV3))
        val newV3 = v3.store(newRaw, false)
        SyncArchiveInputStream(listOf(oldV3, newV3, oldV3), v3).use {
            assertArrayEquals(oldRaw + newRaw + oldRaw, it.readBytes())
        }

        val v4Directory = temporary.newFolder()
        val v4 = SyncArchiveV4Objects(v4Directory)
        val oldV4Compressed = level19(oldRaw, windowLog = 22)
        val oldV4 = v4Ref(oldRaw, oldV4Compressed)
        v4.save(oldV4, oldV4Compressed)
        val v4Pointer = File(v4Directory, "${oldV4.rawHash}-false.v4ref")
        assertTrue(v4Pointer.setLastModified(42_000L))
        assertEquals(oldV4, v4.store(oldRaw, false))
        assertEquals(42_000L, v4Pointer.lastModified())
        assertArrayEquals(oldV4Compressed, v4.readCompressed(oldV4))
        val newV4 = checkNotNull(v4.store(newRaw, false))
        SyncArchiveV4InputStream(listOf(oldV4, newV4, oldV4), v4).use {
            assertArrayEquals(oldRaw + newRaw + oldRaw, it.readBytes())
        }
    }

    @Test fun oldAndCurrentFramesStillRejectChecksumDamageExcessDataAndRawBudgetViolations() {
        val raw = lyricBytes("checksum and budgets")
        for (compressed in listOf(level19(raw, 20), SyncArchiveCodec.compress(raw))) {
            val ref = v3Ref(raw, compressed)
            assertArrayEquals(raw, SyncArchiveCodec.decodeObject(ref, compressed))
            val damaged = damagedChecksum(compressed)
            assertThrows(Exception::class.java) { SyncArchiveCodec.decodeObject(v3Ref(raw, damaged), damaged) }
            assertThrows(IllegalArgumentException::class.java) {
                SyncArchiveCodec.decodeObject(ref.copy(rawBytes = raw.size - 1), compressed)
            }
            assertThrows(IllegalArgumentException::class.java) {
                SyncArchiveCodec.decodeObject(ref.copy(rawBytes = SyncArchiveLimits.MAX_RAW_BYTES + 1), compressed)
            }
            assertThrows(IllegalArgumentException::class.java) {
                SyncArchiveCodec.decodeObject(ref.copy(rawHash = "0".repeat(64)), compressed)
            }
        }
        for (compressed in listOf(level19(raw, 22), SyncArchiveV4Format.compress(raw))) {
            val ref = v4Ref(raw, compressed)
            assertArrayEquals(raw, SyncArchiveV4Format.decode(ref, compressed))
            val damaged = damagedChecksum(compressed)
            assertThrows(Exception::class.java) { SyncArchiveV4Format.decode(v4Ref(raw, damaged), damaged) }
            assertThrows(IllegalArgumentException::class.java) {
                SyncArchiveV4Format.decode(ref.copy(rawBytes = raw.size - 1), compressed)
            }
            assertThrows(IllegalArgumentException::class.java) {
                SyncArchiveV4Format.decode(ref.copy(rawBytes = SyncArchiveV4Format.MAX_RAW_BYTES + 1), compressed)
            }
            assertThrows(IllegalArgumentException::class.java) {
                SyncArchiveV4Format.decode(ref.copy(rawHash = "0".repeat(64)), compressed)
            }
        }
    }

    private fun measureCompression(data: SyncData) {
        val records = ByteArrayOutputStream().also { SyncArchiveRecords.write(data, it) }.toByteArray()
        val chunks = ArrayList<ByteArray>()
        SyncContentChunker { chunks += it }.use { it.write(records) }
        val workspace = temporary.newFolder()
        SyncArchiveCompactRecords.pack(records.inputStream(), ByteArrayInputStream(byteArrayOf()), workspace) {}.use { packed ->
            val parts = (packed.mainParts + packed.legacyParts + packed.poolParts).map(File::readBytes)
            SyncArchiveCodec.compress(chunks.maxBy { it.size })
            SyncArchiveV4Format.compress(parts.maxBy { it.size })
            val v3Samples = List(3) {
                measureChunks(chunks, SyncArchiveCodec::compress) { raw, compressed ->
                    assertArrayEquals(raw, SyncArchiveCodec.decodeObject(v3Ref(raw, compressed), compressed))
                }
            }
            reportCompression("v3-record-chunks", chunks, v3Samples)
            val v4Samples = List(3) {
                measureChunks(parts, SyncArchiveV4Format::compress) { raw, compressed ->
                    assertArrayEquals(raw, SyncArchiveV4Format.decode(v4Ref(raw, compressed), compressed))
                }
            }
            reportCompression("v4-compact-parts", parts, v4Samples)
        }
        assertTrue(workspace.listFiles().orEmpty().isEmpty())
    }

    private fun measureChunks(chunks: List<ByteArray>, compress: (ByteArray) -> ByteArray,
        verify: (ByteArray, ByteArray) -> Unit): CompressionSample {
        var elapsedNanos = 0L
        var compressedBytes = 0L
        for (raw in chunks) {
            val started = System.nanoTime()
            val compressed = compress(raw)
            elapsedNanos += System.nanoTime() - started
            compressedBytes += compressed.size
            verify(raw, compressed)
        }
        return CompressionSample(elapsedNanos, compressedBytes)
    }

    private fun reportCompression(mode: String, chunks: List<ByteArray>, samples: List<CompressionSample>) {
        val compressedBytes = samples.first().compressedBytes
        assertTrue(samples.all { it.compressedBytes == compressedBytes })
        val medianNanos = samples.map { it.elapsedNanos }.sorted()[samples.size / 2]
        val rawBytes = chunks.sumOf { it.size.toLong() }
        println("SYNC_COMPRESSION_BENCHMARK mode=$mode rawBytes=$rawBytes compressedBytes=$compressedBytes objects=${chunks.size} samples=${samples.size} statistic=median elapsedNanos=$medianNanos sampleNanos=${samples.joinToString(",") { it.elapsedNanos.toString() }}")
    }

    private data class CompressionSample(val elapsedNanos: Long, val compressedBytes: Long)

    private fun report(mode: String, started: Long, archive: SyncPreparedArchive, objects: Map<String, ByteArray>) {
        assertEquals(4, SyncArchiveRepository.protocolVersion(archive.content))
        assertTrue(objects.values.all { it.size <= SyncArchiveLimits.MAX_OBJECT_BYTES })
        val manifest = SyncArchiveV4Format.readManifest(archive.content)
        val objectBytes = objects.values.sumOf { it.size.toLong() }
        println("SYNC_COMPRESSION_BENCHMARK mode=$mode elapsedNanos=${System.nanoTime() - started} objects=${objects.size} objectBytes=$objectBytes manifestBytes=${archive.content.size} records=${manifest.original.recordCount} rawBytes=${manifest.original.rawDataBytes}")
    }

    private fun level19(raw: ByteArray, windowLog: Int): ByteArray = ZstdCompressCtx().use {
        it.setLevel(19).setWindowLog(windowLog).setChecksum(true).compress(raw)
    }

    private fun v3Ref(raw: ByteArray, compressed: ByteArray) = SyncArchiveRef(
        SyncArchiveCodec.digest(compressed), SyncArchiveCodec.digest(raw), raw.size, compressed.size)

    private fun v4Ref(raw: ByteArray, compressed: ByteArray) = SyncArchiveV4Ref(
        SyncArchiveCodec.digest(compressed), SyncArchiveCodec.digest(raw), raw.size, compressed.size)

    private fun damagedChecksum(compressed: ByteArray): ByteArray = compressed.copyOf().also {
        it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
    }

    private fun lyricBytes(label: String): ByteArray = (0 until 1024).joinToString("\n") {
        "[00:${it % 60}.000] $label 中文歌词 romaji 行号=$it"
    }.toByteArray()

    private fun assertNoWorkspace(directory: File) {
        assertTrue(directory.listFiles().orEmpty().none { it.isDirectory && it.name.startsWith("sync-stage-") })
    }

    private fun representativeData(): SyncData {
        val songs = (1L..192L).map { id ->
            fun lyric(kind: String) = (0 until 48).joinToString("\n") {
                "[${it / 60}:${it % 60}.000] 歌曲$id $kind 中文歌词 romaji line=$it"
            }
            SyncSong(id = id, name = "歌曲-$id", artist = "artist-${id % 17}", album = "Netease",
                matchedLyric = lyric("matched"), matchedTranslatedLyric = lyric("translated"),
                matchedRomanizedLyric = lyric("romanized"), originalLyric = lyric("baseline"),
                originalTranslatedLyric = lyric("base-translation"), originalRomanizedLyric = lyric("base-romanized"))
        }
        return SyncData(lastModified = 100, deviceId = "synthetic", deviceName = "compression fixture",
            playlists = listOf(SyncPlaylist(id = 1, songs = songs)),
            recentPlays = songs.take(32).mapIndexed { index, song ->
                SyncRecentPlay(songId = song.id, song = song, playedAt = index + 1L, resumePositionMs = 1234)
            },
            playbackStats = (1..768).map {
                SyncTrackStat(identityKey = "track-${it.toString().padStart(4, '0')}", id = it.toLong(),
                    name = "歌曲-$it", playCount = it % 11, totalListenMs = it * 5000L)
            },
            playbackStatBuckets = (1..64).map {
                SyncPlaybackStatBucket(identityKey = "track-0001", dayStartAt = it.toLong(), playCount = it % 7,
                    totalListenMs = it * 1000L)
            })
    }
}
