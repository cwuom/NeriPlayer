@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package moe.ouom.neriplayer.data.sync.dataset.disk

import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.sync.runtime.dataset.SYNC_PLAYBACK_PAGE_RECORDS
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream
import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

class PlaybackRecordFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun invalidFrameLengthsAndTruncatedHeadersFailBeforeDecoding() {
        val valid = frame(SyncTrackStat(identityKey = "key", name = "song"))
        val mutations = listOf<Pair<Long, Int>>(
            0L to -1, 0L to valid.size, 12L to -1, 12L to (MAX_PLAYBACK_RECORD_BYTES + 1), 12L to valid.size
        )
        for ((offset, value) in mutations) {
            val file = write(valid)
            RandomAccessFile(file.file, "rw").use { it.seek(offset); it.writeInt(value) }
            assertTrue(PlaybackFileReader(file, trackCodec).use { runCatching { it.next() }.isFailure })
        }
        val truncated = write(valid.copyOf(2))
        assertTrue(PlaybackFileReader(truncated, trackCodec).use { runCatching { it.next() }.isFailure })
    }

    @Test fun mismatchedFrameKeysDaysRecordCountsAndDigestsRejectTheDataset() {
        val valid = frame(SyncTrackStat(identityKey = "key", name = "song"))
        val badKey = write(valid)
        RandomAccessFile(badKey.file, "rw").use { it.seek(RECORD_HEADER_BYTES); it.writeByte('x'.code) }
        assertTrue(PlaybackFileReader(badKey, trackCodec).use { runCatching { it.next() }.isFailure })
        val badDay = write(valid)
        RandomAccessFile(badDay.file, "rw").use { it.seek(4); it.writeLong(1) }
        assertTrue(PlaybackFileReader(badDay, trackCodec).use { runCatching { it.next() }.isFailure })
        val missingRecord = write(valid).copy(records = 2)
        PlaybackFileReader(missingRecord, trackCodec).use {
            assertNotNull(it.next())
            assertTrue(runCatching { it.next() }.isFailure)
        }
        val extraRecord = write(valid + valid)
        PlaybackFileReader(extraRecord, trackCodec).use {
            assertNotNull(it.next())
            assertTrue(runCatching { it.next() }.isFailure)
        }
        val badDigest = write(valid).copy(hash = ByteArray(32))
        PlaybackFileReader(badDigest, trackCodec).use {
            assertNotNull(it.next())
            assertTrue(runCatching { it.next() }.isFailure)
        }
        val file = write(valid)
        assertTrue(runCatching { PlaybackFileReader(file.copy(bytes = file.bytes + 1), trackCodec) }.isFailure)
    }

    @Test fun completeEofRemainsStableAndLongKeyPrefixesKeepTheirDistinctOrder() = runBlocking {
        val key = "same".repeat(100)
        val store = FileSyncPlaybackDatasetStore(temporary.newFolder())
        val records = (0..5000).map { index -> SyncTrackStat(identityKey = key + listOf("", "a", "ab", "b")[index % 4], playCount = index) }
        store.fromLegacy(SyncData(playbackStats = records)).use { dataset ->
            dataset.playback.openTracks().use { cursor ->
                val actual = buildList { while (true) { val page = cursor.nextPage(); if (page.isEmpty()) break; addAll(page) } }
                assertEquals(records.sortedBy { it.identityKey }, actual)
                assertTrue(cursor.nextPage().isEmpty())
            }
        }
    }

    @Test fun damagedRunMetadataCannotBeSealedAndItsLeaseIsReleased() = runBlocking {
        for (damage in 0..3) {
            val directory = temporary.newFolder()
            val store = FileSyncPlaybackDatasetStore(directory)
            store.newSink().use { sink ->
                repeat(4096 / SYNC_PLAYBACK_PAGE_RECORDS) { batch ->
                    sink.appendTracks((0 until SYNC_PLAYBACK_PAGE_RECORDS).map { SyncTrackStat(identityKey = "key-${batch * 256 + it}") })
                }
                val metadata = directory.walkTopDown().single { it.isFile && it.extension == "meta" }
                RandomAccessFile(metadata, "rw").use {
                    when (damage) {
                        0 -> it.writeLong(-1)
                        1 -> { it.seek(8); it.writeLong(-1) }
                        2 -> { it.seek(it.length()); it.writeByte(1) }
                        else -> it.setLength(17)
                    }
                }
                assertTrue(runCatching { sink.seal() }.isFailure)
            }
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun headersLongUtf8KeysAndPayloadsAcross64KiBBoundariesRoundTripAndCopyExactly() {
        for (distance in listOf(1, 3, 7, 12)) {
            val first = paddingRecord(BUFFER_BYTES - distance)
            val key = SyncTrackStat(identityKey = "长".repeat(23_000), name = "key crosses the buffer")
            val payload = SyncTrackStat(identityKey = "tail", name = "x".repeat(BUFFER_BYTES * 2 + 17))
            val values = listOf(first, key, payload)
            val bytes = frame(first) + frame(key) + frame(payload)
            val file = write(bytes, values.size.toLong())
            PlaybackFileReader(file, trackCodec).use { reader ->
                for (value in values) assertEquals(value, checkNotNull(reader.next()).value)
                assertNull(reader.next())
                assertNull(reader.next())
            }
            val copied = PlaybackFileReader(file, trackCodec).use { reader ->
                PlaybackFileWriter(temporary.newFile()).use { writer ->
                    while (true) {
                        val header = reader.nextHeader() ?: break
                        writer.copy(reader, header)
                    }
                    writer.finish()
                }
            }
            assertEquals(file.records, copied.records)
            assertEquals(file.bytes, copied.bytes)
            assertArrayEquals(file.hash, copied.hash)
            assertArrayEquals(bytes, copied.file.readBytes())
        }
    }

    @Test fun aCorruptLengthInAHeaderCrossing64KiBStillFailsBeforeDecoding() {
        val headerOffset = BUFFER_BYTES - 3
        val first = paddingRecord(headerOffset)
        val file = write(frame(first) + frame(SyncTrackStat(identityKey = "tail")), 2)
        RandomAccessFile(file.file, "rw").use {
            it.seek(headerOffset.toLong() + 12)
            it.writeInt(MAX_PLAYBACK_RECORD_BYTES + 1)
        }
        PlaybackFileReader(file, trackCodec).use {
            assertEquals(first, checkNotNull(it.next()).value)
            val failure = runCatching { it.next() }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(checkNotNull(failure).message.orEmpty().contains("decoding budget"))
        }
    }

    @Test fun keyTailAndPayloadDamageBeyond64KiBKeepKeyAndDigestChecksIntact() {
        val key = write(frame(SyncTrackStat(identityKey = "长".repeat(23_000))))
        flipByte(key, RECORD_HEADER_BYTES + BUFFER_BYTES + 13)
        PlaybackFileReader(key, trackCodec).use {
            val failure = runCatching { it.next() }.exceptionOrNull()
            assertTrue(checkNotNull(failure).message.orEmpty().contains("key mismatch"))
        }
        val bytes = frame(SyncTrackStat(identityKey = "key", name = "x".repeat(BUFFER_BYTES * 2 + 17)))
        val payload = write(bytes)
        flipByte(payload, BUFFER_BYTES.toLong() + 7)
        PlaybackFileReader(payload, trackCodec).use {
            assertNotNull(it.next())
            val failure = runCatching { it.next() }.exceptionOrNull()
            assertTrue(checkNotNull(failure).message.orEmpty().contains("checksum mismatch"))
        }
        val truncated = write(bytes.copyOf(BUFFER_BYTES + 10))
        PlaybackFileReader(truncated, trackCodec).use {
            val failure = runCatching { it.next() }.exceptionOrNull()
            assertTrue(checkNotNull(failure).message.orEmpty().contains("Truncated"))
        }
    }

    private fun paddingRecord(frameBytes: Int): SyncTrackStat {
        var nameBytes = frameBytes - 32
        repeat(8) {
            val record = SyncTrackStat(identityKey = "padding", name = "x".repeat(nameBytes))
            val actual = frame(record).size
            if (actual == frameBytes) return record
            nameBytes += frameBytes - actual
        }
        error("Unable to create exact frame boundary fixture")
    }

    private fun flipByte(file: PlaybackFile, offset: Long) = RandomAccessFile(file.file, "rw").use {
        it.seek(offset)
        val byte = it.readUnsignedByte()
        it.seek(offset)
        it.writeByte(byte xor 1)
    }

    private fun frame(value: SyncTrackStat): ByteArray {
        val record = trackCodec.record(value)
        return ByteArrayOutputStream().also { stream ->
            DataOutputStream(stream).use {
                it.writeInt(record.identity.size); it.writeLong(record.day); it.writeInt(record.payload.size)
                it.write(record.identity); it.write(record.payload)
            }
        }.toByteArray()
    }

    private fun write(bytes: ByteArray, records: Long = 1): PlaybackFile {
        val file = temporary.newFile().apply { writeBytes(bytes) }
        return PlaybackFile(file, records, bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes))
    }

    private companion object { const val BUFFER_BYTES = 64 * 1024 }
}
