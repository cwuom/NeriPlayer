package moe.ouom.neriplayer.core.download.execution.clear

import android.content.Context
import android.content.SharedPreferences
import moe.ouom.neriplayer.core.download.admission.DownloadClearVisibility.ClearPhase
import moe.ouom.neriplayer.core.download.admission.DownloadClearVisibility.ClearProgress
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionHostTestSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

class PersistentDownloadClearProgressRestoreTest {
    private val store = PersistentDownloadClearProgressStore

    private val progress = ClearProgress(
        phase = ClearPhase.CLEANING,
        completedSteps = 2,
        totalSteps = 4,
        affectedItemCount = 12,
        failedItemCount = 1,
        completedItemCount = 7,
        totalItemCount = 12
    )

    @Test
    fun `saved clear progress is restored after a restart until it is cleared`() {
        val preferences = DownloadExecutionHostTestSupport.StatefulSharedPreferences()

        store.save(progressContext(preferences), progress)
        val restored = store.read(progressContext(preferences))
        store.clear(progressContext(preferences))

        assertEquals(progress, restored)
        assertNull(store.read(progressContext(preferences)))
        assertEquals(emptyMap<String, Any?>(), preferences.values)
    }

    @Test
    fun `progress without a known phase is not restored`() {
        val preferences = DownloadExecutionHostTestSupport.StatefulSharedPreferences()
        preferences.values["completed_steps"] = 3

        assertNull(store.read(progressContext(preferences)))
        preferences.values["phase"] = "ARCHIVING"
        assertNull(store.read(progressContext(preferences)))
        preferences.values["phase"] = ClearPhase.PURGING.name
        assertEquals(
            ClearProgress(phase = ClearPhase.PURGING, completedSteps = 3, totalSteps = 4, affectedItemCount = 0),
            store.read(progressContext(preferences))
        )
    }

    @Test
    fun `progress writes commit synchronously before returning`() {
        val editor = mock(SharedPreferences.Editor::class.java)
        doReturn(editor).`when`(editor).putString(anyString(), anyString())
        doReturn(editor).`when`(editor).putInt(anyString(), anyInt())
        doReturn(editor).`when`(editor).clear()
        doReturn(true).`when`(editor).commit()
        val preferences = mock(SharedPreferences::class.java)
        doReturn(editor).`when`(preferences).edit()
        val context = progressContext(preferences)

        store.save(context, progress)
        store.clear(context)

        verify(editor).putString("phase", "CLEANING")
        verify(editor).putInt("completed_steps", 2)
        verify(editor).putInt("affected_item_count", 12)
        verify(editor).putInt("failed_item_count", 1)
        verify(editor).putInt("completed_item_count", 7)
        verify(editor).putInt("total_item_count", 12)
        verify(editor).clear()
        verify(editor, times(2)).commit()
        verify(editor, never()).apply()
    }

    @Test
    fun `unavailable preferences degrade to no restored progress`() {
        val context = mock(Context::class.java)
        doReturn(context).`when`(context).applicationContext
        doThrow(IllegalStateException("credential storage locked"))
            .`when`(context).getSharedPreferences(anyString(), anyInt())

        store.save(context, progress)
        val restored = store.read(context)
        store.clear(context)

        assertNull(restored)
        verify(context, times(3)).getSharedPreferences("download_clear_progress_v1", Context.MODE_PRIVATE)
    }

    private fun progressContext(preferences: SharedPreferences): Context {
        val context = mock(Context::class.java)
        doReturn(context).`when`(context).applicationContext
        doReturn(preferences).`when`(context).getSharedPreferences(anyString(), anyInt())
        return context
    }
}
