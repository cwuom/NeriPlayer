@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.lan

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.*
import moe.ouom.neriplayer.core.player.PlayerManager
import java.io.File
import java.util.UUID

/** Native authenticated datasource -> bounded session file. Never exposes auth or remote URLs to JS. */
internal class LanAudioFileLoader {
    private val directory = File(PlayerManager.application.cacheDir, "lan-audio")
    fun clearAbandoned() {
        directory.listFiles()?.filter { it.isFile && it.name.matches(Regex("[a-f0-9-]{36}\\.media")) }?.forEach { it.delete() }
    }
    suspend fun download(url: String, sampleRate: Int, expectedDurationMs: Long): LanAudioFile = withContext(Dispatchers.IO) {
        check(url.startsWith("http://") || url.startsWith("https://")) { "SOURCE" }
        directory.mkdirs()
        val file = File(directory, "${UUID.randomUUID()}.media")
        val source = checkNotNull(PlayerManager.conditionalHttpFactory) { "SOURCE" }.createDataSource()
        try {
            val length = source.open(DataSpec(Uri.parse(url)))
            check(length == C.LENGTH_UNSET.toLong() || length <= MAX_ENCODED) { "MEMORY_LIMIT" }
            file.outputStream().use { output ->
                val bytes = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    ensureActive()
                    val count = source.read(bytes, 0, bytes.size)
                    if (count == C.RESULT_END_OF_INPUT) break
                    total += count
                    check(total <= MAX_ENCODED) { "MEMORY_LIMIT" }
                    output.write(bytes, 0, count)
                }
            }
            check(file.length() > 0) { "SOURCE" }
            LanAudioFile(file, validate(file, sampleRate, expectedDurationMs))
        } catch (error: Throwable) { file.delete(); throw error }
        finally { source.close() }
    }
    fun validate(file: File, sampleRate: Int, expectedDurationMs: Long): LanAudioFormat =
        validate(Uri.fromFile(file), file.length(), sampleRate, expectedDurationMs)
    fun validate(uri: Uri, byteLength: Long, sampleRate: Int, expectedDurationMs: Long): LanAudioFormat {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(PlayerManager.application, uri, null)
            val format = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: error("AUDIO_FORMAT")
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            check(channels in 1..2) { "MEMORY_LIMIT" }
            check(format.containsKey(MediaFormat.KEY_DURATION)) { "AUDIO_FORMAT" }
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)
            check(moe.ouom.neriplayer.lanltw.core.LanAudioDuration.matches(expectedDurationMs, durationUs / 1000)) { "AUDIO_DURATION" }
            // Browser decodes to the running AudioContext sample rate. Bound before allocating.
            check(sampleRate in 8000..192000) { "MEMORY_LIMIT" }
            check(moe.ouom.neriplayer.data.model.lan.lanPcmFits(durationUs, sampleRate, channels)) { "MEMORY_LIMIT" }
            return LanAudioFormat(format.getString(MediaFormat.KEY_MIME),
                format.optionalInt(MediaFormat.KEY_BIT_RATE)?.div(1000)
                    ?: moe.ouom.neriplayer.data.model.playback.estimateBitrateKbps(byteLength, durationUs / 1000),
                format.optionalInt(MediaFormat.KEY_SAMPLE_RATE), channels, format.optionalInt("bits-per-sample"))
        } catch (error: IllegalStateException) { throw error }
        catch (_: Exception) { error("AUDIO_FORMAT") }
        finally { extractor.release() }
    }
    private fun MediaFormat.optionalInt(key: String): Int? =
        if (containsKey(key)) getInteger(key).takeIf { it > 0 } else null
    fun clear(file: File?) { file?.delete() }
    companion object { private const val MAX_ENCODED = moe.ouom.neriplayer.data.model.lan.LanLocalMedia.MAX_BYTES }
}
