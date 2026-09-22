package moe.ouom.neriplayer.core.download.artifact

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.isFinalizedDownloadedMetadata
import moe.ouom.neriplayer.core.download.model.publicationOwnerId
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import moe.ouom.neriplayer.core.download.storage.operation.content.isVerifiedAudioPublicationTarget
import moe.ouom.neriplayer.core.download.storage.operation.content.samePublicationReference
import moe.ouom.neriplayer.core.download.storage.operation.resolveRootBlocking
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey

/**
 * 核心音频保留恢复凭据，元信息完成后才对正式目录发布
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
        val metadata = readMetadata(context, audio) ?: return audio
        if (metadata.stableKey != song.stableKey() || !isFinalizedDownloadedMetadata(metadata)) {
            return audio
        }
        tryPromote(
            context = context,
            song = song,
            audio = audio
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
        val reconciledMetadata = reconciled?.let { readMetadata(context, it) }
        if (
            reconciled != null && !reconciled.isPendingAudioWrite && reconciledMetadata != null &&
            metadata.publicationOwnerId() != null &&
            reconciledMetadata.publicationOwnerId() == metadata.publicationOwnerId() &&
            reconciledMetadata.stableKey == metadata.stableKey &&
            isFinalizedDownloadedMetadata(reconciledMetadata) &&
            withContext(Dispatchers.IO) {
                samePublicationReference(audio.reference, reconciled.reference) ||
                    ManagedDownloadStorage.isVerifiedAudioPublicationTarget(
                        context, ManagedDownloadStorage.resolveRootBlocking(context), audio.name,
                        audio.logicalName, reconciled.reference, audio.reference
                    )
            }
        ) {
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
        audio: ManagedDownloadStorage.StoredEntry
    ): ManagedDownloadStorage.StoredEntry? {
        return try {
            withContext(NonCancellable) {
                ManagedDownloadStorage.promoteFinalizedPendingAudio(
                    context = context,
                    audio = audio
                )?.audio
            }?.takeUnless { it.isPendingAudioWrite }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            NPLogger.w(
                TAG,
                "core 音频提升正式文件失败，保留可恢复 pending: " +
                    "song=${song.name}, file=${audio.name}, " +
                    "error=${error.message}",
                error
            )
            null
        }
    }

    private suspend fun readMetadata(
        context: Context,
        audio: ManagedDownloadStorage.StoredEntry
    ): ManagedDownloadStorage.DownloadedAudioMetadata? {
        val entry = ManagedDownloadStorage.findMetadataForAudio(context, audio) ?: return null
        return ManagedDownloadStorage.readText(context, entry.reference)
            ?.let(ManagedDownloadStorage::parseDownloadedAudioMetadataJson)
    }

    private companion object {
        const val TAG = "NERI-DownloadCorePublication"
    }
}
