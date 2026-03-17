package moe.ouom.neriplayer.core.api.youtube

import java.util.Locale
import kotlin.jvm.Volatile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.util.NPLogger
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo

class YouTubeMusicPlaybackRepository(
    okHttpClient: OkHttpClient
) {
    private val downloader = NewPipeOkHttpDownloader(okHttpClient)

    suspend fun getBestPlayableAudioUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        ensureInitialized()
        runCatching {
            val streamInfo = StreamInfo.getInfo(
                ServiceList.YouTube,
                "https://music.youtube.com/watch?v=$videoId"
            )
            selectPlayableAudio(streamInfo.audioStreams)
        }.onFailure { error ->
            NPLogger.e("YouTubeMusicPlayback", "extract stream failed for $videoId", error)
        }.getOrNull()
    }

    private fun ensureInitialized() {
        if (initialized) {
            return
        }
        synchronized(initializationLock) {
            if (initialized) {
                return
            }
            val locale = Locale.getDefault()
            NewPipe.init(
                downloader,
                Localization(
                    locale.language.ifBlank { "en" },
                    locale.country.ifBlank { "US" }
                )
            )
            initialized = true
        }
    }

    private fun selectPlayableAudio(streams: List<AudioStream>): String? {
        val progressive = streams
            .asSequence()
            .filter { it.isUrl }
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .sortedWith(
                compareByDescending<AudioStream> { it.averageBitrate }
                    .thenByDescending { it.bitrate }
            )
            .map { it.content }
            .firstOrNull { it.isNotBlank() }

        if (!progressive.isNullOrBlank()) {
            return progressive
        }

        return streams
            .asSequence()
            .filter { it.isUrl }
            .sortedWith(
                compareByDescending<AudioStream> { it.averageBitrate }
                    .thenByDescending { it.bitrate }
            )
            .map { it.content }
            .firstOrNull { it.isNotBlank() }
    }

    private companion object {
        val initializationLock = Any()

        @Volatile
        var initialized: Boolean = false
    }
}
