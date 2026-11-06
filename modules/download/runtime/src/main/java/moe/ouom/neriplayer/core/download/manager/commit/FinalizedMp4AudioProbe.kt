@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.download.manager.commit

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private const val MAX_PROBE_READ_BYTES = 4 * 1024 * 1024
private const val MAX_PROBE_SEEKS = 64
private const val MAX_PROBE_TABLE_ENTRIES = 1_000_000L
private const val MAX_PROBE_BOX_DEPTH = 16

internal suspend fun readFinalizedMp4AudioDuration(context: Context, uri: Uri): Long? {
    val coroutineContext = currentCoroutineContext()
    return context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { asset ->
        ParcelFileDescriptor.AutoCloseInputStream(asset.parcelFileDescriptor).use streamUse@{ stream ->
            val channel = stream.channel
            val startOffset = asset.startOffset
            val availableLength = channel.size() - startOffset
            val length = asset.declaredLength.takeIf { it >= 0L }
                ?.coerceAtMost(availableLength) ?: availableLength
            if (startOffset < 0L || length <= 0L) return@streamUse null
            val reader = Mp4ProbeReader(channel, startOffset, length, coroutineContext)
            val mediaRanges = Mp4ProbePreflight(reader).inspect()
            reader.seek(0L)
            var input = DefaultExtractorInput(reader, 0L, length)
            val extractor = Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED)
            try {
                if (!extractor.sniff(input)) return@streamUse null
                input.resetPeekPosition()
                val output = Mp4AudioMetadataOutput(mediaRanges)
                extractor.init(output)
                val seekPosition = PositionHolder()
                while (!output.hasReadableAudioSample) {
                    coroutineContext.ensureActive()
                    when (extractor.read(input, seekPosition)) {
                        Extractor.RESULT_END_OF_INPUT -> break
                        Extractor.RESULT_SEEK -> {
                            reader.seek(seekPosition.position)
                            input = DefaultExtractorInput(reader, seekPosition.position, length)
                        }
                    }
                }
                output.audioDurationMs()
            } finally {
                extractor.release()
            }
        }
    }
}

private class Mp4ProbeReader(
    private val channel: FileChannel,
    private val startOffset: Long,
    val length: Long,
    private val coroutineContext: CoroutineContext
) : DataReader {
    private var bytesRead = 0L
    private var seeks = 0

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        coroutineContext.ensureActive()
        val remaining = this.length - (channel.position() - startOffset)
        if (remaining <= 0L) return C.RESULT_END_OF_INPUT
        val readLength = length.toLong().coerceAtMost(remaining).toInt()
        if (bytesRead + readLength > MAX_PROBE_READ_BYTES) {
            throw IOException("MP4 audio probe exceeded read budget")
        }
        return channel.read(ByteBuffer.wrap(buffer, offset, readLength)).also { count ->
            if (count > 0) bytesRead += count
        }
    }

    fun seek(position: Long) {
        coroutineContext.ensureActive()
        if (position !in 0..length) throw IOException("MP4 audio probe requested an invalid seek")
        if (channel.position() == startOffset + position) return
        if (++seeks > MAX_PROBE_SEEKS) throw IOException("MP4 audio probe exceeded seek budget")
        channel.position(startOffset + position)
    }

    fun readAt(position: Long, length: Int): ByteBuffer {
        seek(position)
        val buffer = ByteArray(length)
        var count = 0
        while (count < length) {
            val read = read(buffer, count, length - count)
            if (read < 0) throw IOException("MP4 audio probe encountered truncated metadata")
            count += read
        }
        return ByteBuffer.wrap(buffer)
    }

    fun unsignedIntAt(position: Long): Long = readAt(position, 4).int.toLong() and 0xffff_ffffL
}

private class Mp4ProbePreflight(private val reader: Mp4ProbeReader) {
    private val mediaRanges = mutableListOf<LongRange>()
    private var tableEntries = 0L
    private var samples = 0L
    private var edits = 0L
    private var chunks = 0L
    private var maxSamplesPerChunk = 0L

    fun inspect(): List<LongRange> {
        inspectBoxes(0L, reader.length, 0)
        if (samples * edits.coerceAtLeast(1L) > MAX_PROBE_TABLE_ENTRIES ||
            chunks * maxSamplesPerChunk > MAX_PROBE_TABLE_ENTRIES
        ) {
            throw IOException("MP4 audio probe exceeded sample table budget")
        }
        return mediaRanges
    }

