package moe.ouom.neriplayer.data.local.media.curation

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.coroutines.runCatchingNonCancellation
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.inspectTagLibMetadata
import moe.ouom.neriplayer.data.local.media.localMediaUriCandidates
import moe.ouom.neriplayer.data.local.media.resolveInspectableLocalMedia
import moe.ouom.neriplayer.data.model.SongItem

/** 音频文件自身携带的词图状态；只描述嵌入标签，不包含侧载 `.lrc` 或 Covers 目录 */
data class LocalEmbeddedAssetState(
    val embeddedLyricBytes: Int,
    val embeddedCoverBytes: Int,
    val fileSizeBytes: Long?,
    val audioExtension: String?
) {
    val hasEmbeddedLyrics: Boolean
        get() = embeddedLyricBytes > 0

    val hasEmbeddedCover: Boolean
        get() = embeddedCoverBytes > 0
}

object LocalEmbeddedAssetInspector {
    private const val TAG = "LocalEmbeddedAssets"

    /** 文件不可读或容器不受 TagLib 支持时返回 null，调用方应把它当作"无法嵌入"而不是"缺失" */
    suspend fun inspect(context: Context, song: SongItem): LocalEmbeddedAssetState? =
        withContext(Dispatchers.IO) {
            song.localMediaUriCandidates().firstNotNullOfOrNull { candidate ->
                runCatchingNonCancellation { inspectCandidate(context, candidate) }
                    .onFailure { error ->
                        NPLogger.d(TAG, "inspect embedded assets failed: ${error.javaClass.simpleName}")
                    }
                    .getOrNull()
            }
        }

    private fun inspectCandidate(context: Context, candidate: Uri): LocalEmbeddedAssetState? {
        val resolved = LocalMediaSupport.resolveInspectableLocalMedia(
            context = context,
            uri = candidate,
            allowDescriptorFallback = true
        )
        val metadata = LocalMediaSupport.inspectTagLibMetadata(
            context = context,
            uri = resolved.playableUri,
            file = resolved.file,
            includeEmbeddedAssets = true,
            includeEmbeddedLyrics = true,
            includeAudioProperties = false
        ) ?: return null
        return LocalEmbeddedAssetState(
            embeddedLyricBytes = metadata.lyrics?.trim()?.toByteArray()?.size ?: 0,
            embeddedCoverBytes = metadata.coverBytes?.size ?: 0,
            fileSizeBytes = resolved.file?.length()?.takeIf { it > 0L }
                ?: resolved.queried.sizeBytes?.takeIf { it > 0L },
            audioExtension = resolved.fileExtension
        )
    }
}
