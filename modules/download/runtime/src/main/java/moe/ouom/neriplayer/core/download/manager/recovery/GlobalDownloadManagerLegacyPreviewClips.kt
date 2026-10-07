package moe.ouom.neriplayer.core.download.manager.recovery

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.catalog.preview.LegacyPreviewClipCheckCodec
import moe.ouom.neriplayer.core.download.catalog.preview.legacyPreviewClipCandidate
import moe.ouom.neriplayer.core.download.catalog.preview.legacyPreviewClipSizes
import moe.ouom.neriplayer.core.download.catalog.preview.runLegacyPreviewClipPass
import moe.ouom.neriplayer.core.download.manager.admission.isDownloadAdmissionTicketCurrent
import moe.ouom.neriplayer.core.download.manager.commit.inspectFinalizedDownloadedAudio
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity

/**
 * 旧版把网易云试听片段当作完整歌曲保存；这里只做标记，不改动音频文件和 sidecar
 *
 * 每个文件只探测一次真实时长，结果按引用和大小记录，重新下载后自动失效
 */
internal fun GlobalDownloadManager.markLegacyPreviewClipsFromRoot(
    context: Context,
    admissionTicket: Long? = downloadAdmissionGate.openTicketOrNull()
) {
    val appContext = context.applicationContext
    if (!legacyPreviewClipCheckActive.compareAndSet(false, true)) {
        return
    }
    scope.launch {
        try {
            if (
                admissionTicket == null ||
                    !isDownloadAdmissionTicketCurrent(appContext, admissionTicket)
            ) {
                return@launch
            }
            checkLegacyPreviewClips(appContext)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            NPLogger.w(TAG, "旧版下载试听片段检查失败，等待下次启动: ${error.message}")
        } finally {
            legacyPreviewClipCheckActive.set(false)
        }
    }
}

private suspend fun GlobalDownloadManager.checkLegacyPreviewClips(context: Context) {
    val dao = NeriUserDataDatabase.getInstance(context).syncMetadataDao()
    val stored = LegacyPreviewClipCheckCodec.decode(
        dao.getMigrationMetadata(LegacyPreviewClipCheckCodec.METADATA_KEY)?.value
    )
    legacyPreviewClipsMutable.value = legacyPreviewClipSizes(stored)
    val snapshot = ManagedDownloadStorage.buildDownloadLibrarySnapshot(
        context = context,
        forceRefresh = false
    )
    val audioByReference = snapshot.audioEntries.associateBy { it.reference }
    val result = runLegacyPreviewClipPass(
        stored = stored,
        candidates = snapshot.audioEntries.takeIf { snapshot.rootEntriesComplete }?.mapNotNull { audio ->
            ManagedDownloadStorage.metadataForAudioEntry(snapshot, audio)?.let { metadata ->
                legacyPreviewClipCandidate(audio.reference, audio.sizeBytes, metadata)
            }
        },
        probeDurationMs = { reference ->
            audioByReference[reference]?.let { audio -> inspectFinalizedDownloadedAudio(context, audio).durationMs }
        }
    )
    if (result.changed) {
        dao.upsertMigrationMetadata(
            MigrationMetadataEntity(
                key = LegacyPreviewClipCheckCodec.METADATA_KEY,
                value = LegacyPreviewClipCheckCodec.encode(result.checks),
                updatedAt = System.currentTimeMillis()
            )
        )
    }
    val previewClips = legacyPreviewClipSizes(result.checks)
    legacyPreviewClipsMutable.value = previewClips
    NPLogger.d(
        TAG,
        "旧版下载试听片段检查: probed=${result.probedCount}, previewClips=${previewClips.size}, " +
            "remaining=${result.remainingCount}"
    )
}
