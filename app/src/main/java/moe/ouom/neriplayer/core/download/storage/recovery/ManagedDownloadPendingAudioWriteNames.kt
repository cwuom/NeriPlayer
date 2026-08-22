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
        // pending 文件最后使用哨兵后缀, 防止 MediaStore 或播放器按音频扩展名处理
        return "$fileName$PENDING_AUDIO_WRITE_MARKER.$pendingId.pending"
    }

    fun logicalAudioName(name: String): String {
        val markerIndex = name.indexOf(PENDING_AUDIO_WRITE_MARKER)
        return name.takeIf { markerIndex <= 0 } ?: name.substring(0, markerIndex)
    }
}