    private fun inspectBoxes(
        start: Long,
        end: Long,
        depth: Int,
        metadataEntries: Boolean = false,
        parentType: String? = null
    ) {
        if (depth > MAX_PROBE_BOX_DEPTH) throw IOException("MP4 audio probe exceeded box depth budget")
        var position = start
        while (position < end) {
            if (end - position < 8L) throw IOException("MP4 audio probe encountered a truncated box")
            val header = reader.readAt(position, 8)
            var size = header.int.toLong() and 0xffff_ffffL
            val type = String(header.array(), 4, 4, Charsets.ISO_8859_1)
            val headerSize = if (size == 1L) 16L else 8L
            if (size == 1L) size = reader.readAt(position + 8L, 8).long
            if (size == 0L) size = end - position
            if (size < headerSize || size > end - position) {
                throw IOException("MP4 audio probe encountered an invalid box range")
            }
            val payload = position + headerSize
            val boxEnd = position + size
            // Media3 会在读取前按 box 大小和表计数分配内存，输入字节预算无法限制这些分配
            if (size > MAX_PROBE_READ_BYTES && (type == "moov" || type in ALLOCATED_LEAF_BOXES)) {
                throw IOException("MP4 audio probe exceeded metadata allocation budget")
            }
            if (type == "mdat") {
                if (depth != 0 && (depth != 1 || parentType != "axte")) {
                    throw IOException("MP4 audio probe encountered media data inside metadata")
                }
                mediaRanges += payload until boxEnd
            } else if (type in CONTAINER_BOXES || metadataEntries) {
                if (type == "meta" && boxEnd - payload < 4L) {
                    throw IOException("MP4 audio probe encountered truncated metadata")
                }
                val childStart = if (type == "meta" && reader.unsignedIntAt(payload) == 0L) {
                    payload + 4L
                } else {
                    payload
                }
                inspectBoxes(childStart, boxEnd, depth + 1, type == "ilst", type)
            } else {
                inspectTable(type, payload, boxEnd)
            }
            position = boxEnd
        }
    }

    private fun inspectTable(type: String, payload: Long, end: Long) {
        val countOffset = when (type) {
            "stsz", "stz2" -> 8L
            in COUNTED_TABLE_BOXES -> 4L
            else -> return
        }
        if (end - payload < countOffset + 4L) throw IOException("MP4 audio probe encountered a truncated table")
        val count = reader.unsignedIntAt(payload + countOffset)
        tableEntries += count
        if (tableEntries > MAX_PROBE_TABLE_ENTRIES) throw IOException("MP4 audio probe exceeded table budget")
        when (type) {
            "stsd" -> inspectFlacSampleEntries(payload + 8L, end, count)
            "stsz", "stz2" -> samples += count
            "elst" -> edits += count
            "stco", "co64" -> chunks += count
            "stsc" -> {
                if (count > (end - payload - 8L) / 12L) throw IOException("MP4 audio probe encountered a truncated chunk table")
                repeat(count.toInt()) { index ->
                    val entry = reader.readAt(payload + 8L + index * 12L, 12)
                    val samplesPerChunk = entry.getInt(4).toLong() and 0xffff_ffffL
                    maxSamplesPerChunk = maxOf(maxSamplesPerChunk, samplesPerChunk)
                }
            }
        }
    }

    private fun inspectFlacSampleEntries(start: Long, end: Long, count: Long) {
        var entry = start
        repeat(count.toInt()) {
            if (end - entry < 36L) throw IOException("MP4 audio probe encountered a truncated sample entry")
            val header = reader.readAt(entry, 8)
            val size = header.int.toLong() and 0xffff_ffffL
            if (size < 36L || size > end - entry) throw IOException("MP4 audio probe encountered an invalid sample entry")
            val type = String(header.array(), 4, 4, Charsets.ISO_8859_1)
            // 这里只补足 Bilibili FLAC 容器，其他编码仍使用平台和 TagLib，避免接受未受预算保护的编码配置
            if (type != "fLaC" || reader.readAt(entry + 16L, 2).short.toInt() != 0) {
                throw IOException("MP4 audio probe does not support this sample entry")
            }
            inspectFlacConfiguration(entry + 36L, entry + size)
            entry += size
        }
    }

