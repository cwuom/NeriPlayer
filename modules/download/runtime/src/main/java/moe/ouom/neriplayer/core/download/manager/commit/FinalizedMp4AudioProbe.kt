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
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import java.io.IOException
import java.nio.ByteBuffer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private const val MAX_PROBE_READ_BYTES = 4 * 1024 * 1024
private const val MAX_PROBE_SEEKS = 64

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
            channel.position(startOffset)
            var bytesRead = 0L
            val reader = DataReader { buffer, offset, requestedLength ->
                coroutineContext.ensureActive()
                val remaining = length - (channel.position() - startOffset)
                if (remaining <= 0L) {
                    C.RESULT_END_OF_INPUT
                } else {
                    val readLength = requestedLength.toLong().coerceAtMost(remaining).toInt()
                    if (bytesRead + readLength > MAX_PROBE_READ_BYTES) {
                        throw IOException("MP4 audio probe exceeded metadata read budget")
                    }
                    channel.read(ByteBuffer.wrap(buffer, offset, readLength)).also { count ->
                        if (count > 0) bytesRead += count
                    }
                }
            }
            var input = DefaultExtractorInput(reader, 0L, length)
            val extractor = Mp4Extractor(
                SubtitleParser.Factory.UNSUPPORTED,
                Mp4Extractor.FLAG_OMIT_TRACK_SAMPLE_TABLE
            )
            try {
                if (!extractor.sniff(input)) return@streamUse null
                input.resetPeekPosition()
                val output = Mp4AudioMetadataOutput()
                extractor.init(output)
                val seekPosition = PositionHolder()
                var seeks = 0
                while (!output.tracksComplete) {
                    coroutineContext.ensureActive()
                    when (extractor.read(input, seekPosition)) {
                        Extractor.RESULT_END_OF_INPUT -> break
                        Extractor.RESULT_SEEK -> {
                            if (output.tracksComplete) break
                            if (++seeks > MAX_PROBE_SEEKS || seekPosition.position !in 0..length) {
                                throw IOException("MP4 audio probe requested an invalid seek")
                            }
                            channel.position(startOffset + seekPosition.position)
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

private class Mp4AudioMetadataOutput : ExtractorOutput {
    private val audioTracks = mutableMapOf<Int, Mp4AudioTrackOutput>()
    var tracksComplete = false
        private set

    override fun track(id: Int, type: Int): TrackOutput = if (type == C.TRACK_TYPE_AUDIO) {
        audioTracks.getOrPut(id) { Mp4AudioTrackOutput() }
    } else {
        DiscardingTrackOutput()
    }

    override fun endTracks() {
        tracksComplete = true
    }

    override fun seekMap(seekMap: SeekMap) = Unit

    fun audioDurationMs(): Long? = audioTracks.values.mapNotNull { track ->
        track.durationUs.takeIf { tracksComplete && track.hasAudioFormat && it > 0L }
            ?.div(1_000L)?.takeIf { it > 0L }
    }.maxOrNull()
}

private class Mp4AudioTrackOutput : TrackOutput by DiscardingTrackOutput() {
    var durationUs = C.TIME_UNSET
        private set
    var hasAudioFormat = false
        private set

    override fun durationUs(durationUs: Long) {
        this.durationUs = durationUs
    }

    override fun format(format: Format) {
        hasAudioFormat = MimeTypes.isAudio(format.sampleMimeType) &&
            format.sampleRate > 0 && format.channelCount > 0
    }
}
