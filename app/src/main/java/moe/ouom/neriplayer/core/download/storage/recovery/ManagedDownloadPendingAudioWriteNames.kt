package moe.ouom.neriplayer.core.download.storage.recovery

import moe.ouom.neriplayer.core.download.storage.PENDING_AUDIO_WRITE_MARKER
import moe.ouom.neriplayer.core.download.storage.audioExtensions
import java.util.UUID

internal class ManagedDownloadPendingAudioWriteNames {
    fun isPendingAudioWriteName(name: String): Boolean {
        return isArtifactName(name)
    }

    fun buildPendingAudioWriteName(fileName: String): String {
        val pendingId = UUID.randomUUID()
        // pending 文件最后使用哨兵后缀, 防止 MediaStore 或播放器按音频扩展名处理
        return "$fileName$PENDING_AUDIO_WRITE_MARKER.$pendingId.pending"
    }

    fun logicalAudioName(name: String): String {
        val markerIndex = name.indexOf(PENDING_AUDIO_WRITE_MARKER)
        return name.takeIf { markerIndex <= 0 || !isArtifactName(name) }
            ?: name.substring(0, markerIndex)
    }

    companion object {
        /** 仅识别完整哨兵后缀，避免歌曲名偶然包含 marker 被误当临时文件 */
        fun isArtifactName(name: String): Boolean {
            val markerIndex = name.indexOf(PENDING_AUDIO_WRITE_MARKER)
            if (markerIndex <= 0) return false
            val suffix = name.substring(markerIndex + PENDING_AUDIO_WRITE_MARKER.length)
            if (suffix.isEmpty()) return true
            if (suffix.startsWith('.') && suffix.endsWith(".pending") &&
                suffix.length > ".pending".length + 1
            ) {
                return true
            }
            // 兼容早期只追加短 token 的 pending 名称，同时避免把普通歌曲标题
            // 中的 marker 当成临时文件
            val finalName = name.substring(0, markerIndex)
            val finalExtension = finalName.substringAfterLast('.', "").lowercase()
            return finalExtension in audioExtensions &&
                suffix.startsWith('.') && suffix.length > 1
        }
    }
}
