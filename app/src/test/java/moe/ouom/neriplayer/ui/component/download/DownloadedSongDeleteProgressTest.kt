package moe.ouom.neriplayer.ui.component.download

import moe.ouom.neriplayer.core.download.model.DownloadedSongDeletePhase
import moe.ouom.neriplayer.core.download.model.DownloadedSongDeleteProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadedSongDeleteProgressTest {

    @Test
    fun `physical deletion stays visible after request callback or screen recreation`() {
        val progress = progress(DownloadedSongDeletePhase.DELETING_REFERENCES)

        assertTrue(shouldShowDownloadedSongDeleteProgress(progress, requestedSongCount = 0))
        assertTrue(isDownloadedSongDeletionRunning(progress))
    }

    @Test
    fun `preparation is visible before manager publishes the first progress`() {
        assertTrue(shouldShowDownloadedSongDeleteProgress(null, requestedSongCount = 1000))
        assertFalse(shouldShowDownloadedSongDeleteProgress(null, requestedSongCount = 0))
    }

    @Test
    fun `directory and download waits keep deletion visible and prevent another delete`() {
        listOf(
            DownloadedSongDeletePhase.WAITING_FOR_DIRECTORY,
            DownloadedSongDeletePhase.WAITING_FOR_DOWNLOADS
        ).forEach { phase ->
            val progress = progress(phase)
            assertTrue(shouldShowDownloadedSongDeleteProgress(progress, requestedSongCount = 0))
            assertTrue(isDownloadedSongDeletionRunning(progress))
            assertNull(downloadedSongDeleteProgressFraction(progress))
        }
    }

    @Test
    fun `completed deletion hides only after request has returned`() {
        val progress = progress(DownloadedSongDeletePhase.COMPLETED)

        assertFalse(shouldShowDownloadedSongDeleteProgress(progress, requestedSongCount = 0))
        assertTrue(shouldShowDownloadedSongDeleteProgress(progress, requestedSongCount = 1000))
        assertFalse(isDownloadedSongDeletionRunning(progress))
    }

    @Test
    fun `failure remains visible without presenting an endless active operation`() {
        val progress = progress(DownloadedSongDeletePhase.FAILED)

        assertTrue(shouldShowDownloadedSongDeleteProgress(progress, requestedSongCount = 0))
        assertFalse(isDownloadedSongDeletionRunning(progress))
    }

    @Test
    fun `dismissed failure stays hidden while a new deletion can be shown`() {
        val failed = progress(DownloadedSongDeletePhase.FAILED)
        val nextDeletion = failed.copy(deleteId = 2L, phase = DownloadedSongDeletePhase.PREPARING)

        assertFalse(shouldShowDownloadedSongDeleteProgress(
            failed, requestedSongCount = 0, failureDismissed = true
        ))
        assertFalse(shouldShowDownloadedSongDeleteProgress(
            failed.copy(phase = DownloadedSongDeletePhase.DELETING_REFERENCES),
            requestedSongCount = 1,
            failureDismissed = true
        ))
        assertTrue(shouldShowDownloadedSongDeleteProgress(
            nextDeletion, requestedSongCount = 1, failureDismissed = false
        ))
    }

    @Test
    fun `file progress uses confirmed references and never counts failures as deleted`() {
        val progress = progress(DownloadedSongDeletePhase.DELETING_REFERENCES).copy(
            totalReferenceCount = 2000,
            completedReferenceCount = 500,
            failedReferenceCount = 100
        )

        assertEquals(0.25f, downloadedSongDeleteProgressFraction(progress)!!, 0f)
    }

    @Test
    fun `unknown or empty reference totals remain indeterminate`() {
        val progress = progress(DownloadedSongDeletePhase.READING_DELETE_PLAN)

        assertNull(downloadedSongDeleteProgressFraction(progress))
        assertNull(downloadedSongDeleteProgressFraction(progress.copy(totalReferenceCount = 0)))
    }

    @Test
    fun `finalizing and verification do not show physical deletion as overall completion`() {
        listOf(
            DownloadedSongDeletePhase.VERIFYING_REFERENCES,
            DownloadedSongDeletePhase.FINALIZING
        ).forEach { phase ->
            val progress = progress(phase).copy(
                totalReferenceCount = 2000,
                completedReferenceCount = 2000
            )
            assertTrue(isDownloadedSongDeletionRunning(progress))
            assertNull(downloadedSongDeleteProgressFraction(progress))
        }
    }

    @Test
    fun `file progress stays in the legal indicator range`() {
        val progress = progress(DownloadedSongDeletePhase.DELETING_REFERENCES).copy(
            totalReferenceCount = 10
        )

        assertEquals(0f, downloadedSongDeleteProgressFraction(progress.copy(completedReferenceCount = -1))!!, 0f)
        assertEquals(1f, downloadedSongDeleteProgressFraction(progress.copy(completedReferenceCount = 11))!!, 0f)
    }

    private fun progress(phase: DownloadedSongDeletePhase) = DownloadedSongDeleteProgress(
        deleteId = 1L,
        phase = phase,
        requestedSongCount = 1000,
        fullLibraryDelete = true
    )
}
