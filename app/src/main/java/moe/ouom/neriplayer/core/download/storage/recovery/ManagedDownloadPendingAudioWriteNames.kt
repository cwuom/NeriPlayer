package moe.ouom.neriplayer.core.download.storage.recovery

import moe.ouom.neriplayer.core.download.storage.PENDING_AUDIO_WRITE_MARKER
import java.util.concurrent.atomic.AtomicLong

internal class ManagedDownloadPendingAudioWriteNames {
    private val pendingAudioWriteIdGenerator = AtomicLong(0L)

    fun isPendingAudioWriteName(name: String): Boolean {
        return name.contains(PENDING_AUDIO_WRITE_MARKER)
    }

    fun buildPendingAudioWriteName(fileName: String): String {
        val pendingId = pendingAudioWriteIdGenerator.incrementAndGet()
        return "$fileName$PENDING_AUDIO_WRITE_MARKER.$pendingId"
    }
}
