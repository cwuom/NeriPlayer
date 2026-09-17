package moe.ouom.neriplayer.core.download.artifact

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.model.SongItem

/**
 * 负责把已通过完整性校验的核心音频从 staging 提升到正式目录
 *
 * 资产增强和歌词整理不属于核心提交。把这一步单独隔离后，宿主生命周期变化不会
 * 把“已经下载完成”的音频长期留在 .tmp
 */
internal class DownloadCorePublicationCoordinator {
    suspend fun promoteBeforePublication(
        context: Context,
        song: SongItem,
        audio: ManagedDownloadStorage.StoredEntry
    ): ManagedDownloadStorage.StoredEntry {
        if (!audio.isPendingAudioWrite) {
            return audio
        }
        // 新版本通常已经把 core metadata 写到根目录，先走这条快速路径
        tryPromote(
            context = context,
            song = song,
            audio = audio,
            promotePendingMetadata = false
        )?.let { promoted ->
            return promoted
        }

        // 兼容旧版本只写出 pending metadata 的情况，补一次带 metadata 提升的路径
        tryPromote(
            context = context,
            song = song,
            audio = audio,
            promotePendingMetadata = true
        )?.let { promoted ->
            return promoted
        }

        // 并发恢复可能已经完成了提升而当前引用仍指向旧 pending，强制重扫一次
        // 正式目录，避免把已经存在的最终文件重新降级成 pending
        val reconciled = try {
            withContext(NonCancellable) {
                ManagedDownloadStorage.findDownloadedAudio(
                    context = context,
                    song = song,
                    forceRefresh = true
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            NPLogger.w(
                TAG,
                "core 音频提升后的正式目录重扫失败，保留可恢复 pending: " +
                    "song=${song.name}, error=${error.message}",
                error
            )
            null
        }
        if (reconciled != null && !reconciled.isPendingAudioWrite) {
            NPLogger.d(
                TAG,
                "core 音频重扫命中并发提升结果: song=${song.name}, " +
                    "file=${reconciled.name}"
            )
            return reconciled
        }
        NPLogger.w(
            TAG,
            "core 音频未确认正式文件，继续保留恢复凭据: " +
                "song=${song.name}, file=${audio.name}"
        )
        return audio
    }

    private suspend fun tryPromote(
        context: Context,
        song: SongItem,
        audio: ManagedDownloadStorage.StoredEntry,
        promotePendingMetadata: Boolean
    ): ManagedDownloadStorage.StoredEntry? {
        return try {
            withContext(NonCancellable) {
                ManagedDownloadStorage.promoteCoreCommittedPendingAudio(
                    context = context,
                    audio = audio,
                    promotePendingMetadata = promotePendingMetadata
                )
            }?.takeUnless { it.isPendingAudioWrite }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            NPLogger.w(
                TAG,
                "core 音频提升正式文件失败，保留可恢复 pending: " +
                    "song=${song.name}, file=${audio.name}, " +
                    "promotePendingMetadata=$promotePendingMetadata, " +
                    "error=${error.message}",
                error
            )
            null
        }
    }

    private companion object {
        const val TAG = "NERI-DownloadCorePublication"
    }
}
