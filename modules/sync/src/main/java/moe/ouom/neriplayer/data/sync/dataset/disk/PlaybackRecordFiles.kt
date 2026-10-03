@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package moe.ouom.neriplayer.data.sync.dataset.disk

import kotlinx.serialization.KSerializer
import kotlinx.serialization.protobuf.ProtoBuf
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.sync.dataset.SyncPlaybackKeyOrder
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

internal class PlaybackRecordCodec<T>(
    val serializer: KSerializer<T>, val identity: (T) -> String, val day: (T) -> Long
) {
    fun record(value: T): PlaybackRecord<T> = PlaybackRecord(value, ProtoBuf.encodeToByteArray(serializer, value),
        identity(value).toByteArray(Charsets.UTF_8), day(value))
    fun decode(bytes: ByteArray): PlaybackRecord<T> {
        val value = ProtoBuf.decodeFromByteArray(serializer, bytes)
        return PlaybackRecord(value, bytes, identity(value).toByteArray(Charsets.UTF_8), day(value))
    }
}

internal val trackCodec = PlaybackRecordCodec(SyncTrackStat.serializer(), SyncTrackStat::identityKey) { 0L }
internal val bucketCodec = PlaybackRecordCodec(SyncPlaybackStatBucket.serializer(), SyncPlaybackStatBucket::identityKey, SyncPlaybackStatBucket::dayStartAt)
internal data class PlaybackRecord<T>(val value: T, val payload: ByteArray, val identity: ByteArray, val day: Long)
internal data class PlaybackFile(val file: File, val records: Long, val bytes: Long, val hash: ByteArray)

internal class PlaybackFileWriter(private val file: File) : java.io.Closeable {
    private val fileOutput = FileOutputStream(file)
    private val digest = MessageDigest.getInstance("SHA-256")
    private val output = DataOutputStream(DigestOutputStream(fileOutput, digest).buffered(PLAYBACK_IO_BUFFER_BYTES))
    private var records = 0L
    private var bytes = 0L
    fun <T> write(record: PlaybackRecord<T>) {
        require(record.payload.size <= MAX_PLAYBACK_RECORD_BYTES) { "Playback record exceeds decoding budget" }
        output.writeInt(record.identity.size)
        output.writeLong(record.day)
        output.writeInt(record.payload.size)
        output.write(record.identity)
        output.write(record.payload)
        records++
        bytes = Math.addExact(bytes, record.payload.size.toLong() + record.identity.size + RECORD_HEADER_BYTES)
    }
    fun <T> copy(reader: PlaybackFileReader<T>, header: PlaybackRecordHeader) {
        output.writeInt(header.keyBytes)
        output.writeLong(header.day)
        output.writeInt(header.payloadBytes)
        header.copyKey(output)
        reader.copyPayload(header, output)
        records++
        bytes = Math.addExact(bytes, header.payloadBytes.toLong() + header.keyBytes + RECORD_HEADER_BYTES)
    }
    fun finish(): PlaybackFile {
        output.flush()
        fileOutput.fd.sync()
        return PlaybackFile(file, records, bytes, digest.digest())
    }
    override fun close() = output.close()
}

