package moe.ouom.neriplayer.data.sync.dataset.disk

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.sync.dataset.SyncPlaybackKeyOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaybackValidatedReindexTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `validated identity reindex never serializes again across record and byte sorting budgets`() = runBlocking {
        val inputs = listOf(
            List(5_000) { bucket(it % 2_500, it / 2_500 + 1L) },
            List(600) { bucket(it % 300, it / 300 + 1L).copy(name = "x".repeat(8_192)) }
        )
        for (values in inputs) {
            val serializer = SerializerProbe(SyncPlaybackStatBucket.serializer())
            val codec = codec(serializer)
            val input = write(values.map(codec::record))
            assertEquals(values.size, serializer.encodeAttempts)
            serializer.encodingAllowed = false

            val actual = reindex(input, codec, temporary.newFolder())
            assertEquals(values.size, serializer.encodeAttempts)
            assertEquals(values.size, serializer.decodes)
            val ordered = identityOrder(values)
            assertEquals(ordered, read(actual, codec))
            val expected = write(ordered.map(bucketCodec::record))
            assertEquals(expected.records, actual.records)
            assertEquals(expected.bytes, actual.bytes)
            assertArrayEquals(expected.hash, actual.hash)
            assertArrayEquals(expected.file.readBytes(), actual.file.readBytes())
        }
    }

    @Test
    fun `validated reindex preserves original protobuf payload bytes while changing only record order`() = runBlocking {
        val values = listOf(bucket(2, 1), bucket(1, 2), bucket(1, 1),
            bucket(3, 1).copy(identityKey = "unicode-😀"), bucket(3, 2).copy(identityKey = "unicode-\uE000"))
        val serializer = SerializerProbe(SyncPlaybackStatBucket.serializer())
        val codec = codec(serializer)
        val records = values.mapIndexed { index, value ->
            val record = codec.record(value)
            // 合法未知字段在解码后没有 domain 属性，重编码会丢掉这些输入字节
            record.copy(payload = record.payload + byteArrayOf(0xA0.toByte(), 0x06, (20 + index).toByte()))
        }
        val input = write(records)
        assertEquals(values.size, serializer.encodeAttempts)
        val actual = reindex(input, codec, temporary.newFolder())
        val expected = write(records.sortedWith { left, right ->
            SyncPlaybackKeyOrder.compare(left.value.identityKey, right.value.identityKey)
                .takeIf { it != 0 } ?: left.day.compareTo(right.day)
        })

        assertEquals(identityOrder(values), read(actual, codec))
        assertEquals(expected.records, actual.records)
        assertEquals(expected.bytes, actual.bytes)
        assertArrayEquals(expected.hash, actual.hash)
        assertArrayEquals(expected.file.readBytes(), actual.file.readBytes())
        assertEquals(values.size, serializer.encodeAttempts)
    }

    @Test
    fun `reindex rejects key and day mismatch even when damaged input has a matching SHA`() = runBlocking {
        for (keyDamage in listOf(true, false)) {
            val serializer = SerializerProbe(SyncPlaybackStatBucket.serializer())
            val codec = codec(serializer)
            val original = write(listOf(codec.record(bucket(1, 1))))
            RandomAccessFile(original.file, "rw").use {
                if (keyDamage) { it.seek(RECORD_HEADER_BYTES); it.writeByte('x'.code) }
                else { it.seek(4); it.writeLong(2) }
            }
            val damaged = original.copy(hash = digest(original.file))
            val output = temporary.newFolder()
            val failure = runCatching { reindex(damaged, codec, output) }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertTrue(checkNotNull(failure).message.orEmpty().contains(if (keyDamage) "key mismatch" else "day mismatch"))
            assertEquals(1, serializer.decodes)
            assertTrue(output.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun `reindex drains validated input to EOF before accepting counts suffix and SHA`() = runBlocking {
        for (damage in 0..2) {
            val serializer = SerializerProbe(SyncPlaybackStatBucket.serializer())
            val codec = codec(serializer)
            val original = write(listOf(codec.record(bucket(1, 1)), codec.record(bucket(2, 1))))
            val damaged = when (damage) {
                0 -> original.copy(hash = ByteArray(32))
                1 -> original.copy(records = original.records + 1)
                else -> {
                    original.file.appendBytes(byteArrayOf(0))
                    original.copy(bytes = original.bytes + 1, hash = digest(original.file))
                }
            }
            val output = temporary.newFolder()
            val failure = runCatching { reindex(damaged, codec, output) }.exceptionOrNull()
            val expectedMessage = when (damage) {
                0 -> "checksum mismatch"
                1 -> "record count mismatch"
                else -> "Unexpected playback staging record"
            }

            assertTrue(failure is IllegalArgumentException)
            assertTrue(checkNotNull(failure).message.orEmpty().contains(expectedMessage))
            assertEquals(2, serializer.decodes)
            assertTrue(output.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun `cancellation between validated read and append refuses the index`() = runBlocking {
        val serializer = SerializerProbe(SyncPlaybackStatBucket.serializer())
        val codec = codec(serializer)
        val input = write(listOf(codec.record(bucket(1, 1))))
        val child = Job()
        serializer.beforeDecode = { child.cancel() }
        val output = temporary.newFolder()
        val failure = runCatching {
            withContext(child) { reindex(input, codec, output) }
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(1, serializer.decodes)
        assertEquals(1, serializer.encodeAttempts)
        assertTrue(output.listFiles().orEmpty().isEmpty())
    }

    private suspend fun reindex(input: PlaybackFile, codec: PlaybackRecordCodec<SyncPlaybackStatBucket>, directory: File): PlaybackFile {
        return PlaybackFileSorter(directory, "identity", codec, dayFirst = false).use { sorter ->
            PlaybackFileReader(input, codec).use { reader ->
                while (true) {
                    val validated = reader.next() ?: break
                    sorter.appendValidatedRecord(validated)
                }
            }
            sorter.finish()
        }
    }

    private fun read(file: PlaybackFile, codec: PlaybackRecordCodec<SyncPlaybackStatBucket>): List<SyncPlaybackStatBucket> {
        return PlaybackFileReader(file, codec).use { reader ->
            buildList { while (true) { val record = reader.next() ?: break; add(record.value) } }
        }
    }

    private fun write(records: List<PlaybackRecord<SyncPlaybackStatBucket>>): PlaybackFile {
        return PlaybackFileWriter(temporary.newFile()).use { writer ->
            records.forEach(writer::write)
            writer.finish()
        }
    }

    private fun identityOrder(values: List<SyncPlaybackStatBucket>) = values.sortedWith(
        compareBy(SyncPlaybackKeyOrder, SyncPlaybackStatBucket::identityKey).thenBy(SyncPlaybackStatBucket::dayStartAt)
    )

    private fun bucket(key: Int, day: Long) = SyncPlaybackStatBucket(identityKey = "key-${key.toString().padStart(5, '0')}", dayStartAt = day, playCount = key)
    private fun codec(serializer: KSerializer<SyncPlaybackStatBucket>) = PlaybackRecordCodec(serializer, SyncPlaybackStatBucket::identityKey, SyncPlaybackStatBucket::dayStartAt)
    private fun digest(file: File): ByteArray = MessageDigest.getInstance("SHA-256").digest(file.readBytes())

    private class SerializerProbe(private val delegate: KSerializer<SyncPlaybackStatBucket>) : KSerializer<SyncPlaybackStatBucket> by delegate {
        var encodeAttempts = 0
        var decodes = 0
        var encodingAllowed = true
        var beforeDecode: () -> Unit = {}

        override fun serialize(encoder: Encoder, value: SyncPlaybackStatBucket) {
            encodeAttempts++
            if (!encodingAllowed) throw AssertionError("Validated reindex attempted to serialize an already validated record")
            delegate.serialize(encoder, value)
        }

        override fun deserialize(decoder: Decoder): SyncPlaybackStatBucket {
            decodes++
            beforeDecode()
            return delegate.deserialize(decoder)
        }
    }
}
