package moe.ouom.neriplayer.data.sync.dataset.disk

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import moe.ouom.neriplayer.data.sync.dataset.SyncPlaybackKeyOrder
import java.io.Closeable
import java.io.File

internal interface PlaybackStagingWriter<T> : Closeable {
    suspend fun append(value: T)
    suspend fun finish(): PlaybackFile
}

internal class PlaybackOrderedFile<T>(
    private val file: File,
    private val codec: PlaybackRecordCodec<T>,
    private val dayFirst: Boolean
) : PlaybackStagingWriter<T> {
    private var output: PlaybackFileWriter? = null
    private var previousIdentity: String? = null
    private var previousDay = 0L

    override suspend fun append(value: T) {
        currentCoroutineContext().ensureActive()
        val identity = codec.identity(value)
        val day = codec.day(value)
        validateOrder(identity, day)
        writer().write(codec.record(value))
        previousIdentity = identity
        previousDay = day
    }

    private fun validateOrder(identity: String, day: Long) {
        val previous = previousIdentity ?: return
        require(compare(previous, previousDay, identity, day) < 0) {
            "Ordered playback staging requires strictly increasing unique keys"
        }
    }

    private fun compare(leftIdentity: String, leftDay: Long, rightIdentity: String, rightDay: Long): Int {
        val days = leftDay.compareTo(rightDay)
        if (dayFirst && days != 0) return days
        return SyncPlaybackKeyOrder.compare(leftIdentity, rightIdentity).takeIf { it != 0 } ?: days
    }

    private fun writer(): PlaybackFileWriter = output ?: PlaybackFileWriter(file).also { output = it }

    override suspend fun finish(): PlaybackFile {
        currentCoroutineContext().ensureActive()
        return writer().use { it.finish() }
    }

    override fun close() {
        output?.close()
        previousIdentity = null
    }
}
