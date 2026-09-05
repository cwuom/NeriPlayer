package moe.ouom.neriplayer.core.player.download

import java.io.EOFException
import java.net.URI
import moe.ouom.neriplayer.data.traffic.TrafficByteAccumulator
import okio.BufferedSink
import okio.BufferedSource

/**
 * HLS 清单和分段的无状态处理
 *
 * 这里只解析和复制字节，不负责 operation、取消或 checkpoint，避免把协议细节
 * 和下载生命周期锁在同一个对象里
 */
internal object AudioHlsSegmentSupport {
    internal fun parseSegmentUrls(
        playlistUrl: String,
        playlistText: String
    ): List<String> {
        val lines = playlistText.lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toList()
        require(lines.any { it == "#EXTM3U" }) {
            "HLS playlist is missing EXTM3U header"
        }
        val unsupportedTag = lines.firstOrNull { line ->
            line.startsWith("#EXT-X-KEY", ignoreCase = true) ||
                line.startsWith("#EXT-X-MAP", ignoreCase = true) ||
                line.startsWith("#EXT-X-BYTERANGE", ignoreCase = true) ||
                line.startsWith("#EXT-X-I-FRAMES-ONLY", ignoreCase = true)
        }
        require(unsupportedTag == null) {
            "Unsupported HLS tag: ${unsupportedTag?.substringBefore(':')}"
        }
        require(lines.none { it.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) }) {
            "HLS master playlists are not supported"
        }
        return lines
            .filter { !it.startsWith('#') }
            .map { segment ->
                runCatching { URI(playlistUrl).resolve(segment).toString() }
                    .getOrElse { segment }
            }
    }

    internal fun parseMediaSequence(playlistText: String): Long? {
        return playlistText.lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("#EXT-X-MEDIA-SEQUENCE", ignoreCase = true) }
            ?.substringAfter(':', "")
            ?.trim()
            ?.toLongOrNull()
            ?.takeIf { it >= 0L }
    }

    internal fun copySegment(
        source: BufferedSource,
        sink: BufferedSink,
        trafficAccumulator: TrafficByteAccumulator,
        maxSegmentBytes: Long,
        readBufferBytes: Int,
        prefixDigest: java.security.MessageDigest? = null,
        expectedRawBytes: Long? = null,
        onNetworkActivity: (() -> Unit)? = null
    ): Long {
        val header = ByteArray(10)
        var headerBytes = 0
        var rawBytes = 0L
        while (headerBytes < header.size) {
            val read = source.read(header, headerBytes, header.size - headerBytes)
            if (read == -1) {
                break
            }
            onNetworkActivity?.invoke()
            headerBytes += read
            rawBytes += read
            trafficAccumulator.add(read.toLong())
        }
        require(rawBytes <= maxSegmentBytes) {
            "HLS segment exceeds limit: $rawBytes > $maxSegmentBytes"
        }

        var outputBytes = 0L
        val hasId3Header = headerBytes == header.size &&
            header[0] == 'I'.code.toByte() &&
            header[1] == 'D'.code.toByte() &&
            header[2] == '3'.code.toByte()
        if (!hasId3Header) {
            sink.write(header, 0, headerBytes)
            prefixDigest?.update(header, 0, headerBytes)
            outputBytes += headerBytes
        } else {
            val tagSize =
                ((header[6].toInt() and 0x7f) shl 21) or
                    ((header[7].toInt() and 0x7f) shl 14) or
                    ((header[8].toInt() and 0x7f) shl 7) or
                    (header[9].toInt() and 0x7f)
            val remainingTagBytes = tagSize.toLong()
            require(10L + remainingTagBytes <= maxSegmentBytes) {
                "HLS ID3 tag exceeds limit: ${10L + remainingTagBytes} > $maxSegmentBytes"
            }
            var skipped = 0L
            val skipBuffer = ByteArray(readBufferBytes)
            while (skipped < remainingTagBytes) {
                val requested = minOf(
                    skipBuffer.size.toLong(),
                    remainingTagBytes - skipped
                ).toInt()
                val read = source.read(skipBuffer, 0, requested)
                if (read == -1) {
                    throw EOFException("HLS ID3 tag is truncated")
                }
                onNetworkActivity?.invoke()
                skipped += read
                rawBytes += read
                trafficAccumulator.add(read.toLong())
                require(rawBytes <= maxSegmentBytes) {
                    "HLS segment exceeds limit: $rawBytes > $maxSegmentBytes"
                }
            }
        }

        val buffer = ByteArray(readBufferBytes)
        while (true) {
            val read = source.read(buffer)
            if (read == -1) {
                break
            }
            onNetworkActivity?.invoke()
            rawBytes += read.toLong()
            trafficAccumulator.add(read.toLong())
            require(rawBytes <= maxSegmentBytes) {
                "HLS segment exceeds limit: $rawBytes > $maxSegmentBytes"
            }
            sink.write(buffer, 0, read)
            prefixDigest?.update(buffer, 0, read)
            outputBytes += read.toLong()
        }
        expectedRawBytes?.let { expected ->
            if (rawBytes != expected) {
                throw IllegalStateException(
                    "HLS segment length mismatch: expected=$expected, actual=$rawBytes"
                )
            }
        }
        return outputBytes
    }
}