    private fun inspectFlacConfiguration(start: Long, end: Long) {
        var position = start
        var streamInfoFound = false
        while (position < end) {
            if (end - position < 8L) throw IOException("MP4 audio probe encountered truncated codec configuration")
            val header = reader.readAt(position, 8)
            val size = header.int.toLong() and 0xffff_ffffL
            val type = String(header.array(), 4, 4, Charsets.ISO_8859_1)
            if (size < 8L || size > end - position) {
                throw IOException("MP4 audio probe encountered an invalid codec configuration range")
            }
            when (type) {
                "dfLa" -> {
                    if (size < 50L || reader.unsignedIntAt(position + 8L) != 0L) {
                        throw IOException("MP4 audio probe encountered invalid FLAC configuration")
                    }
                    val streamInfo = reader.unsignedIntAt(position + 12L)
                    if (streamInfo and 0x7f00_0000L != 0L || streamInfo and 0x00ff_ffffL != 34L) {
                        throw IOException("MP4 audio probe requires FLAC STREAMINFO")
                    }
                    streamInfoFound = true
                }
                "btrt" -> if (size < 20L) throw IOException("MP4 audio probe encountered truncated bitrate metadata")
                "free" -> Unit
                else -> throw IOException("MP4 audio probe does not support this FLAC configuration box")
            }
            position += size
        }
        if (!streamInfoFound) throw IOException("MP4 audio probe requires FLAC configuration")
    }

    companion object {
        private val CONTAINER_BOXES = setOf("moov", "trak", "mdia", "minf", "stbl", "edts", "meta", "udta", "ilst", "axte")
        private val COUNTED_TABLE_BOXES = setOf("stsd", "stco", "co64", "stsc", "stss", "stts", "ctts", "elst", "keys")
        private val ALLOCATED_LEAF_BOXES = COUNTED_TABLE_BOXES + setOf("stsz", "stz2", "mdhd", "mvhd", "hdlr", "tkhd", "ftyp", "udta", "ilst")
    }
}

private class Mp4AudioMetadataOutput(private val mediaRanges: List<LongRange>) : ExtractorOutput {
    private val audioTracks = mutableMapOf<Int, Mp4AudioTrackOutput>()
    val hasReadableAudioSample: Boolean
        get() = audioTracks.values.any { it.hasAudioFormat && it.sampleReadable }

    override fun track(id: Int, type: Int): TrackOutput = if (type == C.TRACK_TYPE_AUDIO) {
        audioTracks.getOrPut(id) { Mp4AudioTrackOutput(mediaRanges) }
    } else {
        DiscardingTrackOutput()
    }

    override fun endTracks() = Unit

    override fun seekMap(seekMap: SeekMap) = Unit

    fun audioDurationMs(): Long? = audioTracks.values.mapNotNull { track ->
        track.durationUs.takeIf { track.sampleReadable && track.hasAudioFormat && it > 0L }
            ?.div(1_000L)?.takeIf { it > 0L }
    }.maxOrNull()
}

private class Mp4AudioTrackOutput(
    private val mediaRanges: List<LongRange>,
    private val samples: DiscardingTrackOutput = DiscardingTrackOutput()
) : TrackOutput by samples {
    var durationUs = C.TIME_UNSET
        private set
    var hasAudioFormat = false
        private set
    var sampleReadable = false
        private set
    private var sourceBytesRead = 0L

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean): Int =
        sampleData(input, length, allowEndOfInput, TrackOutput.SAMPLE_DATA_PART_MAIN)

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        val position = (input as? ExtractorInput)?.position
            ?: throw IOException("MP4 audio probe cannot locate sample bytes")
        if (mediaRanges.none { position >= it.first && length.toLong() <= it.last - position + 1L }) {
            throw IOException("MP4 audio sample points outside the media payload")
        }
        return samples.sampleData(input, length, allowEndOfInput, sampleDataPart).also { count ->
            if (count > 0) sourceBytesRead += count
        }
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        sampleReadable = size > 0 && sourceBytesRead > 0L
        sourceBytesRead = 0L
    }

    override fun durationUs(durationUs: Long) {
        this.durationUs = durationUs
    }

    override fun format(format: Format) {
        hasAudioFormat = MimeTypes.isAudio(format.sampleMimeType) &&
            format.sampleRate > 0 && format.channelCount > 0
    }
}
