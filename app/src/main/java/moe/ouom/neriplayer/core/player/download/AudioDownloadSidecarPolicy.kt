package moe.ouom.neriplayer.core.player.download

/**
 * 下载旁车引用的合并策略
 *
 * 只合并同一引用的内容和创建权属，避免取消清理误删其他阶段写入的文件
 */
internal object AudioDownloadSidecarPolicy {
    internal fun mergeDownloadedSidecarReferences(
        existing: AudioDownloadManager.DownloadedSidecarReferences?,
        incoming: AudioDownloadManager.DownloadedSidecarReferences?
    ): AudioDownloadManager.DownloadedSidecarReferences {
        return AudioDownloadManager.DownloadedSidecarReferences(
            coverReference = incoming?.coverReference ?: existing?.coverReference,
            lyricContent = mergeSidecarContent(
                existingReference = existing?.lyricReference,
                existingContent = existing?.lyricContent,
                incomingReference = incoming?.lyricReference,
                incomingContent = incoming?.lyricContent
            ),
            translatedLyricContent = mergeSidecarContent(
                existingReference = existing?.translatedLyricReference,
                existingContent = existing?.translatedLyricContent,
                incomingReference = incoming?.translatedLyricReference,
                incomingContent = incoming?.translatedLyricContent
            ),
            romanizedLyricContent = mergeSidecarContent(
                existingReference = existing?.romanizedLyricReference,
                existingContent = existing?.romanizedLyricContent,
                incomingReference = incoming?.romanizedLyricReference,
                incomingContent = incoming?.romanizedLyricContent
            ),
            expectedCover = (existing?.expectedCover == true) ||
                (incoming?.expectedCover == true),
            createdCover = mergeSidecarCreatedFlag(
                existingReference = existing?.coverReference,
                existingCreated = existing?.createdCover ?: false,
                incomingReference = incoming?.coverReference,
                incomingCreated = incoming?.createdCover ?: false
            ),
            lyricReference = incoming?.lyricReference ?: existing?.lyricReference,
            createdLyric = mergeSidecarCreatedFlag(
                existingReference = existing?.lyricReference,
                existingCreated = existing?.createdLyric ?: false,
                incomingReference = incoming?.lyricReference,
                incomingCreated = incoming?.createdLyric ?: false
            ),
            translatedLyricReference = incoming?.translatedLyricReference
                ?: existing?.translatedLyricReference,
            expectedTranslatedLyric = (existing?.expectedTranslatedLyric == true) ||
                (incoming?.expectedTranslatedLyric == true),
            createdTranslatedLyric = mergeSidecarCreatedFlag(
                existingReference = existing?.translatedLyricReference,
                existingCreated = existing?.createdTranslatedLyric ?: false,
                incomingReference = incoming?.translatedLyricReference,
                incomingCreated = incoming?.createdTranslatedLyric ?: false
            ),
            romanizedLyricReference = incoming?.romanizedLyricReference
                ?: existing?.romanizedLyricReference,
            expectedLyric = (existing?.expectedLyric == true) ||
                (incoming?.expectedLyric == true),
            expectedRomanizedLyric = (existing?.expectedRomanizedLyric == true) ||
                (incoming?.expectedRomanizedLyric == true),
            createdRomanizedLyric = mergeSidecarCreatedFlag(
                existingReference = existing?.romanizedLyricReference,
                existingCreated = existing?.createdRomanizedLyric ?: false,
                incomingReference = incoming?.romanizedLyricReference,
                incomingCreated = incoming?.createdRomanizedLyric ?: false
            )
        )
    }

    private fun mergeSidecarContent(
        existingReference: String?,
        existingContent: String?,
        incomingReference: String?,
        incomingContent: String?
    ): String? {
        val normalizedIncomingReference = incomingReference
            ?.trim()
            ?.takeIf(String::isNotBlank)
        if (normalizedIncomingReference == null) {
            return existingContent
        }
        val normalizedExistingReference = existingReference
            ?.trim()
            ?.takeIf(String::isNotBlank)
        return when {
            !incomingContent.isNullOrBlank() -> incomingContent
            normalizedIncomingReference == normalizedExistingReference -> existingContent
            else -> null
        }
    }

    private fun mergeSidecarCreatedFlag(
        existingReference: String?,
        existingCreated: Boolean,
        incomingReference: String?,
        incomingCreated: Boolean
    ): Boolean {
        val incoming = incomingReference?.takeIf(String::isNotBlank)
            ?: return existingCreated
        val existing = existingReference?.takeIf(String::isNotBlank)
            ?: return incomingCreated
        return if (incoming == existing) {
            existingCreated || incomingCreated
        } else {
            incomingCreated
        }
    }
}
