package moe.ouom.neriplayer.core.player.dlna.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import moe.ouom.neriplayer.core.player.dlna.protocol.Mp4MetadataWriter
import java.io.File
import java.nio.ByteBuffer

/**
 * Remuxes fragmented MP4 streams (e.g. Bilibili DASH .m4s) into standard
 * non-fragmented .m4a without decoding or re-encoding a single audio sample,
 * so iTunes ©nam/©ART/©alb metadata can be embedded cleanly for DLNA renderers.
 */
object Mp4Defragmenter {

    fun defragmentIfNeeded(
        sourceFile: File,
        outputFile: File,
        isCancelled: () -> Boolean = { false }
    ): File {
        if (!Mp4MetadataWriter.hasMoofBox(sourceFile)) return sourceFile
        val tmpFile = File(outputFile.parentFile, "${outputFile.name}.remux_tmp")
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        return try {
            extractor.setDataSource(sourceFile.absolutePath)
            var audioTrack = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    audioFormat = fmt
                    break
                }
            }
            val selectedFormat = audioFormat ?: return sourceFile
            extractor.selectTrack(audioTrack)

            muxer = MediaMuxer(tmpFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxTrack = muxer.addTrack(selectedFormat)
            muxer.start()
            muxerStarted = true

            val bufferSize = if (selectedFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                selectedFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceIn(64 * 1024, 1024 * 1024)
            } else {
                256 * 1024
            }
            val buffer = ByteBuffer.allocateDirect(bufferSize)
            val info = MediaCodec.BufferInfo()
            while (!isCancelled()) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                info.offset = 0
                info.size = sampleSize
                info.presentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                val sampleFlags = extractor.sampleFlags
                info.flags = if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
                muxer.writeSampleData(muxTrack, buffer, info)
                extractor.advance()
            }

            if (isCancelled() || tmpFile.length() <= 0L) {
                tmpFile.delete()
                return sourceFile
            }
            muxer.stop()
            muxerStarted = false
            muxer.release()
            muxer = null

            if (outputFile.exists()) outputFile.delete()
            if (tmpFile.renameTo(outputFile)) outputFile else sourceFile
        } catch (_: Throwable) {
            tmpFile.delete()
            sourceFile
        } finally {
            if (muxerStarted) {
                runCatching { muxer?.stop() }
            }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }
}
