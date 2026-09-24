package moe.ouom.neriplayer.core.download.catalog

import android.content.Context
import android.content.SharedPreferences
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.model.DownloadedSongDeletePhase
import moe.ouom.neriplayer.core.download.model.DownloadedSongDeleteProgress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class DownloadedSongDeleteFailureDismissalTest {
    @Test
    fun `dismissal is shared across screens and a later deletion resets it`() {
        val context = mock(Context::class.java)
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java, Answers.RETURNS_SELF)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.getSharedPreferences(
            "downloaded_song_delete_feedback", Context.MODE_PRIVATE
        )).thenReturn(preferences)
        `when`(preferences.edit()).thenReturn(editor)
        `when`(preferences.getBoolean("failure_dismissed", false)).thenReturn(true)

        val original = GlobalDownloadManager.downloadedSongDeleteProgressMutable.value
        try {
            GlobalDownloadManager.restoreDownloadedSongDeleteFailureDismissal(context)
            assertTrue(GlobalDownloadManager.downloadedSongDeleteFailureDismissed.value)
            GlobalDownloadManager.resetDownloadedSongDeleteFailureDismissal(context)
            assertFalse(GlobalDownloadManager.downloadedSongDeleteFailureDismissed.value)

            GlobalDownloadManager.downloadedSongDeleteProgressMutable.value =
                DownloadedSongDeleteProgress(7L, DownloadedSongDeletePhase.FAILED, 1)
            GlobalDownloadManager.dismissDownloadedSongDeleteFailure(context, 8L)
            assertFalse(GlobalDownloadManager.downloadedSongDeleteFailureDismissed.value)

            GlobalDownloadManager.dismissDownloadedSongDeleteFailure(context, 7L)
            assertTrue(GlobalDownloadManager.downloadedSongDeleteFailureDismissed.value)
            verify(editor).putBoolean("failure_dismissed", true)

            GlobalDownloadManager.resetDownloadedSongDeleteFailureDismissal(context)
            assertFalse(GlobalDownloadManager.downloadedSongDeleteFailureDismissed.value)
            verify(editor, org.mockito.Mockito.atLeastOnce()).putBoolean("failure_dismissed", false)
        } finally {
            GlobalDownloadManager.downloadedSongDeleteProgressMutable.value = original
            GlobalDownloadManager.resetDownloadedSongDeleteFailureDismissal(context)
        }
    }
}
