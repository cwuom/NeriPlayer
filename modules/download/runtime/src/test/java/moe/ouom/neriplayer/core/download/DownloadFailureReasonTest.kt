package moe.ouom.neriplayer.core.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.download.policy.downloadFailureReasonForErrorCode
import moe.ouom.neriplayer.core.download.policy.downloadSourceUnavailableErrorCode
import moe.ouom.neriplayer.core.download.policy.recoveredDownloadTaskPresentation
import moe.ouom.neriplayer.core.download.presentation.downloadFailureReasonMessageRes
import moe.ouom.neriplayer.core.download.task.DownloadTaskStore
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.download.DownloadFailureReason
import moe.ouom.neriplayer.data.model.download.DownloadStatus
import moe.ouom.neriplayer.data.model.download.DownloadTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadFailureReasonTest {

    @Test
    fun `source failure reasons survive the persisted operation error code`() {
        DownloadFailureReason.entries.forEach { reason ->
            assertEquals(
                reason,
                downloadFailureReasonForErrorCode(downloadSourceUnavailableErrorCode(reason))
            )
        }
        assertEquals(
            GlobalDownloadManager.DOWNLOAD_SOURCE_UNAVAILABLE_ERROR_CODE,
            downloadSourceUnavailableErrorCode(DownloadFailureReason.SOURCE_UNAVAILABLE)
        )
        assertNull(downloadFailureReasonForErrorCode("DOWNLOAD_INTEGRITY_CHECKSUM_MISMATCH"))
        assertNull(downloadFailureReasonForErrorCode(null))
    }

    @Test
    fun `restarted failed card keeps the source failure reason`() {
        assertEquals(
            DownloadFailureReason.PREVIEW_ONLY,
            recoveredDownloadTaskPresentation(
                operationState = "INVALID",
                stopRequestedByUser = false,
                batchStateBits = null,
                lastErrorCode = GlobalDownloadManager.DOWNLOAD_SOURCE_PREVIEW_ONLY_ERROR_CODE
            )?.failureReason
        )
        assertEquals(
            DownloadFailureReason.SOURCE_UNAVAILABLE,
            recoveredDownloadTaskPresentation(
                operationState = "INVALID",
                stopRequestedByUser = false,
                batchStateBits = null,
                lastErrorCode = GlobalDownloadManager.DOWNLOAD_SOURCE_UNAVAILABLE_ERROR_CODE
            )?.failureReason
        )
        assertNull(
            recoveredDownloadTaskPresentation(
                operationState = "INVALID",
                stopRequestedByUser = false,
                batchStateBits = null,
                lastErrorCode = "DOWNLOAD_INTEGRITY_CHECKSUM_MISMATCH"
            )?.failureReason
        )
    }

    @Test
    fun `task reason exists only while the task stays failed`() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val store = DownloadTaskStore(scope, progressEmitIntervalNs = 0L)
            val song = song(1L)
            val key = song.stableKey()
            store.ensureDownloadTasks(listOf(song))

            store.updateTaskStatus(key, DownloadStatus.FAILED, failureReason = DownloadFailureReason.PREVIEW_ONLY)
            assertEquals(DownloadFailureReason.PREVIEW_ONLY, store.findTask(key)?.failureReason)

            store.updateTaskStatus(key, DownloadStatus.QUEUED, failureReason = DownloadFailureReason.PREVIEW_ONLY)
            assertNull(store.findTask(key)?.failureReason)

            store.updateTaskStatus(key, DownloadStatus.FAILED, failureReason = DownloadFailureReason.SOURCE_UNAVAILABLE)
            store.updateTaskStatus(key, DownloadStatus.FAILED)
            assertNull(store.findTask(key)?.failureReason)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `recovered tasks only carry reasons for failed cards`() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val store = DownloadTaskStore(scope, progressEmitIntervalNs = 0L)
            val failed = song(1L)
            val queued = song(2L)
            store.ensureDownloadTasks(
                songs = listOf(failed, queued),
                statusesBySongKey = mapOf(
                    failed.stableKey() to DownloadStatus.FAILED,
                    queued.stableKey() to DownloadStatus.QUEUED
                ),
                failureReasonsBySongKey = mapOf(
                    failed.stableKey() to DownloadFailureReason.PREVIEW_ONLY,
                    queued.stableKey() to DownloadFailureReason.SOURCE_UNAVAILABLE
                )
            )

            assertEquals(
                DownloadFailureReason.PREVIEW_ONLY,
                store.findTask(failed.stableKey())?.failureReason
            )
            assertNull(store.findTask(queued.stableKey())?.failureReason)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `failure message is only offered for failed tasks with a known reason`() {
        val song = song(1L)
        assertEquals(
            CoreCommonR.string.download_failed_preview_only,
            downloadFailureReasonMessageRes(
                DownloadTask(song, null, DownloadStatus.FAILED, failureReason = DownloadFailureReason.PREVIEW_ONLY)
            )
        )
        assertEquals(
            CoreCommonR.string.download_failed_source_unavailable,
            downloadFailureReasonMessageRes(
                DownloadTask(song, null, DownloadStatus.FAILED, failureReason = DownloadFailureReason.SOURCE_UNAVAILABLE)
            )
        )
        assertNull(downloadFailureReasonMessageRes(DownloadTask(song, null, DownloadStatus.FAILED)))
        assertNull(
            downloadFailureReasonMessageRes(
                DownloadTask(song, null, DownloadStatus.QUEUED, failureReason = DownloadFailureReason.PREVIEW_ONLY)
            )
        )
    }

    private fun song(id: Long) = SongItem(
        id = id,
        name = "song-$id",
        artist = "artist",
        album = "Netease",
        albumId = 0L,
        durationMs = 180_000L,
        coverUrl = null
    )
}
