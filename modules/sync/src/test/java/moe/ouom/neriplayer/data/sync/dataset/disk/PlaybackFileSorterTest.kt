package moe.ouom.neriplayer.data.sync.dataset.disk

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.sync.runtime.dataset.SYNC_PLAYBACK_PAGE_RECORDS
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaybackFileSorterTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun monotonicTracksCrossingRunBoundariesKeepOneLosslessFile() = runBlocking {
        val directory = temporary.newFolder()
        val expected = (0 until 8193).map(::track)
        val filesPastFirstBoundary = ArrayList<File>()
        val file = PlaybackFileSorter(directory, "tracks", trackCodec, false).use { sorter ->
            for ((index, value) in expected.withIndex()) {
                sorter.append(value)
                if (index == 4096) filesPastFirstBoundary.addAll(directory.listFiles().orEmpty())
            }
            sorter.finish()
        }
        assertEquals(expected, read(file, trackCodec))
        assertEquals("Monotonic appends must not create sorting runs or metadata", 1, filesPastFirstBoundary.size)
        assertTrue(filesPastFirstBoundary.none { it.extension == "meta" })
        assertEquals(setOf(file.file.name), directory.listFiles().orEmpty().map(File::getName).toSet())
    }

    @Test fun monotonicDayBucketsCrossingRunBoundariesKeepOneLosslessFile() = runBlocking {
        val directory = temporary.newFolder()
        val expected = listOf(1L, 2L).flatMap { day -> (0 until 4097).map { bucket(it, day) } }
        val filesPastFirstBoundary = ArrayList<File>()
        val file = PlaybackFileSorter(directory, "buckets", bucketCodec, true).use { sorter ->
            for ((index, value) in expected.withIndex()) {
                sorter.append(value)
                if (index == 4096) filesPastFirstBoundary.addAll(directory.listFiles().orEmpty())
            }
            sorter.finish()
        }
        assertEquals(expected, read(file, bucketCodec))
        assertEquals("Monotonic day buckets must not create sorting runs or metadata", 1, filesPastFirstBoundary.size)
        assertTrue(filesPastFirstBoundary.none { it.extension == "meta" })
        assertEquals(setOf(file.file.name), directory.listFiles().orEmpty().map(File::getName).toSet())
    }

    @Test fun lateInversionsPreserveTheWholeWrittenPrefixAndEveryLaterRecord() = runBlocking {
        val directory = temporary.newFolder()
        val prefix = (0 until 8193).map(::track)
        val middle = track(9000).copy(identityKey = key(42) + "-extra", name = "first late inversion")
        val earlier = track(9001).copy(identityKey = key(3) + "-extra", name = "second late inversion")
        val tail = track(9002).copy(identityKey = "zz-tail", name = "after fallback")
        val expected = prefix.take(4) + earlier + prefix.subList(4, 43) + middle + prefix.drop(43) + tail
        val file = PlaybackFileSorter(directory, "tracks", trackCodec, false).use { sorter ->
            for (value in prefix + middle + tail + earlier) sorter.append(value)
            sorter.finish()
        }
        assertEquals(expected, read(file, trackCodec))
    }

    @Test fun repeatedKeysAfterTheWrittenPrefixKeepTheirOriginalPayloadOrder() = runBlocking {
        val directory = temporary.newFolder()
        val prefix = (0 until 4097).map(::track)
        val original = prefix[4095]
        val firstDuplicate = original.copy(name = "first duplicate", totalListenMs = 999, mediaUri = "https://example.test/first")
        val secondDuplicate = original.copy(name = "second duplicate", counterBasePlayCount = 99,
            counterShards = original.counterShards.asReversed())
        val expected = prefix.take(4096) + firstDuplicate + secondDuplicate + prefix.drop(4096)
        val file = PlaybackFileSorter(directory, "tracks", trackCodec, false).use { sorter ->
            for (value in prefix + firstDuplicate + secondDuplicate) sorter.append(value)
            sorter.finish()
        }
        val actual = read(file, trackCodec)
        assertEquals(expected, actual)
        assertEquals(listOf(original, firstDuplicate, secondDuplicate), actual.filter { it.identityKey == original.identityKey })
    }

    @Test fun utf8KeysAndBothBucketOrdersPreserveLongCommonPrefixes() = runBlocking {
        val longPrefix = "common-" + "长".repeat(100)
        val keys = listOf("a", longPrefix, longPrefix + "a", longPrefix + "\uE000", longPrefix + "😀", "\uE000", "😀")
        val tracks = keys.mapIndexed { index, identity -> track(index).copy(identityKey = identity) }
        val trackFile = PlaybackFileSorter(temporary.newFolder(), "tracks", trackCodec, false).use { sorter ->
            for (value in tracks.asReversed()) sorter.append(value)
            sorter.finish()
        }
        assertEquals(tracks, read(trackFile, trackCodec))
        val byDay = listOf(1L, 2L).flatMap { day -> keys.mapIndexed { index, identity -> bucket(index, day).copy(identityKey = identity) } }
        val byIdentity = keys.flatMapIndexed { index, identity -> listOf(1L, 2L).map { day -> bucket(index, day).copy(identityKey = identity) } }
        for ((dayFirst, expected) in listOf(true to byDay, false to byIdentity)) {
            val file = PlaybackFileSorter(temporary.newFolder(), "buckets", bucketCodec, dayFirst).use { sorter ->
                for (value in byDay.asReversed()) sorter.append(value)
                sorter.finish()
            }
            assertEquals(expected, read(file, bucketCodec))
        }
    }

    @Test fun emptyInputHasACompleteVerifiedEofWithoutSortingMetadata() = runBlocking {
        val directory = temporary.newFolder()
        val file = PlaybackFileSorter(directory, "tracks", trackCodec, false).use { it.finish() }
        assertEquals(0L, file.records)
        assertEquals(0L, file.bytes)
        assertTrue(read(file, trackCodec).isEmpty())
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(ByteArray(0)), file.hash)
        assertEquals(setOf(file.file.name), directory.listFiles().orEmpty().map(File::getName).toSet())
    }

    @Test fun cancellationAndSourceCloseReleaseOnlyTheirOwnedDataset() = runBlocking {
        val expected = (0 until 4097).map(::track)
        for (startFallback in listOf(false, true)) {
            val directory = temporary.newFolder()
            val canceled = Job().apply { cancel() }
            val failure = runCatching {
                FileSyncPlaybackDatasetStore(directory).newSink().use { sink ->
                    expected.chunked(SYNC_PLAYBACK_PAGE_RECORDS).forEach { sink.appendTracks(it) }
                    if (startFallback) sink.appendTracks(listOf(track(9000).copy(identityKey = "a")))
                    withContext(canceled) { sink.appendTracks(listOf(track(9001))) }
                }
            }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        }
        val directory = temporary.newFolder()
        val sink = FileSyncPlaybackDatasetStore(directory).newSink()
        val source = try {
            expected.chunked(SYNC_PLAYBACK_PAGE_RECORDS).forEach { sink.appendTracks(it) }
            sink.seal()
        } finally { sink.close() }
        try {
            sink.close()
            source.openTracks().use { cursor ->
                val actual = ArrayList<SyncTrackStat>()
                while (true) {
                    val page = cursor.nextPage()
                    if (page.isEmpty()) break
                    actual.addAll(page)
                }
                assertEquals(expected, actual)
                assertTrue(cursor.nextPage().isEmpty())
            }
            assertEquals(1, directory.listFiles().orEmpty().size)
        } finally {
            source.close()
            source.close()
        }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun sameLengthDamageInTheFinalPayloadStillFailsAtVerifiedEof() = runBlocking {
        val expected = (0 until 10).map(::track).let { it.dropLast(1) + it.last().copy(name = "integrity-name-tail") }
        val file = PlaybackFileSorter(temporary.newFolder(), "tracks", trackCodec, false).use { sorter ->
            for (value in expected) sorter.append(value)
            sorter.finish()
        }
        val marker = "integrity-name-tail".toByteArray(Charsets.UTF_8)
        val bytes = file.file.readBytes()
        val offset = (0..bytes.size - marker.size).first { start -> marker.indices.all { bytes[start + it] == marker[it] } }
        RandomAccessFile(file.file, "rw").use {
            it.seek(offset.toLong() + marker.lastIndex)
            it.writeByte('x'.code)
        }
        assertEquals(file.bytes, file.file.length())
        PlaybackFileReader(file, trackCodec).use { reader ->
            for (value in expected.dropLast(1)) assertEquals(value, checkNotNull(reader.next()).value)
            assertEquals(expected.last().copy(name = "integrity-name-taix"), checkNotNull(reader.next()).value)
            val failure = runCatching { reader.next() }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(checkNotNull(failure).message.orEmpty().contains("checksum mismatch"))
        }
    }

    @Test fun failedPrefixRegistrationRefusesRetriesAndReleasesOnlyCreatedFiles() = runBlocking {
        val directory = temporary.newFolder()
        val unrelated = File(directory, "other-0-0.bin").apply { writeText("another writer owns this") }
        val blocker = File(directory, "tracks-0-0.bin.meta").apply { assertTrue(mkdir()) }
        val retained = File(blocker, "retained.txt").apply { writeText("preexisting metadata blocker") }
        val prefix = File(directory, "tracks-0-0.bin")
        val sorter = PlaybackFileSorter(directory, "tracks", trackCodec, false)
        sorter.use {
            val expected = listOf(track(0), track(1))
            expected.forEach { value -> sorter.append(value) }
            val failure = runCatching { sorter.append(track(2).copy(identityKey = key(0))) }.exceptionOrNull()
            assertTrue(failure is java.io.IOException)
            val writtenPrefix = prefix.readBytes()
            val file = PlaybackFile(prefix, 2, writtenPrefix.size.toLong(), MessageDigest.getInstance("SHA-256").digest(writtenPrefix))
            assertEquals(expected, read(file, trackCodec))
            assertTrue(runCatching { sorter.append(track(3)) }.exceptionOrNull() is IllegalStateException)
            assertTrue(runCatching { sorter.finish() }.exceptionOrNull() is IllegalStateException)
            assertArrayEquals(writtenPrefix, prefix.readBytes())
        }
        sorter.close()
        assertTrue(runCatching { sorter.append(track(4)) }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { sorter.finish() }.exceptionOrNull() is IllegalStateException)
        assertEquals(setOf(unrelated.name, blocker.name), directory.listFiles().orEmpty().map(File::getName).toSet())
        assertTrue(blocker.isDirectory)
        assertEquals("preexisting metadata blocker", retained.readText())
        assertEquals("another writer owns this", unrelated.readText())
    }

    private fun <T> read(file: PlaybackFile, codec: PlaybackRecordCodec<T>): List<T> = PlaybackFileReader(file, codec).use { reader ->
        val records = ArrayList<T>()
        repeat(file.records.toInt()) { records.add(checkNotNull(reader.next()).value) }
        assertNull(reader.next())
        assertNull(reader.next())
        assertEquals(file.bytes, file.file.length())
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(file.file.readBytes()), file.hash)
        records
    }

    private fun key(index: Int) = "key-${index.toString().padStart(6, '0')}"

    private fun track(index: Int) = SyncTrackStat(
        identityKey = key(index), name = "歌曲-$index", artist = "artist-$index", album = "album-$index",
        totalListenMs = 1000L + index, playCount = index + 1, lastPlayedAt = 9000L + index,
        firstPlayedAt = 100L + index, coverUrl = "https://example.test/cover/$index", durationMs = 2000L + index,
        mediaUri = "https://example.test/media/$index", id = index + 11L, albumId = index + 21L,
        counterBaseListenMs = index + 31L, counterBasePlayCount = index + 2,
        counterShards = listOf(
            SyncPlaybackCounterShard("device-a", 100, 200L + index, index + 3, 110, 220),
            SyncPlaybackCounterShard("device-b", 300, 400L + index, index + 4, 330, 440)
        )
    )

    private fun bucket(index: Int, day: Long): SyncPlaybackStatBucket {
        val value = track(index)
        return SyncPlaybackStatBucket(
            dayStartAt = day, identityKey = value.identityKey, name = value.name, artist = value.artist, album = value.album,
            totalListenMs = value.totalListenMs, playCount = value.playCount, lastPlayedAt = value.lastPlayedAt,
            firstPlayedAt = value.firstPlayedAt, coverUrl = value.coverUrl, durationMs = value.durationMs,
            mediaUri = value.mediaUri, id = value.id, albumId = value.albumId,
            counterBaseListenMs = value.counterBaseListenMs, counterBasePlayCount = value.counterBasePlayCount,
            counterShards = value.counterShards
        )
    }
}
