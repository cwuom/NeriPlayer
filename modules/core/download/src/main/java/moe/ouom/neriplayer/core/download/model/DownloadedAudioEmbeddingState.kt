package moe.ouom.neriplayer.core.download.model

import java.util.Locale

/** 已下载音频元信息嵌入的可审计完成状态 */
enum class DownloadedAudioEmbeddingState {
    EMBEDDED_VERIFIED,
    USER_DISABLED,
    LEGACY_V15_FINALIZED,
    UNSUPPORTED_CONTAINER,
    LEGACY_UNVERIFIED;

    companion object {
        fun fromPersisted(value: String?): DownloadedAudioEmbeddingState? {
            val normalized = value?.trim()?.takeIf(String::isNotBlank)
                ?.uppercase(Locale.ROOT)
                ?: return null
            return entries.firstOrNull { state -> state.name == normalized }
        }
    }
}