internal class PlaybackFileReader<T>(private val file: PlaybackFile, private val codec: PlaybackRecordCodec<T>) : java.io.Closeable {
    private val digest = MessageDigest.getInstance("SHA-256")
    private val input = DataInputStream(DigestInputStream(openValidated(file), digest).buffered(PLAYBACK_IO_BUFFER_BYTES))
    private var records = 0L
    private var bytes = 0L
    private var finished = false
    private val copyBuffer = ByteArray(COPY_BYTES)
    fun next(): PlaybackRecord<T>? {
        val header = nextHeader() ?: return null
        val payload = ByteArray(header.payloadBytes).also(input::readFully)
        consumed(header)
        val record = codec.decode(payload)
        header.validateRecord(record.identity, record.day)
        return record
    }
    fun nextHeader(): PlaybackRecordHeader? {
        if (finished) return null
        val first = input.read()
        if (first == -1) {
            validateEnd()
            return null
        }
        require(records < file.records) { "Unexpected playback staging record" }
        val keyBytes = (first shl 24) or (input.readUnsignedByte() shl 16) or
            (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
        val day = input.readLong()
        val payloadBytes = input.readInt()
        validatePayloadLength(payloadBytes)
        validateKeyLength(keyBytes, payloadBytes)
        require(keyBytes.toLong() + payloadBytes <= file.bytes - bytes - RECORD_HEADER_BYTES) { "Truncated playback staging record" }
        val prefix = ByteArray(minOf(KEY_PREFIX_BYTES, keyBytes)).also(input::readFully)
        copyExactly(input, null, keyBytes - prefix.size, copyBuffer)
        return PlaybackRecordHeader(file.file, bytes + RECORD_HEADER_BYTES, keyBytes, day, payloadBytes, prefix)
    }
    private fun validateEnd() {
        require(records == file.records) { "Playback staging record count mismatch" }
        require(bytes == file.bytes) { "Playback staging byte count mismatch" }
        require(MessageDigest.isEqual(digest.digest(), file.hash)) { "Playback staging checksum mismatch" }
        finished = true
    }
    private fun validatePayloadLength(length: Int) {
        require(length >= 0) { "Negative playback staging payload length" }
        require(length <= MAX_PLAYBACK_RECORD_BYTES) { "Playback staging payload exceeds decoding budget" }
    }
    private fun validateKeyLength(length: Int, payloadBytes: Int) {
        require(length >= 0) { "Negative playback staging key length" }
        require(length <= payloadBytes) { "Playback staging key exceeds payload length" }
    }
    fun copyPayload(header: PlaybackRecordHeader, output: DataOutputStream) {
        copyExactly(input, output, header.payloadBytes, copyBuffer)
        consumed(header)
    }
    private fun consumed(header: PlaybackRecordHeader) {
        records++
        bytes += header.payloadBytes.toLong() + header.keyBytes + RECORD_HEADER_BYTES
    }
    override fun close() = input.close()

    companion object {
        private fun openValidated(file: PlaybackFile): java.io.InputStream {
            require(file.file.length() == file.bytes) { "Playback staging length mismatch" }
            return FileInputStream(file.file)
        }
    }
}

internal data class PlaybackRecordHeader(
    val file: File, val keyOffset: Long, val keyBytes: Int, val day: Long,
    val payloadBytes: Int, val prefix: ByteArray
) {
    fun validateRecord(key: ByteArray, recordDay: Long) {
        require(recordDay == day) { "Playback staging day mismatch" }
        require(matchesKey(key)) { "Playback staging key mismatch" }
    }
    fun copyKey(output: DataOutputStream) {
        if (keyBytes <= KEY_PREFIX_BYTES) {
            output.write(prefix)
            return
        }
        RandomAccessFile(file, "r").use {
            it.seek(keyOffset)
            copyExactly(it, output, keyBytes)
        }
    }
    fun matchesKey(key: ByteArray): Boolean {
        if (key.size != keyBytes) return false
        if (keyBytes <= KEY_PREFIX_BYTES) return prefix.contentEquals(key)
        return RandomAccessFile(file, "r").use {
            it.seek(keyOffset)
            val buffer = ByteArray(COPY_BYTES)
            var offset = 0
            while (offset < keyBytes) {
                val size = minOf(buffer.size, keyBytes - offset)
                it.readFully(buffer, 0, size)
                for (index in 0 until size) if (buffer[index] != key[offset + index]) return false
                offset += size
            }
            true
        }
    }
    fun compareKey(other: PlaybackRecordHeader): Int {
        val first = SyncPlaybackKeyOrder.compareBytes(prefix, other.prefix)
        if (first != 0) return first
        if (keyBytes <= KEY_PREFIX_BYTES || other.keyBytes <= KEY_PREFIX_BYTES) return keyBytes.compareTo(other.keyBytes)
        return compareSuffix(other)
    }
    private fun compareSuffix(other: PlaybackRecordHeader): Int = RandomAccessFile(file, "r").use { left ->
            RandomAccessFile(other.file, "r").use { right ->
                left.seek(keyOffset + KEY_PREFIX_BYTES); right.seek(other.keyOffset + KEY_PREFIX_BYTES)
                val a = ByteArray(COPY_BYTES); val b = ByteArray(COPY_BYTES)
                var offset = KEY_PREFIX_BYTES
                while (offset < minOf(keyBytes, other.keyBytes)) {
                    val size = minOf(COPY_BYTES, minOf(keyBytes, other.keyBytes) - offset)
                    left.readFully(a, 0, size); right.readFully(b, 0, size)
                    for (index in 0 until size) {
                        val difference = (a[index].toInt() and 255) - (b[index].toInt() and 255)
                        if (difference != 0) return difference
                    }
                    offset += size
                }
                keyBytes.compareTo(other.keyBytes)
            }
        }
}

internal fun copyExactly(input: java.io.DataInput, output: DataOutputStream?, count: Int, buffer: ByteArray = ByteArray(COPY_BYTES)) {
    var remaining = count
    while (remaining > 0) {
        val size = minOf(buffer.size, remaining)
        input.readFully(buffer, 0, size)
        output?.write(buffer, 0, size)
        remaining -= size
    }
}

internal const val MAX_PLAYBACK_RECORD_BYTES = 64 * 1024 * 1024
internal const val MERGE_FANOUT = 32
internal const val SORT_RECORDS = 4096
internal const val SORT_BYTES = 4 * 1024 * 1024
internal const val RECORD_HEADER_BYTES = 16L
internal const val KEY_PREFIX_BYTES = 256
internal const val COPY_BYTES = 8192
internal const val PLAYBACK_IO_BUFFER_BYTES = 64 * 1024
