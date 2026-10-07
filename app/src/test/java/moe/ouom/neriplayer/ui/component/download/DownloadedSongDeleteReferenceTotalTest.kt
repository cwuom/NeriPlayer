package moe.ouom.neriplayer.ui.component.download

import moe.ouom.neriplayer.data.model.download.DownloadedSongDeletePhase
import moe.ouom.neriplayer.data.model.download.DownloadedSongDeleteProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadedSongDeleteReferenceTotalTest {

    private val deletingReferences = DownloadedSongDeleteProgress(
        deleteId = 7L,
        phase = DownloadedSongDeletePhase.DELETING_REFERENCES,
        requestedSongCount = 3,
        completedReferenceCount = 2
    )

    @Test
    fun `reference deletion without a known total stays indeterminate`() {
        assertNull(downloadedSongDeleteProgressFraction(deletingReferences.copy(totalReferenceCount = null)))
    }

    @Test
    fun `reference deletion with an empty or negative total stays indeterminate`() {
        assertNull(downloadedSongDeleteProgressFraction(deletingReferences.copy(totalReferenceCount = 0)))
        assertNull(downloadedSongDeleteProgressFraction(deletingReferences.copy(totalReferenceCount = -2)))
    }

    @Test
    fun `reference deletion with a positive total reports completed over total`() {
        assertEquals(
            2f / 3f,
            downloadedSongDeleteProgressFraction(deletingReferences.copy(totalReferenceCount = 3))!!,
            0f
        )
    }
}
