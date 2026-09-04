package moe.ouom.neriplayer.core.player.download

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal class DownloadProgressEventStream<T : Any>(
    bufferCapacity: Int
) {
    init {
        require(bufferCapacity > 0) { "bufferCapacity must be positive" }
    }

    private val mutableEvents = MutableSharedFlow<T>(
        replay = 0,
        extraBufferCapacity = bufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val events: SharedFlow<T> = mutableEvents.asSharedFlow()

    fun publish(event: T) {
        mutableEvents.tryEmit(event)
    }
}
